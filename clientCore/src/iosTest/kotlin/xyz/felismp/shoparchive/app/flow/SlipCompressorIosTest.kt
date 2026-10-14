@file:OptIn(ExperimentalForeignApi::class)

package xyz.felismp.shoparchive.app.flow

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageOrientation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIRectFill
import xyz.felismp.shoparchive.app.client.toByteArray
import xyz.felismp.shoparchive.app.client.toNSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlipCompressorIosTest {
    private fun picture(width: Int, height: Int, opaque: Boolean = true): UIImage {
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
            scale = 1.0
            this.opaque = opaque
        }
        return UIGraphicsImageRenderer(size = CGSizeMake(width.toDouble(), height.toDouble()), format = format).imageWithActions {
            UIColor.redColor.setFill()
            UIRectFill(CGRectMake(0.0, 0.0, width.toDouble() / 2, height.toDouble()))
        }
    }

    private fun jpeg(image: UIImage) = assertNotNull(UIImageJPEGRepresentation(image, 0.9)).toByteArray()

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> =
        assertNotNull(UIImage.imageWithData(bytes.toNSData())).size.useContents { width.toInt() to height.toInt() }

    private fun assertJpeg(bytes: ByteArray) {
        assertTrue(bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(), "not a JPEG")
    }

    @Test fun aLargePhotoGetsItsLongSideDownToTheLimit() {
        val out = assertNotNull(compressWithUIKit(jpeg(picture(3000, 2000))))
        assertJpeg(out)
        val (w, h) = sizeOf(out)
        assertEquals(SLIP_MAX_SIDE, w)
        assertTrue(h in 1066..1067, "height $h")
    }

    @Test fun aTallPhotoIsScaledByItsHeight() {
        val (w, h) = sizeOf(assertNotNull(compressWithUIKit(jpeg(picture(2000, 3200)))))
        assertEquals(SLIP_MAX_SIDE, h)
        assertEquals(1000, w)
    }

    @Test fun aSmallPhotoIsNotEnlarged() {
        assertEquals(800 to 600, sizeOf(assertNotNull(compressWithUIKit(jpeg(picture(800, 600))))))
    }

    @Test fun aPhotoStoredSidewaysComesOutTheWayItIsMeantToBeSeen() {
        val sideways = UIImage.imageWithCGImage(picture(200, 100).CGImage, scale = 1.0, orientation = UIImageOrientation.UIImageOrientationRight)
        assertEquals(100 to 200, sideways.size.useContents { width.toInt() to height.toInt() })
        assertEquals(100 to 200, sizeOf(assertNotNull(compressWithUIKit(jpeg(sideways)))))
    }

    @Test fun aPictureWithTransparencyBecomesAJpeg() {
        val png = assertNotNull(UIImagePNGRepresentation(picture(400, 300, opaque = false))).toByteArray()
        val out = assertNotNull(compressWithUIKit(png))
        assertJpeg(out)
        assertEquals(400 to 300, sizeOf(out))
    }

    @Test fun somethingThatIsNotAnImageIsNull() {
        assertNull(compressWithUIKit("not a picture".encodeToByteArray()))
        assertNull(compressWithUIKit(ByteArray(0)))
    }
}
