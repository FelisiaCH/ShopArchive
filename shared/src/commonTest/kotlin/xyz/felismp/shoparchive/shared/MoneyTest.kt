package xyz.felismp.shoparchive.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTest {
    @Test
    fun amountsParseToMinorUnitsPerCurrency() {
        val cases = listOf(
            Triple("150000", 0, 150000L), // LAK
            Triple("1", 0, 1L),
            Triple("120.50", 2, 12050L), // THB
            Triple("120.5", 2, 12050L), // fewer decimals than the currency has: padded
            Triple("120", 2, 12000L),
            Triple("0.01", 2, 1L),
            Triple("0.50", 2, 50L),
            Triple("9999999999999.99", 2, 999_999_999_999_999L), // the largest: 15 digits
            Triple("999999999999999", 0, 999_999_999_999_999L),
            Triple("1.2345", 4, 12345L),
        )
        for ((text, exponent, minor) in cases) assertEquals(minor, parseAmount(text, exponent), "$text / $exponent")
    }

    @Test
    fun anythingButPlainDigitsWithAtMostTheCurrencysDecimalsIsRefused() {
        val bad = listOf(
            "" to 2, " " to 2, "-5" to 2, "+5" to 2, "5 " to 2, " 5" to 2, "1,000" to 2, "1 000" to 2, "1e3" to 0, "1E3" to 0, "0x10" to 0,
            "1.234" to 2, // too many decimals: rounding would hide a typo
            "1.0" to 0, "1." to 2, ".5" to 2, "." to 2, "1..2" to 2, "1.2.3" to 2,
            "0" to 0, "0.00" to 2, // an entry is worth something
            "007" to 0, "00.50" to 2, // canonical form only
            "١٢٣" to 0, "๑๒๓" to 0, // digits of other scripts
            "NaN" to 0, "Infinity" to 0, "1_000" to 0,
            "9999999999999.999" to 3, "1000000000000000" to 0, // 16 digits
            "99999999999999999999999999" to 0, // would overflow a Long
        )
        for ((text, exponent) in bad) assertNull(parseAmount(text, exponent), "'$text' / $exponent")
    }

    @Test
    fun aFloatOrCountMayBeZeroButNothingElseChanges() {
        assertEquals(0L, parseAmount("0", 0, allowZero = true))
        assertEquals(0L, parseAmount("0.00", 2, allowZero = true))
        assertNull(parseAmount("-0", 0, allowZero = true))
        assertNull(parseAmount("00", 0, allowZero = true))
    }

    @Test
    fun amountsAreWrittenWithExactlyTheCurrencysDecimals() {
        assertEquals("150000", formatAmount(150000, 0))
        assertEquals("0", formatAmount(0, 0))
        assertEquals("120.50", formatAmount(12050, 2))
        assertEquals("0.05", formatAmount(5, 2))
        assertEquals("0.00", formatAmount(0, 2))
        assertEquals("1.2345", formatAmount(12345, 4))
        assertEquals("-12.50", formatAmount(-1250, 2))
        assertEquals("-500", formatAmount(-500, 0))
        assertEquals("-0.01", formatAmount(-1, 2))
        assertEquals("92233720368547758.07", formatAmount(Long.MAX_VALUE, 2))
        assertEquals("-92233720368547758.08", formatAmount(Long.MIN_VALUE, 2))
    }

    @Test
    fun whatIsWrittenReadsBackTheSame() {
        for (exponent in 0..4) {
            for (minor in listOf(1L, 9L, 10L, 99L, 100L, 12345L, 999_999_999_999_999L)) {
                assertEquals(minor, parseAmount(formatAmount(minor, exponent), exponent), "$minor / $exponent")
            }
        }
    }
}
