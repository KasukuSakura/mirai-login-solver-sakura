/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.console

import com.kasukusakura.mlss.DaemonNettyNioEventLoopGroup
import com.kasukusakura.mlss.ProjMetadata
import com.kasukusakura.mlss.resolver.CommonTerminalLoginSolver
import com.kasukusakura.mlss.resolver.JLineLoginSolver
import com.kasukusakura.mlss.resolver.SakuraLoginSolver
import com.kasukusakura.mlss.slovbroadcast.SakuraTransmitDaemon
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.job
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.extension.PluginComponentStorage
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.util.requestInput
import net.mamoe.mirai.utils.debug
import org.jline.reader.LineReader
import java.awt.GraphicsEnvironment
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import java.awt.Toolkit as AwtToolkit

object ConsolePluginMain : KotlinPlugin(
    JvmPluginDescription(id = "com.kasukusakura.mlss", version = ProjMetadata["proj.projver"]) {
        name("mirai-login-solver-sakura")
        version(ProjMetadata["proj.projver"])
    }
) {
    override fun PluginComponentStorage.onLoad() {
        logger.debug { "Version : " + ProjMetadata["proj.projver"] }
        logger.debug { "Commit  : " + ProjMetadata["proj.commitid"] }

        val server = SakuraTransmitDaemon(
            DaemonNettyNioEventLoopGroup(),
            NioServerSocketChannel::class.java,
            NioSocketChannel::class.java,
            SecureRandom().asKotlinRandom(),
            logger,
        )
        server.bootServer()

        ConsolePluginMain.coroutineContext.job.invokeOnCompletion {
            server.shutdown()
            server.eventLoopGroup.shutdownGracefully()
        }

        val noDesktop = if (System.getProperty("mirai.no-desktop") != null) {
            true
        } else kotlin.runCatching {
            if (GraphicsEnvironment.isHeadless()) return@runCatching true
            AwtToolkit.getDefaultToolkit()
            return@runCatching false
        }.onFailure { logger.warning(it) }.getOrElse { true }

        val solver = if (noDesktop) run {
            try {
                val lineReader = Class.forName("net.mamoe.mirai.console.terminal.MiraiConsoleImplementationTerminalKt")
                    .getMethod("getLineReader")
                    .invoke(null)
                return@run JLineLoginSolver(
                    daemon = server,
                    lineReader = lineReader as LineReader,
                )
            } catch (_: Throwable) {
            }

            object : CommonTerminalLoginSolver(server) {
                override fun printMsg(msg: String) {
                    logger.info(msg)
                }

                override suspend fun requestInput(hint: String): String = MiraiConsole.requestInput(hint)

                override val isCtrlCSupported: Boolean get() = false
            }
        } else {
            SakuraLoginSolver(server)
        }

        contributeBotConfigurationAlterer { botid, botconf ->
            botconf.loginSolver = solver
            return@contributeBotConfigurationAlterer botconf
        }

    }

    override fun onEnable() {
    }
}
