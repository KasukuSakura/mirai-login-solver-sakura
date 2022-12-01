/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.resolver

import com.google.gson.JsonObject
import com.kasukusakura.mlss.slovbroadcast.SakuraTransmitDaemon
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultHttpHeaders
import net.mamoe.mirai.Bot
import net.mamoe.mirai.utils.DeviceVerificationRequests
import net.mamoe.mirai.utils.DeviceVerificationResult
import net.mamoe.mirai.utils.LoginSolver
import javax.imageio.ImageIO

abstract class CommonTerminalLoginSolver(
    private val daemon: SakuraTransmitDaemon,
) : LoginSolver() {

    protected abstract fun printMsg(msg: String)
    protected open val isCtrlCSupported: Boolean get() = false
    protected abstract suspend fun requstInput(hint: String): String?

    override val isSliderCaptchaSupported: Boolean get() = true

    override suspend fun onSolvePicCaptcha(bot: Bot, data: ByteArray): String? {
        val req = daemon.newRawRequest(
            additionalHeaders = DefaultHttpHeaders()
                .add("Content-Type", "image/png")
        ) { Unpooled.wrappedBuffer(data) }

        printMsg("需要图像验证码")
        printMsg("请使用 任意浏览器 打开 http://<ip>:${daemon.serverPort}/request/request/${req.requestId} 来查看图片")

        try {
            val rsp = requstInput("PicCaptcha > ") ?: throw UnsafeDeviceLoginVerifyCancelledException(true)

            return rsp.takeIf { it.isNotBlank() }
        } finally {
            req.fireCompleted()
        }
    }

    override suspend fun onSolveSliderCaptcha(bot: Bot, url: String): String? {
        val req = daemon.newRequest(JsonObject().also { jo ->
            jo.addProperty("type", "slider")
            jo.addProperty("url", url)
        })

        printMsg("请使用 SakuraLoginSolver 打开 http://<ip>:${daemon.serverPort}/request/request/${req.requestId} 来完成验证")

        val rsp = req.awaitResponse()
        val rspText = rsp.toString(Charsets.UTF_8)
        rsp.release()
        return rspText
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override suspend fun onSolveUnsafeDeviceLoginVerify(bot: Bot, url: String): String? {
        error("Sorry, but mirai-login-solver-sakura no longer support the legacy version of mirai-core; Please use 2.13.0 or above")
    }

    protected open fun acquireLineReaderCompleter(): Any? = null
    protected open fun setLineReaderCompleting(words: Iterable<String>) {}
    protected open fun setLineReaderCompleter(completer: Any?) {}

    override suspend fun onSolveDeviceVerification(
        bot: Bot,
        requests: DeviceVerificationRequests
    ): DeviceVerificationResult {
        val originTabCompleter = acquireLineReaderCompleter()

        val resolveMethods = mutableMapOf<String, suspend () -> DeviceVerificationResult?>()

        requests.fallback?.let { fallback ->
            resolveMethods["legacy"] = process@{

                printMsg("需要设备锁验证")
                printMsg("请在 「手机QQ」!!! 打开此链接")
                printMsg(fallback.url)

                val req = kotlin.runCatching {
                    ImageIO.setUseCache(false)
                    val img = fallback.url.renderQRCode()
                    daemon.newRawRequest(
                        additionalHeaders = DefaultHttpHeaders()
                            .add("Content-Type", "image/png")
                    ) { allocator ->
                        val buf = allocator.buffer()
                        ByteBufOutputStream(buf).use { ImageIO.write(img, "png", it) }
                        return@newRawRequest buf
                    }
                }.getOrNull()
                if (req != null) {
                    printMsg("")
                    printMsg("或使用 任意浏览器 打开 http://<ip>:${daemon.serverPort}/request/request/${req.requestId}")
                    printMsg("并使用 「手机QQ」扫描此二维码")
                }

                try {
                    requstInput(
                        if (isCtrlCSupported) "按任意键继续, 按 「Ctrl+C」 取消"
                        else "按任意键继续"
                    ) ?: return@process null

                    return@process fallback.solved()
                } finally {
                    req?.fireCompleted()
                }
            }
        }
        requests.sms?.let { sms ->
            resolveMethods["sms"] = process@{

                printMsg("需要短信验证 -> (+${sms.countryCode}) ${sms.phoneNumber}")
                printMsg("直接按下 「Enter」 发送短信, 输入验证码完成验证")
                if (isCtrlCSupported) {
                    printMsg("按下「Ctrl+C」取消")
                }

                while (true) {
                    val value = requstInput("> ") ?: return@process null
                    if (value.isBlank()) {
                        sms.requestSms()
                        printMsg("短信已发出. 请自行留意短信重发时间")
                        printMsg("高频请求验证码可能导致账户被停用")
                        continue
                    }
                    return@process sms.solved(value)
                }

                @Suppress("UNREACHABLE_CODE")
                return@process null
            }
        }

        try {
            if (resolveMethods.isEmpty()) {
                printMsg("Error: No resolve methods available")
                throw UnsafeDeviceLoginVerifyCancelledException(true, "No resolve methods available")
            }

            while (true) {
                setLineReaderCompleting(resolveMethods.keys)

                printMsg("可用方法: ${resolveMethods.keys.joinToString(", ")}")
                if (isCtrlCSupported) {
                    printMsg("输入 「cancel」 取消 / 按下 「Ctrl+C」 取消")
                } else {
                    printMsg("输入 「cancel」 取消")
                }

                val optionSelected = requstInput("> ")
                if (optionSelected.isNullOrBlank() || optionSelected == "cancel") {
                    throw UnsafeDeviceLoginVerifyCancelledException(true, "Cancelled")
                }
                val met = resolveMethods[optionSelected]
                if (met == null) {
                    printMsg("未知方法: $optionSelected")
                    continue
                }
                setLineReaderCompleting(emptyList())
                met.invoke()?.let { return it }
            }

        } finally {
            setLineReaderCompleter(originTabCompleter)
        }
    }
}