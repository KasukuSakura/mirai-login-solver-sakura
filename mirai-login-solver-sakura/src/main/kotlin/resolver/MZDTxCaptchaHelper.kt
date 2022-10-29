/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.resolver

import com.kasukusakura.mlss.SharedObjects
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse


internal abstract class MZDTxCaptchaHelper {
    private val client get() = SharedObjects.httpclient

    private lateinit var queue: Job

    private var latestDisplay = "Sending request..."

    abstract fun onComplete(ticket: String)
    abstract fun updateDisplay(msg: String)

    fun start(scope: CoroutineScope, url: String) {
        val url0 = url.replace("ssl.captcha.qq.com", "txhelper.glitch.me")
        val queue = scope.launch(Dispatchers.IO) {
            updateDisplay(latestDisplay)
            while (isActive) {
                try {
                    val response = client.sendAsync(
                        HttpRequest.newBuilder()
                            .GET()
                            .uri(URI.create(url0))
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    ).await()

                    if (response.statusCode() != 200) {
                        updateDisplay("HTTP Response ${response.statusCode()}")
                        continue
                    }

                    val rspText = response.body()
                    if (rspText.startsWith("请在")) {
                        if (rspText != latestDisplay) {
                            latestDisplay = rspText
                            updateDisplay(rspText)
                        }
                    } else {
                        onComplete(rspText)
                        return@launch
                    }
                } catch (e: Throwable) {
                    updateDisplay(e.toString().also { latestDisplay = it })
                }
                delay(1000)
            }
        }
        this.queue = queue
    }

    fun dispose() {
        queue.cancel()
    }
}
