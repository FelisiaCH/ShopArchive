package xyz.felismp.shoparchive.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordsTest {
    private val rules = EntryRules(
        exponents = mapOf("LAK" to 0, "THB" to 2),
        categories = mapOf(
            "supplies" to CategoryRule(AppliesTo.EXPENSE, archived = false),
            "sales" to CategoryRule(AppliesTo.INCOME, archived = false),
            "misc" to CategoryRule(AppliesTo.BOTH, archived = false),
            "old" to CategoryRule(AppliesTo.BOTH, archived = true),
        ),
        requireCategory = false,
        slipMaxCount = 2,
    )
    private val cash = TenderDto("LAK", TenderMethod.CASH, "150000")
    private val online = TenderDto("THB", TenderMethod.ONLINE, "120.50")

    private fun check(
        tenders: List<TenderDto> = listOf(cash), slips: Int = 0, category: String? = null,
        type: EntryType = EntryType.INCOME, rules: EntryRules = this.rules,
    ) = checkEntry(type, category, tenders, slips, rules)?.message

    private fun reason(tenders: List<TenderDto> = listOf(cash), slips: Int = 0, category: String? = null) =
        checkEntry(EntryType.INCOME, category, tenders, slips, rules)?.reason

    @Test
    fun everyRefusalCarriesAKnownReasonKey() {
        assertEquals(ErrorReasons.ENTRY_NO_AMOUNT, reason(tenders = emptyList()))
        assertEquals(ErrorReasons.ENTRY_AMOUNT_INVALID, reason(tenders = listOf(TenderDto("LAK", TenderMethod.CASH, "0"))))
        assertEquals(ErrorReasons.ENTRY_CURRENCY_UNKNOWN, reason(tenders = listOf(TenderDto("EUR", TenderMethod.CASH, "5"))))
        assertEquals(ErrorReasons.ENTRY_SLIP_REQUIRED, reason(tenders = listOf(online)))
        assertEquals(ErrorReasons.ENTRY_SLIP_NOT_ALLOWED, reason(slips = 1))
        assertEquals(ErrorReasons.ENTRY_SLIP_TOO_MANY, reason(tenders = listOf(online), slips = 3))
        assertEquals(ErrorReasons.ENTRY_CATEGORY_UNKNOWN, reason(category = "nope"))
        assertEquals(ErrorReasons.ENTRY_CATEGORY_ARCHIVED, reason(category = "old"))
        assertEquals(ErrorReasons.ENTRY_CATEGORY_TYPE, reason(category = "supplies"))
        assertEquals(ErrorReasons.all.size, ErrorReasons.all.toSet().size)
    }

    @Test
    fun aCashEntryWithoutCategoryOrSlipIsAcceptedWhenCategoriesAreOptional() {
        assertNull(check())
    }

    @Test
    fun anEntryNeedsATenderWorthSomethingInAKnownCurrency() {
        assertTrue(check(tenders = emptyList())!!.contains("at least one"))
        assertTrue(check(tenders = listOf(TenderDto("LAK", TenderMethod.CASH, "0")))!!.contains("not valid"))
        assertTrue(check(tenders = listOf(TenderDto("LAK", TenderMethod.CASH, "12.5")))!!.contains("without decimals"))
        assertTrue(check(tenders = listOf(TenderDto("THB", TenderMethod.CASH, "1.234")))!!.contains("at most 2"))
        assertTrue(check(tenders = listOf(TenderDto("EUR", TenderMethod.CASH, "5")))!!.contains("EUR"))
    }

    @Test
    fun slipsAreNeededExactlyWhenSomethingIsPaidOnline() {
        assertTrue(check(tenders = listOf(online), slips = 0)!!.contains("needs a slip"))
        assertNull(check(tenders = listOf(online), slips = 1))
        assertNull(check(tenders = listOf(cash, online), slips = 2))
        assertTrue(check(tenders = listOf(cash), slips = 1)!!.contains("online only"))
        assertTrue(check(tenders = listOf(online), slips = 3)!!.contains("at most 2"))
    }

    @Test
    fun aCategoryMustExistBeInUseAndFitTheType() {
        assertNull(check(category = "sales"))
        assertNull(check(category = "misc", type = EntryType.EXPENSE))
        assertTrue(check(category = "supplies")!!.contains("not for income"))
        assertTrue(check(category = "sales", type = EntryType.EXPENSE)!!.contains("not for expense"))
        assertTrue(check(category = "nope")!!.contains("does not exist"))
        assertTrue(check(category = "old")!!.contains("not in use"))
    }

    @Test
    fun aCategoryIsOnlyDemandedWhenTheRulesSaySo() {
        val strict = EntryRules(rules.exponents, rules.categories, requireCategory = true, slipMaxCount = 2)
        assertTrue(check(rules = strict)!!.contains("category is needed"))
        assertNull(check(rules = strict, category = "sales"))
    }

    @Test
    fun idsKeysAndDatesAreChecked() {
        assertTrue(isUuidV7("0192a6f4-7c1e-7b3a-9f10-2a3b4c5d6e7f"))
        assertFalse(isUuidV7("0192a6f4-7c1e-4b3a-9f10-2a3b4c5d6e7f")) // version 4
        assertFalse(isUuidV7("0192a6f4-7c1e-7b3a-1f10-2a3b4c5d6e7f")) // bad variant
        assertFalse(isUuidV7("0192A6F4-7C1E-7B3A-9F10-2A3B4C5D6E7F")) // upper case: Windows would treat it as the same folder
        assertFalse(isUuidV7("../0192a6f4-7c1e-7b3a-9f10-2a3b4c5d6e7f"))
        assertTrue(isSlug("main"))
        assertTrue(isSlug("market-2"))
        assertFalse(isSlug("Main"))
        assertFalse(isSlug("a/b"))
        assertFalse(isSlug(".."))
        assertFalse(isSlug(""))
        assertFalse(isSlug("a".repeat(33)))
        assertTrue(isIsoDate("2026-02-28"))
        assertTrue(isIsoDate("2028-02-29"))
        assertFalse(isIsoDate("2026-02-29"))
        assertFalse(isIsoDate("2026-13-01"))
        assertFalse(isIsoDate("2026-4-01"))
        assertFalse(isIsoDate("2026-04-31"))
    }

    @Test
    fun slipsAreRecognisedByTheirFirstBytesNotTheirName() {
        assertEquals("jpg", detectSlipExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", detectSlipExtension(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertNull(detectSlipExtension("GIF89a".encodeToByteArray()))
        assertNull(detectSlipExtension("<html>".encodeToByteArray()))
        assertNull(detectSlipExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) // too short
        assertNull(detectSlipExtension(ByteArray(0)))
    }

    @Test
    fun theDrawerAddsUpFloorPlusCashInMinusCashOut() {
        // float 50000, in 200000, out 30000 -> expected 220000
        val match = closeFigures(50_000, 200_000, 30_000, counted = 220_000)
        assertEquals(220_000, match.expected)
        assertEquals(0, match.variance)
        assertEquals(170_000, match.handover) // the float stays for the next day
        assertFalse(match.belowFloor)

        val short = closeFigures(50_000, 200_000, 30_000, counted = 219_500)
        assertEquals(-500, short.variance)
        assertEquals(169_500, short.handover)

        val over = closeFigures(50_000, 200_000, 30_000, counted = 221_000)
        assertEquals(1_000, over.variance)
        assertEquals(171_000, over.handover)
    }

    @Test
    fun countingLessThanTheFloorHandsOverNothingAndWarns() {
        val figures = closeFigures(50_000, 0, 0, counted = 20_000)
        assertEquals(-30_000, figures.variance)
        assertEquals(0, figures.handover)
        assertTrue(figures.belowFloor)
    }

    @Test
    fun moreCashOutThanInCanLeaveLessThanTheFloorExpected() {
        val figures = closeFigures(10_000, 5_000, 20_000, counted = 0)
        assertEquals(-5_000, figures.expected)
        assertEquals(5_000, figures.variance)
    }
}
