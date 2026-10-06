package xyz.felismp.shoparchive.app.flow

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import xyz.felismp.shoparchive.shared.isUuidV7

class MoneyTextTest {
    @Test fun serverTotalsMayBeNegative() {
        assertEquals(-700L, parseSignedTotal("-700", 0))
        assertEquals(-25L, parseSignedTotal("-0.25", 2))
        assertEquals(0L, parseSignedTotal("0.00", 2))
        assertEquals(null, parseSignedTotal("-1.5", 0))
        assertEquals(null, parseSignedTotal("--1", 0))
    }

    @Test fun kipHasNoDecimalsAndGroupsThousands() {
        assertEquals("1,500,000", displayAmount(1_500_000, 0, ',', '.'))
        assertEquals("999", displayAmount(999, 0, ',', '.'))
        assertEquals("1,000", displayAmount(1000, 0, ',', '.'))
        assertEquals("0", displayAmount(0, 0, ',', '.'))
    }

    @Test fun bahtKeepsItsTwoDecimals() {
        assertEquals("1,200.50", displayAmount(120_050, 2, ',', '.'))
        assertEquals("0.05", displayAmount(5, 2, ',', '.'))
        assertEquals("12.00", displayAmount(1200, 2, ',', '.'))
    }

    @Test fun aNegativeAmountKeepsItsSign() {
        assertEquals("-1,200.50", displayAmount(-120_050, 2, ',', '.'))
        assertEquals("-300", groupThousands("-300", ',', '.'))
    }

    @Test fun theLocalesSeparatorsAreUsed() {
        assertEquals("1.200,50", displayAmount(120_050, 2, '.', ','))
        assertEquals("1 500", groupThousands("1500", ' ', '.'))
    }

    @Test fun serverTextsAreGroupedWithoutLosingTheirDecimals() {
        assertEquals("1,234,567.80", groupThousands("1234567.80", ',', '.'))
    }
}

class TypedAmountTest {
    @Test fun accepted() {
        assertEquals(2500L, parseTypedAmount("2,500", 0))
        assertEquals(123450L, parseTypedAmount("1,234.50", 2))
        assertEquals(1234567L, parseTypedAmount("1 234 567", 0))
        assertEquals(1500L, parseTypedAmount("1\u00A0500", 0))
        assertEquals(1250L, parseTypedAmount(" 12.5 ", 2))
        assertEquals(0L, parseTypedAmount("0", 0, allowZero = true))
    }

    @Test fun refused() {
        for (typed in listOf("1,50", "1,5", "-700", "+5", "12.345", "1..5", "1.2.3", "1,2345", ",500", "500,", "1,,500", "12a", "", "0,500", "1.5", "1e3")) {
            val exponent = if (typed == "12.345") 2 else 0
            assertEquals(null, parseTypedAmount(typed, exponent), typed)
        }
        assertEquals(null, parseTypedAmount("0", 0)) // zero only where allowed
        assertEquals(null, parseTypedAmount("1.5", 0))
    }
}

class Uuid7Test {
    @Test fun hasTheV7Shape() {
        repeat(200) { assertTrue(isUuidV7(uuidV7(1_790_000_000_000L + it))) }
    }

    @Test fun startsWithTheTimeSoIdsSortByTime() {
        val earlier = uuidV7(1_790_000_000_000L, Random(1))
        val later = uuidV7(1_790_000_000_001L, Random(1))
        assertTrue(earlier < later)
        // The first 12 hex digits are the 48-bit time.
        assertEquals(java.lang.Long.toHexString(1_790_000_000_000L).padStart(12, '0'), earlier.take(13).replace("-", ""))
    }

    @Test fun twoIdsOfTheSameMillisecondDiffer() {
        assertNotEquals(uuidV7(5), uuidV7(5))
    }
}
