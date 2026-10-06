package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryChange
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The text of entry, session and history files: what is written, and what is refused when read. */
class RecordFilesTest {
    private val exponents = mapOf("LAK" to 0, "THB" to 2, "USD" to 2)
    private val by = UserRef("0b7c55de-1111-4222-8333-444455556666", "noy")

    private fun entry(item: String = "coffee", note: String = "", category: String? = null, session: String? = null) = Entry(
        "0192a6f4-7c1e-7b3a-9f10-2a3b4c5d6e7f", LocalDate.of(2026, 9, 27), EntryType.EXPENSE, "market", category, item, note, by,
        "2026-09-27T08:14:03+07:00", "2026-09-27T08:20:00+07:00", deleted = false,
        tenders = listOf(Tender("LAK", TenderMethod.CASH, Amount(150000, 0)), Tender("THB", TenderMethod.ONLINE, Amount(12050, 2))),
        slips = listOf(Slip("slip-1.jpg", "3f".repeat(32))), session = session,
    )

    @Test
    fun anEntryIsWrittenInTheShapeOfThePlanWithEveryTextQuoted() {
        val text = renderEntry(entry(item = "ນ້ຳກ້ອນ", category = "supplies", session = "0192a6f4-0000-7000-8000-000000000001"))

        assertEquals(
            """
            file-version: 1
            id: 0192a6f4-7c1e-7b3a-9f10-2a3b4c5d6e7f
            session: 0192a6f4-0000-7000-8000-000000000001
            date: 2026-09-27
            type: expense
            branch: "market"
            category: "supplies"
            item: "ນ້ຳກ້ອນ"
            note: ""
            created-by: { id: "0b7c55de-1111-4222-8333-444455556666", name: "noy" }
            created-at: "2026-09-27T08:14:03+07:00"
            updated-at: "2026-09-27T08:20:00+07:00"
            deleted: false
            tenders:
              - currency: LAK
                method: cash
                amount: "150000"
              - currency: THB
                method: online
                amount: "120.50"
            slips:
              - file: "slip-1.jpg"
                sha256: "${"3f".repeat(32)}"
            """.trimIndent() + "\n",
            text,
        )
    }

    @Test
    fun textThatLooksLikeOtherYamlStaysText() {
        for (odd in listOf("null", "true", "yes", "1e3", "0x10", "~", "- not a list", "a: b", "# not a comment", "\"quoted\"", "back\\slash", "tab\there", "line1\nline2", "  padded  ", "", "ກາເຟ ☕ กาแฟ")) {
            val read = parseEntry(renderEntry(entry(item = odd, note = odd, category = null)), exponents)
            assertEquals(odd, read.item, "item '$odd'")
            assertEquals(odd, read.note)
            assertNull(read.category)
        }
        assertEquals("null", parseEntry(renderEntry(entry(category = "null")), exponents).category, "the text null is not no category")
    }

    @Test
    fun anEntryReadsBackExactlyAsItWasWritten() {
        val original = entry(item = "x", category = "supplies", session = "0192a6f4-0000-7000-8000-000000000001")

        assertEquals(original, parseEntry(renderEntry(original), exponents))
        assertEquals(original.copy(session = null, deleted = true), parseEntry(renderEntry(original.copy(session = null, deleted = true)), exponents))
    }

    @Test
    fun anAmountMayBeWrittenWithFewerDecimalsByHandButNotWithMore() {
        val text = renderEntry(entry())

        assertEquals(12050, parseEntry(text.replace("\"120.50\"", "\"120.5\""), exponents).tenders[1].amount.minor)
        assertFailsWith<RecordFormatException> { parseEntry(text.replace("\"120.50\"", "\"120.505\""), exponents) }
        assertFailsWith<RecordFormatException> { parseEntry(text.replace("\"150000\"", "\"150000.0\""), exponents) }
        assertFailsWith<RecordFormatException> { parseEntry(text.replace("\"150000\"", "\"0\""), exponents) }
        assertFailsWith<RecordFormatException> { parseEntry(text.replace("\"150000\"", "\"-5\""), exponents) }
    }

