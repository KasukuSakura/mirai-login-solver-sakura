/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss

import com.kasukusakura.mlss.resolver.SakuraLoginSolver
import com.kasukusakura.mlss.slovbroadcast.SakuraTransmitDaemon
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import net.mamoe.mirai.utils.LoginSolver
import net.mamoe.mirai.utils.MiraiLogger
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/**
 * 立即分配一个 debug 所使用的 [LoginSolver], 生命周期为直到 process 死亡
 *
 * 请不要直接执行此方法, 这会造成资源泄露
 */
@PublishedApi
internal object SakuraLoginSolverDebugAllocator {
    @JvmStatic
    @PublishedApi
    internal fun allocate(): LoginSolver {
        val server = SakuraTransmitDaemon(
            DaemonNettyNioEventLoopGroup(),
            NioServerSocketChannel::class.java,
            NioSocketChannel::class.java,
            SecureRandom().asKotlinRandom(),
            MiraiLogger.Factory.create(SakuraLoginSolverDebugAllocator::class.java, "SakuraLoginSolver"),
        )
        server.bootServer()

        return SakuraLoginSolver(server)
    }
}

