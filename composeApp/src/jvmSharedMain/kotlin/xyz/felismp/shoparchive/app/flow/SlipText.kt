package xyz.felismp.shoparchive.app.flow

/** Reads the text on a slip photo, on this device only (no network). */
fun interface SlipReader {
    /** The text on the photo, or null when this device cannot read photos. */
    suspend fun text(jpeg: ByteArray): String?
}

/** The platform's reader: ML Kit on Android; the desktop has none. */
expect fun createSlipReader(): SlipReader

/** A number written with comma thousands (`850,000`, `190,000.00`) that is not part of a longer number. Plain digits (a date, a reference) are not amounts. */
private val GROUPED_NUMBER = Regex("""(?<![\d,.])(\d{1,3}(?:,\d{3})+)(?:\.(\d+))?(?![\d,])""")

/**
 * The amount paid on a bank transfer slip whose [text] was read off the photo: the currency code and the amount in minor units,
 * or null when the text does not say so clearly. The currency is the one configured code (a key of [exponents]) that appears
 * as a whole word, and there must be exactly one. The amount is the grouped number seen most often (the watermark repeats it, while a
 * misread copy of it is seen once), and among those seen as often the largest (the fee on the slip is smaller).
 * Decimals beyond the currency's own are accepted only when they are zeros ("190,000.00" in kip is 190000).
 */
fun slipAmount(text: String, exponents: Map<String, Int>): Pair<String, Long>? {
    val code = exponents.keys.filter { Regex("""(?<!\p{L})""" + Regex.escape(it) + """(?!\p{L})""").containsMatchIn(text) }.singleOrNull() ?: return null
    val exponent = exponents.getValue(code)
    val amount = GROUPED_NUMBER.findAll(text).mapNotNull { m ->
        val fraction = m.groupValues[2]
        if (fraction.drop(exponent).any { it != '0' }) return@mapNotNull null
        val kept = fraction.take(exponent)
        parseTypedAmount(m.groupValues[1] + if (kept.isEmpty()) "" else ".$kept", exponent)
    }.groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })?.key ?: return null
    return code to amount
}
