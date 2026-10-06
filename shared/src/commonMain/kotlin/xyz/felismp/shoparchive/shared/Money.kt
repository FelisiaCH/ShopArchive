package xyz.felismp.shoparchive.shared

/**
 * The largest amount in minor units (15 digits). Beyond it a sum over a year of entries could overflow a Long,
 * and no shop counts that much. Money is a Long of minor units everywhere in memory; text is for files and JSON.
 */
const val MAX_MINOR: Long = 999_999_999_999_999L

private const val MAX_DIGITS = 15

/**
 * Reads an amount as written in files and JSON: digits, and for a currency with decimals a dot and at most
 * [exponent] digits after it ("150000", "120.50", "120.5"). Nothing else: no sign, space, thousands separator,
 * exponent notation or leading zero. Zero is refused unless [allowZero] (an entry must be worth something; a float may be 0).
 * Returns the amount in minor units, or null if [text] is not such an amount.
 */
fun parseAmount(text: String, exponent: Int, allowZero: Boolean = false): Long? {
    if (exponent < 0) return null
    val dot = text.indexOf('.')
    val whole = if (dot < 0) text else text.substring(0, dot)
    val fraction = if (dot < 0) "" else text.substring(dot + 1)
    if (dot >= 0 && (exponent == 0 || fraction.isEmpty() || fraction.length > exponent)) return null
    if (whole.isEmpty() || !whole.all { it in '0'..'9' } || !fraction.all { it in '0'..'9' }) return null
    if (whole.length > 1 && whole[0] == '0') return null
    val digits = (whole + fraction.padEnd(exponent, '0')).trimStart('0')
    if (digits.length > MAX_DIGITS) return null
    val minor = if (digits.isEmpty()) 0L else digits.toLong()
    return if (minor == 0L && !allowZero) null else minor
}

/** [minor] as text with exactly [exponent] decimals ("150000", "120.50"); a negative amount (a variance) has a leading "-". */
fun formatAmount(minor: Long, exponent: Int): String {
    require(exponent >= 0) { "exponent must not be negative" }
    val negative = minor < 0
    // Long.MIN_VALUE has no positive twin, so the digits come from the text, not from negating.
    val digits = minor.toString().removePrefix("-").padStart(exponent + 1, '0')
    val cut = digits.length - exponent
    val text = if (exponent == 0) digits else digits.substring(0, cut) + "." + digits.substring(cut)
    return if (negative) "-$text" else text
}
