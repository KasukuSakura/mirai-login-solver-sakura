/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.slovbroadcast

@Suppress("MemberVisibilityCanBePrivate")
internal object DefaultSettings {
    fun sysProp(key: String, def: Int): Int {
        return System.getProperty(key)?.toIntOrNull() ?: def
    }

    fun sysProp(key: String, def: Boolean): Boolean {
        val prop = System.getProperty(key) ?: return def
        if (prop.isEmpty()) return true
        if (prop == "yes") return true
        return prop.toBoolean()
    }

    fun sysProp(key: String, def: String): String {
        return System.getProperty(key, def)
    }

    fun sysProp(key: String, def: Long): Long {
        return System.getProperty(key)?.toLongOrNull() ?: def
    }

    internal val noTunnel: Boolean by lazy { sysProp("mlss.no-tunnel", false) }
    internal val serverPort: Int by lazy { sysProp("mlss.port", 0) }
    internal val tunnelLimited: Boolean by lazy { sysProp("mlss.tunnel.limited", true) }
}