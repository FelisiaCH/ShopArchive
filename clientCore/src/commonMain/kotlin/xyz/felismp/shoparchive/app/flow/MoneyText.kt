package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.parseAmount

/** [amount] (a decimal string like `1500000` or `-120.50`) with a separator between the thousands. For display only; the string itself is what the server and files hold. */
fun groupThousands(amount: String, grouping: Char = localeGroupingSeparator(), decimal: Char = localeDecimalSeparator()): String {
    val negative = amount.startsWith("-")
    val body = amount.removePrefix("-")
    val whole = body.substringBefore('.')
    val fraction = if ('.' in body) decimal + body.substringAfter('.') else ""
    val grouped = whole.reversed().chunked(3).joinToString(grouping.toString()).reversed()
    return (if (negative) "-" else "") + grouped + fraction
}

/** [minor] units of a currency with [exponent] decimals, written for the screen: `1,500,000` for LAK, `1,200.50` for THB. */
fun displayAmount(minor: Long, exponent: Int, grouping: Char = localeGroupingSeparator(), decimal: Char = localeDecimalSeparator()): String =
    groupThousands(formatAmount(minor, exponent), grouping, decimal)

/** The thousands separator of the device's language and region (`,` in en-US, `.` in de-DE). */
expect fun localeGroupingSeparator(): Char

/** The decimal separator of the device's language and region (`.` in en-US, `,` in de-DE). */
expect fun localeDecimalSeparator(): Char

/** The language of the device: `lo` or `th`, else `en` (the app's three). Where the person chose one in the app, that is the one the platform answers with. */
expect fun systemLanguage(): String

/** A total the server sent (`-700`, `1200.50`, `0`) in minor units; unlike [parseTypedAmount] it may be negative or zero. Null if it is not a number with at most [exponent] decimals. */
fun parseSignedTotal(text: String, exponent: Int): Long? {
    val negative = text.startsWith("-")
    val minor = parseAmount(text.removePrefix("-"), exponent, allowZero = true) ?: return null
    return if (negative) -minor else minor
}

private val GROUP_SEPARATORS = Regex("[,\\u00A0 ]")

private val DOT_GROUPED = Regex("\\d{1,3}(\\.\\d{3})+")

/**
 * What a person typed or pasted as an amount, in minor units, or null if it is not one: digits, a single separator for the decimals
 * (at most [exponent]), and grouping separators (comma, space, no-break space) only between groups of three digits ("2,500",
 * "1 234 567"). A sign, letters or a second decimal separator are refused, never guessed at. The decimal separator is the one of the
 * device's language ([localeDecimalSeparator]), as the keyboard and the screen show it.
 */
fun parseTypedAmount(text: String, exponent: Int, allowZero: Boolean = false): Long? =
    parseTypedAmount(text, exponent, allowZero, localeDecimalSeparator())

/**
 * [parseTypedAmount] for a language whose [decimal] separator is given. With ".", the comma groups ("1,500" is 1500, "1,50" is refused).
 * With ",", the comma is the decimal ("1,50" is 1.50, "1.234,50" is 1234.50) and the dot groups, so "1.500" is 1500 and never 1.5; a dot
 * that cannot be a grouping ("12.5") is still read as the decimal. A currency without decimals has none to separate: there both only group.
 */
internal fun parseTypedAmount(text: String, exponent: Int, allowZero: Boolean, decimal: Char): Long? {
    val typed = text.trim()
    if (typed.isEmpty() || typed.any { !(it in '0'..'9' || it == '.' || it == ',' || it == ' ' || it == '\u00A0') }) return null
    val dotted = (if (decimal == ',') withDotDecimal(typed, exponent) else typed) ?: return null
    val parts = dotted.split('.')
    if (parts.size > 2) return null
    val groups = parts[0].split(GROUP_SEPARATORS)
    if (groups.size > 1 && (groups[0].length !in 1..3 || groups.drop(1).any { it.length != 3 })) return null
    if (groups.any { it.isEmpty() || !it.all { c -> c in '0'..'9' } }) return null
    val canonical = groups.joinToString("") + (if (parts.size == 2) "." + parts[1] else "")
    return parseAmount(canonical, exponent, allowZero)
}

/** [typed] written for a comma-decimal language, as the dot-decimal text with comma groups that [parseTypedAmount] reads; null if it mixes the two. */
private fun withDotDecimal(typed: String, exponent: Int): String? {
    val commas = typed.count { it == ',' }
    return when {
        exponent == 0 -> if (commas > 0 && '.' in typed) null else typed.replace('.', ',')
        commas > 1 -> null
        commas == 1 -> {
            val fraction = typed.substringAfter(',')
            if ('.' in fraction) null else typed.substringBefore(',').replace('.', ',') + "." + fraction
        }
        DOT_GROUPED.matches(typed) -> typed.replace('.', ',')
        else -> typed
    }
}
