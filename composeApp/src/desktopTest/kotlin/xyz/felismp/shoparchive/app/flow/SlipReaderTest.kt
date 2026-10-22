package xyz.felismp.shoparchive.app.flow

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SlipReaderTest {
    private fun jpeg(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun qr(text: String): BufferedImage {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 300) for (y in 0 until 300) image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
        return image
    }

    @Test fun theQrOfASlipBecomesItsCode() {
        val scan = runBlocking { createSlipReader().read(jpeg(qr("  0041000123456789ABC  "))) }
        assertEquals("0041000123456789ABC", assertNotNull(scan).code)
    }

    @Test fun aPictureWithoutAQrHasNoCode() {
        val blank = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 300, 300) } }
        val scan = runBlocking { createSlipReader().read(jpeg(blank)) }
        assertNull(assertNotNull(scan).code)
    }
}
