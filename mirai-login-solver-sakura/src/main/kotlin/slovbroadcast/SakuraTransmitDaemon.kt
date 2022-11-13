/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.slovbroadcast

import com.google.gson.JsonElement
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.socksx.SocksVersion
import io.netty.handler.codec.socksx.v4.*
import io.netty.handler.codec.socksx.v5.*
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import io.netty.util.AttributeKey
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import net.mamoe.mirai.utils.*
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.random.Random

class SakuraTransmitDaemon(
    @JvmField val eventLoopGroup: EventLoopGroup,
    private val serverChannelType: Class<out ServerChannel>,
    private val clientChannelType: Class<out Channel>,
    private val random: Random,
    private val logger: MiraiLogger,
) {
    @JvmField
    var isSocksTunnelEnabled: Boolean = true

    private val requests = ConcurrentHashMap<String, ResolveRequest>()
    private lateinit var serverChannel: ServerChannel

    val serverPort: Int get() = (serverChannel.localAddress() as InetSocketAddress).port

    fun bootServer(inetPort: Int = 0) {
        val rspx = ServerBootstrap()
            .channel(serverChannelType)
            .group(eventLoopGroup, eventLoopGroup)
            .childHandler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.attr(DAEMON).set(this@SakuraTransmitDaemon)

                    ch.pipeline()
                        .addLast("r-timeout", ReadTimeoutHandler(2, TimeUnit.MINUTES))
                        .addLast("w-timeout", WriteTimeoutHandler(2, TimeUnit.MINUTES))
                        .addFirst("exception-caught", object : ChannelInboundHandlerAdapter() {
                            @Suppress("OVERRIDE_DEPRECATION")
                            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
                                logger.error({ "${ctx.channel()} [exceptionCaught]" }, cause)

                                ctx.fireExceptionCaught(cause)
                            }
                        })


                    if (isSocksTunnelEnabled) {
                        logger.debug { "$ch [initial] Waiting first packet" }

                        ch.pipeline().addLast("1st-prepare-handler", FstPrepareHandler())
                    } else {
                        ch.pipeline()
                            .addLast("http-req-decoder", HttpRequestDecoder())
                            .addLast("http-rsp-encoder", HttpResponseEncoder())
                            .addLast("http-handler", HttpConnectHandler())
                    }
                }
            })
            .bind(inetPort)
            .sync()

        if (rspx.isSuccess) {
            serverChannel = rspx.channel() as ServerChannel
        }
    }

    fun shutdown() {
        if (::serverChannel.isInitialized) {
            serverChannel.close().await()
        }
    }

    inner class ResolveRequest {
        fun fireCompleted() {
            if (::continuation.isInitialized) {
                val data = msgData
                msgData = Unpooled.EMPTY_BUFFER
                continuation.resumeWith(Result.success(data))
            } else {
                msgData.release()
                msgData = Unpooled.EMPTY_BUFFER
            }
        }

        internal lateinit var requestId: String
        internal lateinit var msgData: ByteBuf

        private lateinit var continuation: CancellableContinuation<ByteBuf>

        suspend fun awaitResponse(): ByteBuf = suspendCancellableCoroutine { cont ->
            continuation = cont
            cont.invokeOnCancellation {
                if (requests.remove(requestId, this@ResolveRequest)) {
                    if (::msgData.isInitialized) {
                        msgData.release()
                        msgData = Unpooled.EMPTY_BUFFER
                    }
                }
            }
        }

        fun renderQR(): BitMatrix = Companion.renderQR(serverPort, requestId)
    }

    fun newRequest(msg: JsonElement, initialReqId: String? = null): ResolveRequest {
        val request = ResolveRequest()
        var id: String = initialReqId ?: generateNewRequestId()

        do {
            if (requests.putIfAbsent(id, request) == null) break

            id = generateNewRequestId()
        } while (true)

        request.requestId = id

        val reqMsgData = serverChannel.alloc().buffer(256)

        JsonWriter(OutputStreamWriter(ByteBufOutputStream(reqMsgData))).use { jwriter ->
            jwriter.beginObject()
                .name("reqid").value(id)
                .name("rspuri").value("/request/complete/$id")
                .name("create-time").value(System.currentTimeMillis())
                .name("data")

            Streams.write(msg, jwriter)

            if (isSocksTunnelEnabled) {
                jwriter.name("tunnel").value("socks://<serverip>:$serverPort")
            }

            jwriter.endObject()
        }

        request.msgData = reqMsgData

        return request
    }

    private fun processHttpMsg(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is HttpRequest) {
            logger.debug { "${ctx.channel()} [http   ] New http request: ${msgFormat(msg)}" }
            logger.verbose { "${ctx.channel()} [http   ] $msg" }


            val uri = msg.uri()

            if (uri == "/") {
                ctx.channel().writeAndFlush(
                    DefaultFullHttpResponse(
                        msg.protocolVersion(), HttpResponseStatus.OK,
                        Unpooled.EMPTY_BUFFER,
                        serverHttpRspHeaders().add("Content-Length", 0),
                        EmptyHttpHeaders.INSTANCE,
                    )
                )
                return
            }

            if (msg.method() == HttpMethod.GET && uri.startsWith("/request/request/")) {
                requests[uri.substring(17)]?.let { request ->
                    ctx.channel().writeAndFlush(
                        DefaultFullHttpResponse(
                            msg.protocolVersion(), HttpResponseStatus.OK,
                            request.msgData.retainedDuplicate(),
                            serverHttpRspHeaders()
                                .add("Content-Length", request.msgData.readableBytes())
                                .add("Content-Type", "application/json")
                                .add("Content-Encoding", "UTF-8"),
                            EmptyHttpHeaders.INSTANCE,
                        )
                    )
                    return
                }
            }

            if (msg.method() == HttpMethod.POST && uri.startsWith("/request/complete/")) {
                requests[uri.substring(18)]?.let procx@{ request ->
                    val contentLength = msg.headers().get("Content-Length", "0").toIntOrNull() ?: return@procx
                    if (contentLength > 40960) {
                        ctx.channel().writeAndFlush(
                            DefaultFullHttpResponse(
                                msg.protocolVersion(), HttpResponseStatus.FORBIDDEN,
                                Unpooled.EMPTY_BUFFER,
                                serverHttpRspHeaders().add("Content-Length", 0),
                                EmptyHttpHeaders.INSTANCE,
                            )
                        )
                        return@procx
                    }
                    // Request not found
                    if (!requests.remove(request.requestId, request)) return@procx

                    val response = request.msgData
                    response.clear()

                    val httpReq = msg

                    ctx.channel().pipeline()
                        .addBefore("http-handler", "post-msg-receiver", object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                if (msg is HttpContent) {
                                    response.writeBytes(msg.content())
                                    msg.release()

                                    if (msg is LastHttpContent) {
                                        // completed
                                        logger.debug {
                                            "${ctx.channel()} [http   ] [${request.requestId}] post response: ${
                                                response.toString(
                                                    Charsets.UTF_8
                                                )
                                            }"
                                        }

                                        ctx.channel().writeAndFlush(
                                            DefaultFullHttpResponse(
                                                httpReq.protocolVersion(), HttpResponseStatus.OK,
                                                Unpooled.EMPTY_BUFFER,
                                                serverHttpRspHeaders().add("Content-Length", 0),
                                                EmptyHttpHeaders.INSTANCE,
                                            )
                                        )

                                        request.fireCompleted()
                                    }
                                } else {
                                    ctx.fireChannelRead(msg)
                                }
                            }
                        })

                    return
                }
            }

            ctx.channel().writeAndFlush(
                DefaultFullHttpResponse(
                    msg.protocolVersion(), HttpResponseStatus.NOT_FOUND,
                    Unpooled.EMPTY_BUFFER,
                    serverHttpRspHeaders().add("Content-Length", 0),
                    EmptyHttpHeaders.INSTANCE,
                )
            )
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    companion object {
        private val DAEMON = AttributeKey.newInstance<SakuraTransmitDaemon>("sakura-transmit-daemon")

        private val ChannelHandlerContext.sdaemon: SakuraTransmitDaemon get() = channel().sdaemon
        private val Channel.sdaemon: SakuraTransmitDaemon get() = attr(DAEMON).get()

        private fun msgFormat(msg: Any?): String {
            if (msg is HttpRequest) {
                return buildString {
                    append(simpleClassName(msg))
                    append("(decodeResult: ")
                    append(msg.decoderResult())
                    append(", version: ")
                    append(msg.protocolVersion())
                    append(") - ")
                    append(msg.method())
                    append(' ')
                    append(msg.uri())
                    append(' ')
                    append(msg.protocolVersion())
                }
            }
            return msg.toString()
        }

        private fun doConnect(
            ctx: ChannelHandlerContext,
            dstAddr: String,
            dstPort: Int,
        ): ChannelFuture {
            return Bootstrap()
                .channel(run {
                    if (ctx.channel().hasAttr(DAEMON)) {
                        return@run ctx.channel().attr(DAEMON).get().clientChannelType
                    }
                    return@run NioSocketChannel::class.java
                })
                .group(ctx.channel().eventLoop())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        ch.pipeline()
                            .addLast("r-timeout", ReadTimeoutHandler(2, TimeUnit.MINUTES))
                            .addLast("w-timeout", WriteTimeoutHandler(2, TimeUnit.MINUTES))
                            .addLast("forward", MsgForwardHandler(ctx.channel()))

                        if (ctx.channel().hasAttr(DAEMON)) {
                            ch.attr(DAEMON).set(ctx.sdaemon)
                        }
                    }
                })
                .connect(dstAddr, dstPort)
        }

        fun loopMachineAvailableIp(action: Consumer<String>) {
            NetworkInterface.getNetworkInterfaces().asSequence().filterNot { inet ->
                inet.isLoopback || inet.isVirtual || !inet.isUp
            }.flatMap { it.inetAddresses.asSequence() }.filter {
                it.isSiteLocalAddress && it is Inet4Address
            }.map { iaddr ->
                iaddr.address.joinToString(".") { java.lang.Byte.toUnsignedInt(it).toString() }
            }.forEach(action::accept)
        }

        fun renderQRInfo(port: Int, reqId: String): String {
            val buf = StringWriter()
            JsonWriter(buf).use { jw ->
                jw.beginObject()

                jw.name("port").value(port)

                jw.name("server").beginArray()
                loopMachineAvailableIp(jw::value)
                jw.endArray()

                jw.name("id").value(reqId)

                jw.endObject()
            }
            return buf.toString()
        }

        fun renderQR(port: Int, reqId: String): BitMatrix = renderQRCode(renderQRInfo(port, reqId))

        fun renderQRCode(
            text: String,
            width: Int = 400, height: Int = 400,
            ec: ErrorCorrectionLevel = ErrorCorrectionLevel.H,
        ): BitMatrix {
            return QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, width, height, mapOf(
                    EncodeHintType.ERROR_CORRECTION to ec
                )
            )
        }

        private fun simpleClassName(o: Any?): String {
            return if (o == null) {
                "null_object"
            } else {
                simpleClassName(o.javaClass)
            }
        }

        /**
         * Generates a simplified name from a [Class].  Similar to [Class.getSimpleName], but it works fine
         * with anonymous classes.
         */
        private fun simpleClassName(clazz: Class<*>): String {
            val className = clazz.name
            val lastDotIdx = className.lastIndexOf('.')
            return if (lastDotIdx > -1) {
                className.substring(lastDotIdx + 1)
            } else className
        }
    }

    private fun serverHttpRspHeaders() = DefaultHttpHeaders()
        .add("Server", "netty/1.1.4.5.1.4")
        .add("Cache-Control", "private, no-cache, no-store, proxy-revalidate, no-transform")
        .add("Connection", "keep-alive")

    private fun generateNewRequestId(): String = buildString {
        repeat(8) {
            append(('0'..'9').random(random))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    private class MsgForwardHandler(
        private val target: Channel,
    ) : ChannelInboundHandlerAdapter() {
        override fun channelActive(ctx: ChannelHandlerContext) {
            super.channelActive(ctx)
            if (!target.isActive || !target.isOpen) {
                ctx.channel().close()
                ctx.sdaemon.logger.warning { "${ctx.channel()} -> $target [tunnel ] Failed to bind forwarding because target is inactive" }
            } else {
                ctx.sdaemon.logger.verbose { "${ctx.channel()} -> $target [tunnel ] Established" }
            }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            target.writeAndFlush(msg).addListener { f ->
                if (!f.isSuccess) exceptionCaught(ctx, f.cause())
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
            super.exceptionCaught(ctx, cause)

            ctx.sdaemon.logger.warning({ "${ctx.channel()} -> $target [tunnel ] [exceptionCaught]" }, cause)
            ctx.channel().close()
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            target.close()

            ctx.sdaemon.logger.verbose { "${ctx.channel()} -> $target [tunnel ] Disconnected" }
        }
    }

    private class FstPrepareHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                if (msg.isReadable(2)) {
                    val daemon = ctx.sdaemon

                    when (msg.getByte(0).toInt() and 0xFF) {
                        5 -> {
                            daemon.logger.debug { "${ctx.channel()} [initial] Preparing as socks5" }

                            ctx.channel().pipeline()
                                .addLast("socks-5-init-request", Socks5InitialRequestDecoder())
                                .addLast("socks-5-server-encoder", Socks5ServerEncoder.DEFAULT)
                        }

                        4 -> {
                            daemon.logger.debug { "${ctx.channel()} [initial] Preparing as socks4" }

                            ctx.channel().pipeline()
                                .addLast("socks-4-server-decoder", Socks4ServerDecoder())
                                .addLast("socks-4-server-encoder", Socks4ServerEncoder.INSTANCE)
                        }

                        else -> {
                            daemon.logger.debug { "${ctx.channel()} [initial] Preparing as HTTPd" }

                            ctx.channel().pipeline()
                                .addLast("http-req-decoder", HttpRequestDecoder())
                                .addLast("http-rsp-encoder", HttpResponseEncoder())
                        }
                    }

                    ctx.fireChannelActive()

                    ctx.pipeline().remove(ctx.handler())
                    ctx.pipeline().addLast("2nd-pst-recv", SndPstRecv())

                    ctx.channel().pipeline().fireChannelRead(msg)
                    return
                }
            }
            super.channelRead(ctx, msg)
        }
    }

    private class SndPstRecv : ChannelInboundHandlerAdapter() {

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val daemon = ctx.sdaemon

            daemon.logger.debug { "${ctx.channel()} [2 init ] Processing first packet: ${msgFormat(msg)}" }

            if (msg is Socks5InitialRequest) {
                if (msg.decoderResult().isFailure) {
                    ctx.close()
                    return
                }
                if (msg.version() != SocksVersion.SOCKS5) {
                    ctx.close()
                    return
                }
                ctx.pipeline().replace(
                    Socks5InitialRequestDecoder::class.java,
                    "socks5-cmd-decoder",
                    Socks5CommandRequestDecoder()
                )
                daemon.logger.debug { "${ctx.channel()} [initial] switch to socks5 protocol" }

                // TODO: Auth
                ctx.pipeline().replace(this, "socks5-request-cmd-handler", Socks5CmdHandler())
                ctx.writeAndFlush(DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH))
                    .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)

                return
            }
            if (msg is Socks4CommandRequest) {
                daemon.logger.debug { "${ctx.channel()} [initial] switch to socks4 protocol" }

                if (msg.type() != Socks4CommandType.CONNECT) {
                    daemon.logger.debug { "${ctx.channel()} [socks 4] Disconnected because command isn't CONNECT" }

                    ctx.channel().close()
                    return
                }

                ctx.pipeline().remove(this)

                doConnect(ctx, msg.dstAddr(), msg.dstPort()).addListener { cRsp ->
                    cRsp as ChannelFuture

                    daemon.logger.debug { "${ctx.channel()} [socks 4] TCP Tunnel status: $cRsp" }


                    if (cRsp.isSuccess) {
                        ctx.writeAndFlush(DefaultSocks4CommandResponse(Socks4CommandStatus.SUCCESS))
                        ctx.pipeline().addFirst("forward", MsgForwardHandler(cRsp.channel()))
                    } else {
                        ctx.writeAndFlush(DefaultSocks4CommandResponse(Socks4CommandStatus.REJECTED_OR_FAILED))
                            .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
                        ctx.channel().close()
                    }
                }

                return
            }
            if (msg is HttpRequest) {
                daemon.logger.debug { "${ctx.channel()} [initial] switch to http protocol" }

                ctx.pipeline().replace(this, "http-handler", HttpConnectHandler())
                ctx.pipeline().fireChannelRead(msg)
                return
            }
            super.channelRead(ctx, msg)
        }
    }

    private class Socks5CmdHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is Socks5CommandRequest) {
                val daemon = ctx.sdaemon

                daemon.logger.debug { "${ctx.channel()} [socks 5] Processing command $msg" }

                if (msg.type() != Socks5CommandType.CONNECT) {
                    daemon.logger.debug { "${ctx.channel()} [socks 5] Disconnected because command isn't CONNECT" }

                    ctx.pipeline().writeAndFlush(
                        DefaultSocks5CommandResponse(
                            Socks5CommandStatus.FORBIDDEN, Socks5AddressType.DOMAIN
                        )
                    )
                    ctx.channel().close()
                    return
                }
                ctx.pipeline().remove(this)

                doConnect(ctx, msg.dstAddr(), msg.dstPort()).addListener { connectRsp ->
                    connectRsp as ChannelFuture


                    daemon.logger.debug { "${ctx.channel()} [socks 4] TCP Tunnel status: $connectRsp" }

                    if (connectRsp.isSuccess) {
                        ctx.pipeline().writeAndFlush(
                            DefaultSocks5CommandResponse(
                                Socks5CommandStatus.SUCCESS, when (msg.dstAddrType()) {
                                    Socks5AddressType.DOMAIN -> Socks5AddressType.IPv4
                                    else -> msg.dstAddrType()
                                }
                            )
                        )

                        ctx.pipeline().addFirst("forward", MsgForwardHandler(connectRsp.channel()))
                    } else {
                        ctx.pipeline().writeAndFlush(
                            DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, msg.dstAddrType())
                        ).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE)
                        ctx.channel().close()
                    }
                }

                return
            }

            super.channelRead(ctx, msg)
        }
    }

    private class HttpConnectHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            ctx.channel().attr(DAEMON).get().processHttpMsg(ctx, msg)
        }
    }


}
