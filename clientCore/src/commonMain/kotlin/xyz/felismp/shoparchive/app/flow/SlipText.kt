package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import xyz.felismp.shoparchive.shared.isSlipCode

/** What one read of a slip photo found: all the [text] on it, and the [code] (the whole text of the QR the bank prints on it, trimmed). Each is null when there is none. */
data class SlipScan(val text: String?, val code: String?)

/** Reads a slip photo, on this device only (no network). */
fun interface SlipReader {
    /** What is on the photo, or null when this device cannot read photos. */
    suspend fun read(jpeg: ByteArray): SlipScan?
}

/** What [SlipReader.read] finds on [jpeg], with a code that cannot be one dropped; null when this device cannot read photos or the read failed (reading only helps, it never fails a slip). */
suspend fun SlipReader.tryRead(jpeg: ByteArray): SlipScan? = try {
    read(jpeg)?.let { it.copy(code = it.code?.takeIf(::isSlipCode)) }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** A number written with comma thousands (`850,000`, `190,000.00`) that is not part of a longer number. Plain digits (a date, a reference) are not amounts. */
private val GROUPED_NUMBER = Regex("""(?<![\d,.])(\d{1,3}(?:,\d{3})+)(?:\.(\d+))?(?![\d,])""")

/**
 * The amount paid on a bank transfer slip whose [text] was read off the photo: the currency code and the amount in minor units,
 * or null when the text does not say so clearly. The currency is the one configured code (a key of [exponents]) that appears
 * as a whole word, and there must be exactly one. The amount is the one seen most often, and among those seen as often the largest
 * (the fee on the slip is smaller). Seen are the grouped numbers and the watermark BCEL prints all over its slips
 * ("amount 850000 LAK"): a misread copy is seen once, the amount many times.
 * Decimals beyond the currency's own are accepted only when they are zeros ("190,000.00" in kip is 190000).
 */
fun slipAmount(text: String, exponents: Map<String, Int>): Pair<String, Long>? {
    val code = exponents.keys.filter { Regex("""(?<!\p{L})""" + Regex.escape(it) + """(?!\p{L})""").containsMatchIn(text) }.singleOrNull() ?: return null
    val exponent = exponents.getValue(code)
    val grouped = GROUPED_NUMBER.findAll(text).mapNotNull { m ->
        val fraction = m.groupValues[2]
        if (fraction.drop(exponent).any { it != '0' }) return@mapNotNull null
        val kept = fraction.take(exponent)
        parseTypedAmount(m.groupValues[1] + if (kept.isEmpty()) "" else ".$kept", exponent, allowZero = false, decimal = '.')
    }
    val watermark = Regex("""(?<!\p{L})amount (\d+)[\s-]*""" + Regex.escape(code) + """(?!\p{L})""").findAll(text).mapNotNull { parseTypedAmount(it.groupValues[1], exponent, allowZero = false, decimal = '.') }
    val amount = (grouped + watermark).groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })?.key ?: return null
    return code to amount
}
