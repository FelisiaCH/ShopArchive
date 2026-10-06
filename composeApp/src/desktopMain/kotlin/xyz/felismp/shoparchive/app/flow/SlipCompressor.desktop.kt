package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

actual fun createSlipCompressor(): SlipCompressor = SlipCompressor { raw -> withContext(Dispatchers.Default) { compressWithImageIo(raw) } }

internal fun compressWithImageIo(raw: ByteArray): ByteArray? {
    val source = ImageIO.read(ByteArrayInputStream(raw)) ?: return null
    val scale = minOf(1.0, SLIP_MAX_SIDE.toDouble() / maxOf(source.width, source.height))
    val width = maxOf(1, (source.width * scale).toInt())
    val height = maxOf(1, (source.height * scale).toInt())
    // JPEG has no transparency: draw on white.
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        color = java.awt.Color.WHITE
        fillRect(0, 0, width, height)
        drawImage(source, 0, 0, width, height, null)
        dispose()
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val params = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = SLIP_QUALITY / 100f
    }
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(image, null, null), params)
    }
    writer.dispose()
    return out.toByteArray()
}
