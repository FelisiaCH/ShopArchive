package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.flow.CloseBlock
import xyz.felismp.shoparchive.app.flow.ClosePhase
import xyz.felismp.shoparchive.app.flow.CloseRow
import xyz.felismp.shoparchive.app.flow.CloseUi
import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.SessionClose
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionStatus
import xyz.felismp.shoparchive.shared.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloseDayViewsTest {
    private val lak = CurrencyInfo("LAK", 0)
    private val session = SessionDto("s1", "main", "2026-11-14", UserRef("u1", "Noy"), "2026-11-14T08:12:30+07:00", emptyMap(), SessionStatus.OPEN)

    private fun row(
        counted: Long? = 1500000,
        invalid: Boolean = false,
        expected: Long? = 1500000,
        variance: Long? = 0,
        float: Long = 500000,
        handover: Long? = 1000000,
        belowFloor: Boolean = false,
    ) = CloseRow(lak, counted?.toString().orEmpty(), float, counted, invalid, expected, variance, handover, belowFloor)

    private fun ui(
        rows: List<CloseRow> = listOf(row()),
        note: String = "",
        noteRequired: Boolean = false,
        phase: ClosePhase = ClosePhase.Idle,
        session: SessionDto? = this.session,
        blocked: CloseBlock? = null,
        previewFailure: Failure? = null,
    ) = CloseUi(session, rows, note, noteRequired, previewFailure, phase, result = null, blocked = blocked)

    @Test
    fun aMatchingCountNeedsNoNoteAndShowsKeepAndHandover() {
        val view = closeDayView(ui())
        assertEquals(Message("closeday_hint", listOf("2026-11-14")), view.hint)
        assertEquals("closeday_note", view.noteLabel)
        assertFalse(view.noteNeeded)
        assertTrue(view.confirmEnabled)
        val row = view.rows.single()
        assertNull(row.variance!!.text)
        assertEquals("closeday_matches", row.variance.words.key)
        assertTrue(row.expected!!.endsWith(" LAK"))
        assertTrue(row.keep!!.endsWith(" LAK"))
        assertTrue(row.handover!!.endsWith(" LAK"))
        assertFalse(row.belowFloor)
    }

    @Test
    fun aDifferenceSaysWhetherTheDrawerIsShortOrOver() {
        val short = closeDayView(ui(listOf(row(counted = 1498500, variance = -1500)))).rows.single()
        assertTrue(short.variance!!.text!!.startsWith("-"))
        assertTrue(short.variance.text.endsWith(" LAK"))
        assertEquals("closeday_short", short.variance.words.key)
        assertTrue(short.variance.words.args.single().endsWith(" LAK"))

        val over = closeDayView(ui(listOf(row(counted = 1501500, variance = 1500)))).rows.single()
        assertTrue(over.variance!!.text!!.startsWith("+"))
        assertEquals("closeday_over", over.variance.words.key)
    }

    @Test
    fun aCountBelowTheChangeWarnsAndHandsOverNothing() {
        val row = closeDayView(ui(listOf(row(counted = 100, variance = -499900, handover = 0, belowFloor = true)))).rows.single()
        assertTrue(row.belowFloor)
        assertEquals("0 LAK", row.handover)
        assertTrue(row.keep!!.endsWith(" LAK"))
    }

    @Test
    fun anInvalidAmountBlocksTheClose() {
        val view = closeDayView(ui(listOf(row(counted = null, invalid = true, expected = null, variance = null))))
        val row = view.rows.single()
        assertTrue(row.invalid)
        assertNull(row.keep)
        assertNull(row.handover)
        assertNull(row.variance)
        assertFalse(view.confirmEnabled)
    }

    @Test
    fun aDifferenceAsksForANoteUntilOneIsWritten() {
        val blank = closeDayView(ui(noteRequired = true, note = ""))
        assertEquals("closeday_note_required", blank.noteLabel)
        assertTrue(blank.noteNeeded)
        assertFalse(blank.confirmEnabled)

        val written = closeDayView(ui(noteRequired = true, note = "The till was short"))
        assertFalse(written.noteNeeded)
        assertTrue(written.confirmEnabled)
    }

    @Test
    fun theFormSaysWhatItIsDoing() {
        val saving = closeDayView(ui(phase = ClosePhase.Saving))
        assertEquals("working", saving.banner?.message?.key)
        assertFalse(saving.confirmEnabled)
        assertTrue(saving.saving)

        val failed = closeDayView(ui(phase = ClosePhase.Failed(Failure.Unreachable)))
        assertEquals("closeday_failed", failed.banner?.message?.key)
        assertEquals("err_unreachable", failed.banner?.message?.detail?.key)
        assertTrue(failed.banner!!.error)

        val offline = closeDayView(ui(blocked = CloseBlock.OFFLINE))
        assertTrue(offline.needsConnection)
        assertFalse(offline.confirmEnabled)

        assertEquals("closeday_no_preview", closeDayView(ui(previewFailure = Failure.Unreachable)).previewBanner?.key)
        assertNull(closeDayView(ui()).banner)
    }

    private fun close(variance: Map<String, String>) = SessionClose(
        counted = mapOf("LAK" to "1500000"),
        expected = mapOf("LAK" to "1500000"),
        variance = variance,
        handover = mapOf("LAK" to "1000000"),
        belowFloor = listOf("LAK"),
        note = "Drawer short",
        closedBy = UserRef("u1", "Noy"),
        closedAt = "2026-11-14T20:00:00+07:00",
    )

    @Test
    fun theClosedDayShowsTheServersFiguresWithASignedDifference() {
        val over = closeResultView("2026-11-14", close(mapOf("LAK" to "1500")))
        assertEquals(Message("closeday_done", listOf("2026-11-14", "Noy")), over.done)
        val card = over.cards.single()
        assertTrue(card.variance.startsWith("+"))
        assertTrue(card.variance.endsWith(" LAK"))
        assertTrue(card.counted.endsWith(" LAK"))
        assertTrue(card.handover.endsWith(" LAK"))
        assertTrue(card.belowFloor)
        assertEquals("closeday_note_is", over.note?.key)

        assertTrue(closeResultView("2026-11-14", close(mapOf("LAK" to "-1500"))).cards.single().variance.startsWith("-"))
        assertEquals("0 LAK", closeResultView("2026-11-14", close(mapOf("LAK" to "0"))).cards.single().variance)
        assertNull(closeResultView("2026-11-14", close(emptyMap()).copy(note = "")).note)
    }
}
