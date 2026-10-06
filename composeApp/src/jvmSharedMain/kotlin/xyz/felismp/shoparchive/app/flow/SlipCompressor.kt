package xyz.felismp.shoparchive.app.flow

/** Longest side of a slip photo after compression, in pixels, and the JPEG quality (percent). */
const val SLIP_MAX_SIDE = 1600
const val SLIP_QUALITY = 80

/** Turns a picked photo into the JPEG that is sent: scaled so its longest side is at most [SLIP_MAX_SIDE], quality [SLIP_QUALITY]. */
fun interface SlipCompressor {
    /** The JPEG bytes, or null when [raw] is not an image this device can read. */
    suspend fun compress(raw: ByteArray): ByteArray?
}

/** The platform's compressor: Android's bitmap classes, or javax.imageio on the desktop. */
expect fun createSlipCompressor(): SlipCompressor
