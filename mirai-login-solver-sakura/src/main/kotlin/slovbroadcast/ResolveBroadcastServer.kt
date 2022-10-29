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
import com.google.gson.JsonPrimitive
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.resume


class ResolveBroadcastServer(
    private val executor: ScheduledExecutorService,
    private val serverBindPort: Int = 0,
) {

    inner class TMXRequest(
        val message: JsonElement,
    ) {
        internal lateinit var continuation: CancellableContinuation<ByteArray>
        internal lateinit var requestId: String
        internal lateinit var messageX: ByteArray

        fun cancel() {
            if (requests.remove(requestId, this)) {
                if (this::continuation.isInitialized) {
                    continuation.cancel()
                }
            }
        }

        suspend fun awaitResponse(): ByteArray = suspendCancellableCoroutine { cont ->
            continuation = cont
            cont.invokeOnCancellation { requests.remove(requestId, this@TMXRequest) }
        }

        fun complete(msg: ByteArray) {
            requests.remove(requestId, this)


            if (this::continuation.isInitialized) {
                continuation.resume(msg)
            }
        }

        fun renderQR(): BitMatrix {
            val buf = StringWriter()
            JsonWriter(buf).use { jw ->
                jw.beginObject()

                jw.name("port").value(delegateServer.address.port)
                jw.name("server").beginArray()

                NetworkInterface.getNetworkInterfaces().asSequence().filterNot { inet ->
                    inet.isLoopback || inet.isVirtual || !inet.supportsMulticast() || !inet.isUp
                }.flatMap { it.inetAddresses.asSequence() }.filter {
                    it.isSiteLocalAddress && it is Inet4Address
                }.map { iaddr ->
                    iaddr.address.joinToString(".") { java.lang.Byte.toUnsignedInt(it).toString() }
                }.forEach { jw.value(it) }
                jw.endArray()

                jw.name("id").value(requestId)

                jw.endObject()
            }

            return QRCodeWriter().encode(
                buf.toString(), BarcodeFormat.QR_CODE, 400, 400, mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H
                )
            )
        }

        val requestPt: String get() = "/request/request/$requestId"
    }

    fun newRequest(msg: JsonElement, initialReqId: String? = null): TMXRequest {
        val req = TMXRequest(msg)

        var id: String = initialReqId ?: UUID.randomUUID().toString()
        do {
            if (requests.putIfAbsent(id, req) == null) {
                break
            }
            id = UUID.randomUUID().toString()
        } while (true)

        req.requestId = id
        req.messageX = ByteArrayOutputStream(1024).also { baos ->
            JsonWriter(OutputStreamWriter(baos)).use { jo ->
                jo.beginObject()
                    .name("reqid").value(id)
                    .name("rspuri").value("/request/complete/$id")
                    .name("create-time").value(System.currentTimeMillis())
                    .name("data")

                Streams.write(msg, jo)

                jo.endObject()
            }
        }.toByteArray()

        return req
    }


    internal val delegateServer = HttpServer.create()
    internal val requests = ConcurrentHashMap<String, TMXRequest>()


    fun start() {
        delegateServer.executor = executor

        delegateServer.createContext("/etc/hostname") { exchange ->
            exchange.sendResponseHeaders(200, 0)

            exchange.responseBody.use { rsp ->
                rsp.write("ML Sakura Login Solver - KasukuSakura Tech".toByteArray(StandardCharsets.ISO_8859_1))
            }
        }

        delegateServer.createContext("/request") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/request").removePrefix("/")
            if (path.isBlank()) {
                exchange.sendResponseHeaders(404, -1)
                return@createContext
            }
            val (requestMethod, requestParam) = run {
                val spl = path.indexOf('/')
                if (spl == -1) {
                    exchange.sendResponseHeaders(404, -1)
                    return@createContext
                }
                path.substring(0, spl) to path.substring(spl + 1)
            }


            when (requestMethod) {
                "request" -> {
                    val req0 = requests[requestParam]
                    if (req0 == null) {
                        exchange.sendResponseHeaders(404, -1)
                        return@createContext
                    }

                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.responseHeaders.add("Content-Encoding", "UTF-8")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { it.write(req0.messageX) }
                }

                "complete" -> {
                    if (exchange.requestMethod != "POST") {
                        exchange.sendResponseHeaders(403, -1)
                        return@createContext
                    }

                    val req0 = requests[requestParam]
                    if (req0 == null) {
                        exchange.sendResponseHeaders(404, -1)
                        return@createContext
                    }

                    req0.complete(exchange.requestBody.use { it.readAllBytes() })

                    exchange.sendResponseHeaders(200, -1)
                    return@createContext

                }

                else -> {
                    exchange.sendResponseHeaders(404, 0)
                    exchange.responseBody.write(path.toByteArray())
                    exchange.responseBody.close()
                }
            }

        }

        delegateServer.bind(InetSocketAddress("0.0.0.0", serverBindPort), 40)
        delegateServer.start()
    }

    fun stop() {
        delegateServer.stop(1)
    }
}

fun main() {
    val server = ResolveBroadcastServer(
        Executors.newScheduledThreadPool(5)
    )

    server.start()

    println(server.delegateServer.address)

    val req = server.newRequest(JsonPrimitive("114514"), "114514")
    println(req)
    println(req.requestId)
    println(req.requestPt)




    LockSupport.park()
}
