/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss

import io.netty.buffer.ByteBuf
import io.netty.channel.nio.NioEventLoopGroup
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
internal inline fun <R> ByteBuf.useByteBuf(act: (ByteBuf) -> R): R {
    contract { callsInPlace(act, InvocationKind.EXACTLY_ONCE) }
    retain()
    try {
        return act(this)
    } finally {
        release(2)
    }
}

@Suppress("FunctionName")
internal fun DaemonNettyNioEventLoopGroup(): NioEventLoopGroup {
    val threadGroup = ThreadGroup("SakuraLoginSolverDaemon")
    val counter = AtomicInteger(0)
    val threadFactory = ThreadFactory { task ->
        Thread(threadGroup, task, "SakuraLoginSolverDaemon #${counter.getAndIncrement()}").also { thread ->
            thread.isDaemon = true
        }
    }
    return NioEventLoopGroup(0, threadFactory)
}
