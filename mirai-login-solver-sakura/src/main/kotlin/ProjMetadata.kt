/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss

import java.util.*

internal object ProjMetadata {
    private val prop: Properties by lazy {
        Properties().also { rsp ->
            ProjMetadata::class.java.getResourceAsStream("metadata.properties")?.bufferedReader()?.use { rsp.load(it) }
        }
    }

    operator fun get(key: String): String = prop.getProperty(key) ?: error("`$key` not found in metadata")
}
