package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.SessionPreview
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.SessionClose
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CloseDayStateTest {
    /** What the server says the drawer should hold. */
    private fun preview(lak: String, thb: String) = SessionPreview(emptyMap(), emptyMap(), emptyMap(), mapOf("LAK" to lak, "THB" to thb))

    /** An open day with 100,000 LAK and 500.00 THB of change; the day's cash: LAK +50,000 -20,000 (should be 130,000), THB +1,200.50 -200.25 (should be 1,500.25). */
    private fun TestScope.setup(withDashboard: Boolean = true): Pair<Harness, CloseDayState> {
        val h = Harness(backgroundScope)
        h.api.current = h.api.session("s1").copy(float = mapOf("LAK" to "100000", "THB" to "500.00"))
        if (withDashboard) h.api.previewAnswer = preview("130000", "1500.25")
        val today = TodayState(h.env)
        return h to CloseDayState(h.env, today)
    }

    private fun CloseDayState.count(lak: String, thb: String) {
        setCounted("LAK", lak)
        setCounted("THB", thb)
    }

    private fun CloseDayState.row(code: String) = ui.value.rows.first { it.currency.code == code }

    private val closed = { request: CloseSessionRequest ->
        SessionDto(
            "s1", "main", "2026-10-04", UserRefs.alice, "2026-10-04T08:12:00+07:00", mapOf("LAK" to "100000"), SessionStatus.CLOSED,
            SessionClose(
                request.counted, mapOf("LAK" to "130000", "THB" to "1500.25"), mapOf("LAK" to "-500", "THB" to "0.00"),
                mapOf("LAK" to "29500", "THB" to "1000.25"), emptyList(), request.note, UserRefs.alice, "2026-10-04T21:00:00+07:00",
            ),
        )
    }

    @Test fun theExpectedAmountIsTheChangePlusCashInMinusCashOutPerCurrency() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        assertEquals("s1", close.ui.value.session!!.id)
        assertEquals("main" to "s1", h.api.previewCalls.last())
        close.count("130,000", "1,500.25")
        assertEquals(130_000L, close.row("LAK").expected)
        assertEquals(150_025L, close.row("THB").expected) // two decimals: minor units
        assertEquals(0L, close.row("LAK").variance)
        assertEquals(0L, close.row("THB").variance)
        assertFalse(close.ui.value.noteRequired)
        assertTrue(close.ui.value.canClose)
    }

    @Test fun varianceIsCountedMinusExpectedWithItsSign() = runTest(UnconfinedTestDispatcher()) {
        val (_, close) = setup()
        close.count("129,500", "1500.26")
        assertEquals(-500L, close.row("LAK").variance) // short
        assertEquals(1L, close.row("THB").variance)    // over by 0.01
    }

    @Test fun handoverIsWhatIsCountedAboveTheChangeAndNeverBelowZero() = runTest(UnconfinedTestDispatcher()) {
        val (_, close) = setup()
        close.count("130000", "400.00")
        assertEquals(30_000L, close.row("LAK").handover)
        assertFalse(close.row("LAK").belowFloor)
        assertEquals(0L, close.row("THB").handover)
        assertTrue(close.row("THB").belowFloor) // 400.00 counted, 500.00 must stay
        assertEquals(100_000L, close.row("LAK").float)
        assertEquals(50_000L, close.row("THB").float)
    }

    @Test fun aKipDrawerHasNoDecimalsAndAnEmptyCountIsZero() = runTest(UnconfinedTestDispatcher()) {
        val (_, close) = setup()
        close.setCounted("LAK", "130000.5")
        assertTrue(close.row("LAK").invalid)
        assertFalse(close.ui.value.canClose)
        close.setCounted("LAK", "")
        assertEquals(0L, close.row("LAK").counted)
        assertEquals(-130_000L, close.row("LAK").variance)
        close.setCounted("LAK", "0")
        assertFalse(close.row("LAK").invalid) // zero is a count
    }

    @Test fun typedTextIsNeverGuessedAt() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        for (typed in listOf("1,50", "-700", "x", "1.2.3")) {
            close.setCounted("THB", typed)
            assertTrue(close.row("THB").invalid, typed)
            close.confirm()
        }
        assertTrue(h.api.closes.isEmpty())
    }

    @Test fun aDifferenceNeedsANoteBeforeTheDayCanBeClosed() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        h.api.closeAnswer = closed
        close.count("129,500", "1500.25")
        assertTrue(close.ui.value.noteRequired)
        assertFalse(close.ui.value.canClose)
        close.confirm()
        assertTrue(h.api.closes.isEmpty())
        close.setNote("   ")
        assertFalse(close.ui.value.canClose)
        close.setNote("gave a customer too much change")
        assertTrue(close.ui.value.canClose)
    }

    @Test fun closingSendsEveryCurrencyInTheServersFormAndShowsTheServersFigures() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        h.api.closeAnswer = closed
        close.count("129,500", "1,500.25")
        close.setNote(" short ")
        h.api.current = null // after the close the day is not open any more
        h.api.todaysSessions = listOf(closed(CloseSessionRequest(emptyMap())))
        close.confirm()
        val (branch, id, request) = h.api.closes.single()
        assertEquals("main", branch)
        assertEquals("s1", id)
        assertEquals(CloseSessionRequest(mapOf("LAK" to "129500", "THB" to "1500.25"), "short"), request)
        val result = close.ui.value.result!!.close!!
        assertEquals("-500", result.variance["LAK"])
        assertEquals("29500", result.handover["LAK"])
        assertEquals("short", result.note)
        assertIs<ClosePhase.Closed>(close.ui.value.phase)
        assertNull(close.ui.value.session)
    }

    @Test fun aDayThatWasClosedByAnotherDeviceShowsItsResultAndTheRefusal() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        close.count("130000", "1500.25")
        h.api.closeError = ClientError.Api(409, ErrorCode.SESSION_CLOSED, "This day was closed already.", reason = ErrorReasons.SESSION_CLOSED)
        h.api.current = null
        h.api.todaysSessions = listOf(closed(CloseSessionRequest(mapOf("LAK" to "130000"), "")))
        close.confirm()
        assertEquals(Failure.Refused(ErrorCode.SESSION_CLOSED, ErrorReasons.SESSION_CLOSED), assertIs<ClosePhase.Failed>(close.ui.value.phase).failure)
        assertEquals("29500", close.ui.value.result!!.close!!.handover["LAK"])
    }

    @Test fun theServersRefusalOfTheCountIsShownAndTheFormKept() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        h.api.closeError = ClientError.Api(400, ErrorCode.INVALID_REQUEST, "Count the THB in the drawer too.", reason = ErrorReasons.CLOSE_COUNT_MISSING)
        close.count("130000", "1500.25")
        close.confirm()
        assertEquals(Failure.Refused(ErrorCode.INVALID_REQUEST, ErrorReasons.CLOSE_COUNT_MISSING), assertIs<ClosePhase.Failed>(close.ui.value.phase).failure)
        assertEquals("130000", close.ui.value.rows.first().text)
        assertTrue(close.ui.value.canClose) // can be tried again
    }

    @Test fun withoutTheDashboardThereIsNoPreviewAndTheServerJudgesTheCount() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.current = h.api.session("s1")
        h.api.previewError = ClientError.Api(403, ErrorCode.FORBIDDEN, "This needs the permission shoparchive.day.close.", reason = ErrorReasons.PERMISSION_MISSING)
        val close = CloseDayState(h.env, TodayState(h.env))
        close.count("1", "2")
        assertNull(close.row("LAK").expected)
        assertNull(close.row("LAK").variance)
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), close.ui.value.previewFailure)
        assertEquals(0L, close.row("LAK").handover) // 1 counted, 100000 change
        assertTrue(close.row("LAK").belowFloor)
        assertTrue(close.ui.value.canClose)
    }

    @Test fun theCountNeedsTheConnection() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        close.count("130000", "1500.25")
        h.canWrite.value = false
        assertEquals(CloseBlock.OFFLINE, close.ui.value.blocked)
        close.confirm()
        assertTrue(h.api.closes.isEmpty())
    }

    @Test fun withoutAnOpenDayThereIsNothingToClose() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val close = CloseDayState(h.env, TodayState(h.env))
        assertNull(close.ui.value.session)
        assertFalse(close.ui.value.canClose)
        assertNull(close.ui.value.result)
    }

    @Test fun aStaleDayIsClosedWithTheFiguresOfItsOwnDate() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.current = h.api.session("s0", date = "2026-10-03")
        val close = CloseDayState(h.env, TodayState(h.env))
        assertEquals("main" to "s0", h.api.previewCalls.last())
        assertEquals("s0", close.ui.value.session!!.id)
    }

    @Test fun theExpectedAmountFollowsLiveEntries() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        close.count("130000", "1500.25")
        h.api.previewAnswer = preview("140000", "1500.25")
        h.messages.tryEmit(EntryCreatedMessage("e9", "2026-10-04", "main"))
        assertEquals(140_000L, close.row("LAK").expected)
        assertEquals(-10_000L, close.row("LAK").variance)
    }

    @Test fun anExpectedAmountBelowZeroIsReadAsNegative() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        h.api.previewAnswer = preview("-500", "1500.25")
        h.refetch.tryEmit(Unit)
        close.count("0", "1500.25")
        assertEquals(-500L, close.row("LAK").expected)
        assertEquals(500L, close.row("LAK").variance)
    }

    @Test fun aNewOpenDayStartsWithAnEmptyForm() = runTest(UnconfinedTestDispatcher()) {
        val (h, close) = setup()
        close.count("1", "2")
        close.setNote("x")
        h.api.current = h.api.session("s2")
        h.refetch.tryEmit(Unit)
        assertEquals("", close.ui.value.note)
        assertEquals("", close.row("LAK").text)
    }
}

private object UserRefs {
    val alice = xyz.felismp.shoparchive.shared.UserRef("u1", "alice")
}
