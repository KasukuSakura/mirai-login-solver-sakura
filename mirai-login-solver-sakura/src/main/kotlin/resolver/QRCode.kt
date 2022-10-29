/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package com.kasukusakura.mlss.resolver

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.QRCode
import java.awt.image.BufferedImage
import com.google.zxing.qrcode.encoder.Encoder as QRCodeEncoder

internal fun String.renderQRCode(width: Int = 500, height: Int = 500): BufferedImage {
    val bitMatrix = QRCodeWriter().encode(
        this,
        BarcodeFormat.QR_CODE,
        width,
        height
    )

    return MatrixToImageWriter.toBufferedImage(bitMatrix)
}


internal fun String.toQRCode(el: ErrorCorrectionLevel = ErrorCorrectionLevel.L): QRCode =
    QRCodeEncoder.encode(this, el, null)

internal fun QRCode.renderToBitMatrix(): BitMatrix {
    val input = matrix

    val rsp = BitMatrix(input.width, input.height)
    for (x in 0 until input.width) {
        for (y in 0 until input.height) {
            if (input.get(x, y).toInt() == 1) {
                rsp.set(x, y)
            }
        }
    }

    return rsp
}

@Suppress("NOTHING_TO_INLINE")
inline fun BitMatrix.render(): BufferedImage = MatrixToImageWriter.toBufferedImage(this)

private const val ANSI_RESET = "\u001b[0m"
internal fun BitMatrix.renderAsAnsi(
    builder: StringBuilder,
    onColor: String = "\u001b[40m",
    offColor: String = "\u001b[47m",
) {
    builder.append('\n')
    repeat(2) {
        builder.append(offColor)
        repeat(width + 4) {
            builder.append(' ').append(' ')
        }
        builder.append(ANSI_RESET).append('\n')
    }
    for (y in 0 until height) {

        var current = false
        builder.append(offColor)
        builder.append("    ")

        for (x in 0 until width) {
            if (current != this[x, y].also { current = it }) {
                builder.append(if (current) onColor else offColor)
            }
            builder.append(' ').append(' ')
        }
        if (current) {
            builder.append(offColor)
        }
        builder.append("    ")
        builder.append(ANSI_RESET).append('\n')
    }

    repeat(2) {
        builder.append(offColor)
        repeat(width + 4) {
            builder.append(' ').append(' ')
        }
        builder.append(ANSI_RESET).append('\n')
    }

    builder.append("\n\n")
}