package xyz.felismp.shoparchive.app.flow

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlipCompressorTest {
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }

    @Test fun aBigPictureBecomesAJpegWithItsLongestSideAtMost1600() {
        val jpeg = compressWithImageIo(png(4000, 3000))!!
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val image = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(1600, image.width)
        assertEquals(1200, image.height)
    }

    @Test fun aSmallPictureIsNotEnlarged() {
        val image = ImageIO.read(ByteArrayInputStream(compressWithImageIo(png(300, 200))!!))
        assertEquals(300, image.width)
        assertTrue(image.height == 200)
    }

    @Test fun somethingThatIsNotAPictureIsRefused() {
        assertNull(compressWithImageIo(byteArrayOf(1, 2, 3)))
    }
}
