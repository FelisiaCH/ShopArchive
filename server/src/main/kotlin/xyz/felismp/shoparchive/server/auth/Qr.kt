package xyz.felismp.shoparchive.server.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Light margin around the code, in modules. The spec asks for 4; a bright block on a dark terminal is read well with 2 and saves 4 rows. */
private const val QUIET_MODULES = 2

private fun matrixOf(text: String): BitMatrix =
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        // Level L: the code is shown on a screen, so it needs no repair, and a smaller code is easier to scan from a terminal.
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 0),
    )

private fun BitMatrix.dark(x: Int, y: Int) = x in 0 until width && y in 0 until height && get(x, y)

/**
 * [text] as a QR code made of half blocks, two modules to a text row. Drawn for a dark terminal: the light modules
 * and the margin are the bright blocks, the dark modules are the terminal's own background.
 */
internal fun qrAsText(text: String): List<String> {
    val matrix = matrixOf(text)
    val from = -QUIET_MODULES
    val to = matrix.width + QUIET_MODULES
    return (from until to step 2).map { y ->
        (from until to).joinToString("") { x ->
            val top = matrix.dark(x, y)
            val bottom = matrix.dark(x, y + 1)
            when {
                !top && !bottom -> "█"
                !top && bottom -> "▀"
                top && !bottom -> "▄"
                else -> " "
            }
        }
    }
}

/** [text] as a PNG: black modules on white, 8 pixels to a module, with the standard 4-module margin. */
internal fun qrAsPng(text: String): ByteArray {
    val matrix = matrixOf(text)
    val scale = 8
    val margin = 4
    val size = (matrix.width + 2 * margin) * scale
    val image = BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val dark = matrix.dark(x / scale - margin, y / scale - margin)
            image.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
        }
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
