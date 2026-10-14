@file:OptIn(ExperimentalForeignApi::class)

package xyz.felismp.shoparchive.app.flow

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill
import xyz.felismp.shoparchive.app.client.toByteArray
import xyz.felismp.shoparchive.app.client.toNSData

fun createSlipCompressor(): SlipCompressor = SlipCompressor { raw -> withContext(Dispatchers.Default) { compressWithUIKit(raw) } }

internal fun compressWithUIKit(raw: ByteArray): ByteArray? {
    if (raw.isEmpty()) return null
    val source = UIImage.imageWithData(raw.toNSData()) ?: return null
    // `size` is the picture as it is meant to be seen: a phone camera stores it sideways and says so in the EXIF data, and drawing honours that.
    val (width, height) = source.size.useContents { width to height }
    if (width <= 0.0 || height <= 0.0) return null
    val scale = minOf(1.0, SLIP_MAX_SIDE / maxOf(width, height))
    val newWidth = maxOf(1.0, (width * scale).toInt().toDouble())
    val newHeight = maxOf(1.0, (height * scale).toInt().toDouble())
    // One pixel per point, and opaque: JPEG has no transparency, so a see-through picture goes on white.
    val format = UIGraphicsImageRendererFormat.defaultFormat().apply {
        this.scale = 1.0
        opaque = true
    }
    val flat = UIGraphicsImageRenderer(size = CGSizeMake(newWidth, newHeight), format = format).imageWithActions {
        UIColor.whiteColor.setFill()
        UIRectFill(CGRectMake(0.0, 0.0, newWidth, newHeight))
        source.drawInRect(CGRectMake(0.0, 0.0, newWidth, newHeight))
    }
    return UIImageJPEGRepresentation(flat, SLIP_QUALITY / 100.0)?.toByteArray()
}
