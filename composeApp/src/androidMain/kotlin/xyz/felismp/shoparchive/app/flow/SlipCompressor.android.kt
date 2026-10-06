package xyz.felismp.shoparchive.app.flow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

actual fun createSlipCompressor(): SlipCompressor = SlipCompressor { raw -> withContext(Dispatchers.Default) { compressWithBitmap(raw) } }

private fun compressWithBitmap(raw: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Decode at a power-of-two fraction first so a 12-megapixel photo never sits in memory at full size.
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SLIP_MAX_SIDE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = minOf(1f, SLIP_MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        // A phone camera stores the photo sideways and says so in the EXIF data.
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }
    val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    // JPEG has no transparency: put a see-through picture on white.
    val flat = if (!result.hasAlpha()) result else Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(result, 0f, 0f, null) }
    }
    val out = ByteArrayOutputStream()
    flat.compress(Bitmap.CompressFormat.JPEG, SLIP_QUALITY, out)
    return out.toByteArray()
}
