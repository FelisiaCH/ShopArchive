package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.parseAmount
import java.text.DecimalFormatSymbols
import java.util.Locale

/** [amount] (a decimal string like `1500000` or `-120.50`) with a separator between the thousands. For display only; the string itself is what the server and files hold. */
fun groupThousands(amount: String, grouping: Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).groupingSeparator, decimal: Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator): String {
    val negative = amount.startsWith("-")
    val body = amount.removePrefix("-")
    val whole = body.substringBefore('.')
    val fraction = if ('.' in body) decimal + body.substringAfter('.') else ""
    val grouped = whole.reversed().chunked(3).joinToString(grouping.toString()).reversed()
    return (if (negative) "-" else "") + grouped + fraction
}

/** [minor] units of a currency with [exponent] decimals, written for the screen: `1,500,000` for LAK, `1,200.50` for THB. */
fun displayAmount(minor: Long, exponent: Int, grouping: Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).groupingSeparator, decimal: Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator): String =
    groupThousands(formatAmount(minor, exponent), grouping, decimal)

/** A total the server sent (`-700`, `1200.50`, `0`) in minor units; unlike [parseTypedAmount] it may be negative or zero. Null if it is not a number with at most [exponent] decimals. */
fun parseSignedTotal(text: String, exponent: Int): Long? {
    val negative = text.startsWith("-")
    val minor = parseAmount(text.removePrefix("-"), exponent, allowZero = true) ?: return null
    return if (negative) -minor else minor
}

private val GROUP_SEPARATORS = Regex("[,\\u00A0 ]")

/**
 * What a person typed or pasted as an amount, in minor units, or null if it is not one: digits, a single dot for the decimals
 * (at most [exponent]), and grouping separators (comma, space, no-break space) only between groups of three digits ("2,500",
 * "1 234 567"). A comma as the decimal ("1,50"), a sign, letters or a second dot are refused, never guessed at.
 */
fun parseTypedAmount(text: String, exponent: Int, allowZero: Boolean = false): Long? {
    val typed = text.trim()
    if (typed.isEmpty() || typed.any { !(it in '0'..'9' || it == '.' || it == ',' || it == ' ' || it == '\u00A0') }) return null
    val parts = typed.split('.')
    if (parts.size > 2) return null
    val groups = parts[0].split(GROUP_SEPARATORS)
    if (groups.size > 1 && (groups[0].length !in 1..3 || groups.drop(1).any { it.length != 3 })) return null
    if (groups.any { it.isEmpty() || !it.all { c -> c in '0'..'9' } }) return null
    val canonical = groups.joinToString("") + (if (parts.size == 2) "." + parts[1] else "")
    return parseAmount(canonical, exponent, allowZero)
}
