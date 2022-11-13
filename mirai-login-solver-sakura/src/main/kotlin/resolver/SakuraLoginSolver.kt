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
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.kasukusakura.mlss.ProjMetadata
import com.kasukusakura.mlss.slovbroadcast.SakuraTransmitDaemon
import com.kasukusakura.mlss.useByteBuf
import kotlinx.coroutines.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.network.CustomLoginFailedException
import net.mamoe.mirai.utils.*
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel

@Suppress("MemberVisibilityCanBePrivate")
class SakuraLoginSolver(
    private val daemon: SakuraTransmitDaemon,
) : LoginSolver() {
    override val isSliderCaptchaSupported: Boolean get() = true

    override suspend fun onSolvePicCaptcha(bot: Bot, data: ByteArray): String? {
        val img = runInterruptible(Dispatchers.IO) {
            val usingCache = ImageIO.getUseCache()
            try {
                ImageIO.setUseCache(false)
                ImageIO.read(ByteArrayInputStream(data))
            } finally {
                if (usingCache) ImageIO.setUseCache(true)
            }
        }
        return onSolvePicCaptcha(bot.id, img)
    }

    override suspend fun onSolveSliderCaptcha(bot: Bot, url: String): String? {
        return onSolveSliderCaptcha(bot.id, url, bot.logger)
    }

    @Deprecated(
        "Please use onSolveDeviceVerification instead",
        replaceWith = ReplaceWith("onSolveDeviceVerification(bot, url, null)"),
        level = DeprecationLevel.WARNING
    )
    override suspend fun onSolveUnsafeDeviceLoginVerify(bot: Bot, url: String): String? {
        error("Sorry, but mirai-login-solver-sakura no longer support the legacy version of mirai-core; Please use 2.13.0 or above")
    }

    override suspend fun onSolveDeviceVerification(
        bot: Bot,
        requests: DeviceVerificationRequests
    ): DeviceVerificationResult {
        return onDeviceVerification(bot.id, requests)
    }

    internal suspend fun onSolvePicCaptcha(botid: Long, img: BufferedImage): String? {
        return openWindowCommon(JFrameWithIco(), isTopLevel = true, title = "PicCaptcha($botid)") {
            appendFillX(JLabel(ImageIcon(img)))
            optionPane.options = arrayOf(
                BTN_OK.attachToTextField(filledTextField("", "")).asInitialValue(),
                BTN_CANCEL.withValue(WindowResult.Cancelled),
            )
        }.valueAsString
    }

    internal suspend fun onSolveSliderCaptcha(botid: Long, captchaUrl: String, logger: MiraiLogger): String? {
        val rspx = openWindowCommon(JFrameWithIco(), isTopLevel = true, title = "SliderCaptcha($botid)") {
            filledTextField("url", captchaUrl)
            optionPane.options = arrayOf(
                JButton("Use TxCaptchaHelper").withActionBlocking {
                    val respx = openWindowCommon(
                        window = parentWindow,
                        isTopLevel = false,
                        blockingDisplay = true,
                        title = "SliderCaptcha($botid) - TxCaptchaHelper",
                    ) {
                        val statusBar = filledTextField("", "")
                        optionPane.options = arrayOf(
                            JButton("Open Website").withAction {
                                openBrowserOrAlert("https://github.com/mzdluo123/TxCaptchaHelper")
                            },
                            BTN_CANCEL.withValue(WindowResult.Cancelled),
                        )

                        val helper = object : MZDTxCaptchaHelper() {
                            override fun onComplete(ticket: String) {
                                response.complete(WindowResult.Confirmed(ticket))
                            }

                            override fun updateDisplay(msg: String) {
                                statusBar.text = msg
                            }
                        }
                        helper.start(subCoroutineScope, captchaUrl)
                    }
                    if (respx.cancelled) return@withActionBlocking

                    response.complete(respx)
                },
                JButton("Use SakuraCaptchaHelper").withActionBlocking {
                    val rspx = openWindowCommon(
                        window = parentWindow,
                        isTopLevel = false,
                        blockingDisplay = true,
                        title = "SliderCaptcha($botid) - SakuraCaptchaHelper"
                    ) {
                        appendFillX(JLabel("请使用 Sakura Login Solver (配套app) 扫描此二维码"))
                        appendFillX(JLabel("注: 手机与此设备应该在同一内网中 (即连接同一个网络)"))
                        val req24 = daemon.newRequest(JsonObject().also { jo ->
                            jo.addProperty("type", "slider")
                            jo.addProperty("url", captchaUrl)
                        })

                        val qrx = req24.renderQR()
                        val jl = JLabel()
                        jl.icon = ImageIcon(
                            MatrixToImageWriter.toBufferedImage(
                                qrx, MatrixToImageConfig(
                                    Color.black.rgb,
                                    Color.white.rgb,
                                )
                            )
                        )
                        appendFillX(jl)
                        appendFillX(
                            JLabel("Version " + ProjMetadata["proj.projver"] + " " + ProjMetadata["proj.commitid"]).also { verinf ->
                                verinf.foreground = Color.GRAY
                            }
                        )
                        optionPane.options = arrayOf(
                            BTN_CANCEL.withValue(WindowResult.Cancelled),
                        )

                        subCoroutineScope.launch {
                            val rspxwfx = req24.awaitResponse().useByteBuf { it.toString(StandardCharsets.UTF_8) }
                            response.complete(WindowResult.Confirmed(rspxwfx))
                        }
                    }

                    if (!rspx.cancelled) {
                        response.complete(rspx)
                    }
                },
                BTN_OK.attachToTextField(filledTextField("ticket", "")).asInitialValue(),
                BTN_CANCEL.withValue(WindowResult.Cancelled),
            )
        }
        if (rspx.cancelled) {
            throw UnsafeDeviceLoginVerifyCancelledException(true, "Cancelled")
        }
        return rspx.valueAsString
    }

    internal suspend fun onDeviceVerification(
        botid: Long,
        requests: DeviceVerificationRequests
    ): DeviceVerificationResult {
        val rsp = openWindowCommon(JFrameWithIco(), isTopLevel = true, title = "Device Verify($botid)") {
            val options = mutableListOf<JButton>()

            appendFillX(JLabel("此账户需要安全认证, 请选择以下一种验证方式"))

            requests.sms?.let { smsreq ->
                options.add(JButton("短信验证").withActionBlocking {
                    val rsp = onSolveUnsafeDeviceSMSVerify(botid, smsreq, this@openWindowCommon)
                    if (!rsp.cancelled) {
                        response.complete(rsp)
                    }
                })
            }
            requests.fallback?.let { fallback ->
                options.add(JButton("设备锁验证").withActionBlocking {
                    val rsp = onSolveUnsafeDeviceFallbackLoginVerify(botid, fallback, this@openWindowCommon)
                    if (!rsp.cancelled) {
                        response.complete(rsp)
                    }
                })
            }

            if (options.isEmpty()) {
                options.add(JButton("非常抱歉, 没有任何可用选项"))
            }

            options.add(BTN_CANCEL.withValue(WindowResult.Cancelled))

            optionPane.options = options.toTypedArray()
        }

        if (rsp.cancelled) {
            throw UnsafeDeviceLoginVerifyCancelledException(true, "Cancelled")
        }
        if (rsp is WindowResult.ConfirmedAnything) {
            return rsp.value as DeviceVerificationResult
        }
        throw UnsafeDeviceLoginVerifyCancelledException(false, "Unknown result $rsp")
    }

    internal suspend fun onSolveUnsafeDeviceSMSVerify(
        botid: Long,
        req: DeviceVerificationRequests.SmsRequest,
        parent: WindowsOptions
    ): WindowResult {
        val rspx = openWindowCommon(
            parent.parentWindow, isTopLevel = false,
            blockingDisplay = true,
            title = "SMS Verify($botid)",
        ) {
            appendFillX(JLabel("An SMS verification required for this login"))
            appendFillX(JLabel("${req.countryCode} ${req.phoneNumber}"))

            val codeid = filledTextField("code", "")

            val btnResend = JButton("发送短信")
            val btnCooldown = AtomicLong()

            subCoroutineScope.launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (btnCooldown.get() != 0L) {
                        val mitx = now - btnCooldown.get()
                        if (mitx > 60000) {
                            btnResend.text = "重发短信"
                            btnResend.isEnabled = true
                            btnCooldown.set(0L)
                            btnResend.putClientProperty("JComponent.outline", null)
                        } else {
                            btnResend.text = "重发短信 (" + ((60000L - mitx) / 1000L) + "s)"
                        }
                    }
                    delay(500L)
                }
            }

            var lastWhenX = 0L
            btnResend.addMouseListener(object : MouseAdapter() {

                override fun mouseClicked(e: MouseEvent) {
                    if (e.`when` - lastWhenX < 100L) return
                    lastWhenX = e.`when`

                    if (btnResend.isEnabled) return

                    btnResend.putClientProperty("JComponent.outline", "error")
                    btnResend.isEnabled = true
                }
            })

            optionPane.options = arrayOf(
                btnResend.withAction { evt ->
                    lastWhenX = evt.`when`

                    btnResend.isEnabled = false
                    btnCooldown.set(System.currentTimeMillis())

                    subCoroutineScope.launch { req.requestSms() }
                },
                BTN_OK.attachToTextField(codeid).asInitialValue(),
                BTN_CANCEL.withValue(WindowResult.Cancelled),
            )
        }
        if (rspx is WindowResult.Confirmed) {
            return WindowResult.ConfirmedAnything(req.solved(rspx.valueAsString))
        }
        return rspx
    }

    internal suspend fun onSolveUnsafeDeviceFallbackLoginVerify(
        botid: Long, req: DeviceVerificationRequests.FallbackRequest,
        parent: WindowsOptions
    ): WindowResult {
        return openWindowCommon(
            parent.parentWindow, isTopLevel = false,
            blockingDisplay = true,
            title = "UnsafeDeviceVerify($botid)"
        ) {
            appendFillX(
                JLabel(
                    """
                <html>
                需要进行账户安全认证<br>
                该账户有设备锁/不常用登录地点/不常用设备登录的问题<br>
                请在<span style="color: red">手机QQ</span>打开下面链接
            """.trimIndent()
                )
            )
            filledTextField("url", req.url)
            appendFillX(JLabel(ImageIcon(req.url.renderQRCode())))
            optionPane.options = arrayOf(
                JButton("已完成").withValue { WindowResult.ConfirmedAnything(req.solved()) }
            )
        }
    }

    private fun JFrameWithIco(): JFrame = JFrame().also { jfr ->
        jfr.iconImage = iconx
        jfr.rootPane.putClientProperty("JRootPane.titleBarShowIcon", false)
    }
}


private val iconx: BufferedImage? by lazy {
    ExternalResource::class.java.getResourceAsStream("project-mirai.png")?.use { resx ->
        val imgiocache = ImageIO.getUseCache()
        if (imgiocache) ImageIO.setUseCache(false)
        try {
            ImageIO.read(resx)
        } finally {
            if (imgiocache) ImageIO.setUseCache(true)
        }
    }
}

internal class UnsafeDeviceLoginVerifyCancelledException : CustomLoginFailedException {
    public constructor(killBot: Boolean) : super(killBot)
    public constructor(killBot: Boolean, message: String?) : super(killBot, message)
    public constructor(killBot: Boolean, message: String?, cause: Throwable?) : super(killBot, message, cause)
    public constructor(killBot: Boolean, cause: Throwable?) : super(killBot, cause = cause)
}
