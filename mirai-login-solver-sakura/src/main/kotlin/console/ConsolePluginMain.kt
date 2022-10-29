/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.console

import com.kasukusakura.mlss.resolver.SakuraLoginSolver
import com.kasukusakura.mlss.slovbroadcast.ResolveBroadcastServer
import kotlinx.coroutines.job
import net.mamoe.mirai.console.extension.PluginComponentStorage
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import java.util.concurrent.Executors

object ConsolePluginMain : KotlinPlugin(
    JvmPluginDescription(id = "com.kasukusakura.mlss", version = "0.0.1") {
        name("mirai-login-solver-sakura")
        version("0.0.1")
    }
) {
    override fun PluginComponentStorage.onLoad() {
        val executors = Executors.newScheduledThreadPool(5)
        val server = ResolveBroadcastServer(executors)
        server.start()

        ConsolePluginMain.coroutineContext.job.invokeOnCompletion {
            server.stop()
            executors.shutdown()
        }
        val solver = SakuraLoginSolver(server)

        contributeBotConfigurationAlterer { botid, botconf ->
            botconf.loginSolver = solver
            return@contributeBotConfigurationAlterer botconf
        }

    }

    override fun onEnable() {
    }
}