    @Test
    fun anEntryFileWithAnythingMissingOrWrongIsRefusedWithAReasonNamingTheField() {
        val good = renderEntry(entry())
        val cases = mapOf(
            "id: 0192a6f4-7c1e-7b3a-9f10-2a3b4c5d6e7f" to "id: 1234",
            "date: 2026-09-27" to "date: 2026-02-30",
            "type: expense" to "type: transfer",
            "branch: \"market\"" to "branch: \"Mar Ket\"",
            "deleted: false" to "deleted: maybe",
            "file-version: 1" to "file-version: soon",
            "  - file: \"slip-1.jpg\"" to "  - file: \"evil/../x.jpg\"",
            "method: cash" to "method: barter",
        )
        for ((from, to) in cases) {
            val failure = assertFailsWith<RecordFormatException>("$from -> $to") { parseEntry(good.replace(from, to), exponents) }
            assertTrue(failure.message!!.isNotBlank())
        }
        assertFailsWith<RecordFormatException> { parseEntry("", exponents) }
        assertFailsWith<RecordFormatException> { parseEntry("- just\n- a list\n", exponents) }
        assertFailsWith<RecordFormatException> { parseEntry(good.substringBefore("tenders:"), exponents) }
        assertFailsWith<RecordFormatException> { parseEntry(good.substringBefore("tenders:") + "tenders: []\nslips: []\n", exponents) }
    }

    @Test
    fun aSessionIsWrittenOpenAndClosedAndReadBack() {
        val open = Session(
            "0192a6f4-0000-7000-8000-000000000001", "main", LocalDate.of(2026, 10, 3), by, "2026-10-03T08:00:00+07:00",
            mapOf("LAK" to Amount(50000, 0), "THB" to Amount(2000, 2)), closed = null,
        )
        val closed = open.copy(
            closed = Closed(
                counted = mapOf("LAK" to Amount(219500, 0)), expected = mapOf("LAK" to Amount(220000, 0)),
                variance = mapOf("LAK" to Amount(-500, 0)), handover = mapOf("LAK" to Amount(169500, 0)),
                belowFloor = listOf("THB"), note = "short \"500\"", closedBy = by, closedAt = "2026-10-03T20:00:00+07:00",
            ),
        )

        assertEquals(open, parseSession(renderSession(open), exponents))
        assertEquals(closed, parseSession(renderSession(closed), exponents))
        assertTrue("float: { LAK: \"50000\", THB: \"20.00\" }" in renderSession(open))
        assertTrue("variance: { LAK: \"-500\" }" in renderSession(closed))
        assertTrue("float: {}" in renderSession(open.copy(float = emptyMap())))
        assertEquals(emptyMap(), parseSession(renderSession(open.copy(float = emptyMap())), exponents).float)
    }

    @Test
    fun theHistoryOnlyEverGrowsByAppendingItems() {
        val items = listOf(
            HistoryItem("2026-09-27T08:14:03+07:00", by, "create", emptyList()),
            HistoryItem("2026-09-27T09:00:00+07:00", by, "edit", listOf(HistoryChange("item", "a \"b\"", "ກ"), HistoryChange("tenders", "LAK cash 1", "LAK cash 2"))),
        )
        val text = renderHistoryHead() + items.joinToString("") { renderHistoryItem(it) }

        assertEquals(items, parseHistory(text))
        assertEquals(renderHistoryHead() + renderHistoryItem(items[0]), text.substringBefore("  - at: \"2026-09-27T09"), "an item is added after the earlier ones, which are not touched")
    }

    @Test
    fun aTimeWithoutItsOffsetOrSecondsOrWithAnOddYearMakesTheEntryUnreadable() {
        for (odd in listOf("2026-09-27T09:00:00", "2026-09-27T09:00Z", "+999999999-12-31T23:59:59-18:00", "2026-09-27 09:00:00+07:00")) {
            val text = renderEntry(entry()).replace(Regex("created-at: \"[^\"]*\""), "created-at: \"$odd\"")

            val e = assertFailsWith<RecordFormatException>(odd) { parseEntry(text, exponents) }

            assertTrue("created-at" in e.message!!, e.message)
        }
        val fine = renderEntry(entry()).replace(Regex("created-at: \"[^\"]*\""), "created-at: \"2026-09-27T02:00:00.5Z\"")
        assertEquals("2026-09-27T02:00:00.5Z", parseEntry(fine, exponents).createdAt)
    }
}
