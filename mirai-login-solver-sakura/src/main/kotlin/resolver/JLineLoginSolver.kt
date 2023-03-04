/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.resolver

import com.kasukusakura.mlss.slovbroadcast.SakuraTransmitDaemon
import kotlinx.coroutines.runInterruptible
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.LineReaderImpl
import org.jline.reader.impl.completer.StringsCompleter

class JLineLoginSolver(
    daemon: SakuraTransmitDaemon,
    private val lineReader: LineReader,
) : CommonTerminalLoginSolver(daemon) {
    override fun printMsg(msg: String) {
        lineReader.printAbove(msg)
    }

    override suspend fun requestInput(hint: String): String? {
        return runInterruptible {
            try {
                return@runInterruptible lineReader.readLine(hint)
            } catch (_: UserInterruptException) {
                return@runInterruptible null
            }
        }
    }

    override val isCtrlCSupported: Boolean get() = true
    override fun acquireLineReaderCompleter(): Any? {
        return (lineReader as? LineReaderImpl?)?.completer
    }

    override fun setLineReaderCompleter(completer: Any?) {
        val impl = lineReader as? LineReaderImpl ?: return
        completer as Completer?
        impl.completer = completer
    }

    override fun setLineReaderCompleting(words: Iterable<String>) {
        setLineReaderCompleter(StringsCompleter(words))
    }
}