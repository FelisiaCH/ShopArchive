package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.app.client.EntryFilter
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.DashboardTotals
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.NoteChangedMessage
import xyz.felismp.shoparchive.shared.NotesUnread
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.SayMessage
import xyz.felismp.shoparchive.shared.SessionStatus
import xyz.felismp.shoparchive.shared.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TodayStateTest {
    @Test fun aDayThatWasNeverOpenedSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val today = TodayState(h.env)
        val data = today.ui.value.data!!
        assertEquals(DayStatus.NotOpened, data.status)
        assertEquals("2026-10-04", data.date)
        assertEquals(Triple("2026-10-04", "2026-10-04", "main"), h.api.dashboardCalls.single())
        assertEquals(false, today.ui.value.loading)
    }

    @Test fun anOpenDayShowsWhoOpenedItAndTheFiguresOfItsDate() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.current = h.api.session("s1")
        val data = TodayState(h.env).ui.value.data!!
        assertEquals("alice", assertIs<DayStatus.Open>(data.status).session.openedBy.name)
        assertNull(data.staleFrom)
    }

    @Test fun anOldDayThatIsStillOpenIsFlaggedAndItsOwnDateIsShown() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.current = h.api.session("s0", date = "2026-10-03")
        val data = TodayState(h.env).ui.value.data!!
        assertEquals("2026-10-03", data.staleFrom)
        assertEquals("2026-10-03", data.date)
        assertEquals("2026-10-03", h.api.dashboardCalls.single().first)
    }

    @Test fun aDayOpenedAndClosedTodayIsClosed() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.todaysSessions = listOf(h.api.session("s1", status = SessionStatus.CLOSED))
        assertIs<DayStatus.Closed>(TodayState(h.env).ui.value.data!!.status)
    }

    @Test fun aServerThatCannotBeReachedIsAnErrorNotAnEmptyDay() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.currentError = ClientError.Unreachable()
        val ui = TodayState(h.env).ui.value
        assertEquals(Failure.Unreachable, ui.failure)
        assertNull(ui.data)
        assertEquals(false, ui.loading)
    }

    @Test fun refusedTotalsStillLeaveTheDayStatus() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.current = h.api.session("s1")
        h.api.dashboardError = ClientError.Api(403, ErrorCode.FORBIDDEN, "You may not see the dashboard.", reason = ErrorReasons.PERMISSION_MISSING)
        val data = TodayState(h.env).ui.value.data!!
        assertIs<DayStatus.Open>(data.status)
        assertNull(data.currencies)
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), data.statsFailure)
    }

    @Test fun nothingLoadsUntilABranchIsKnown() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, branch = null)
        val today = TodayState(h.env)
        assertTrue(today.ui.value.loading)
        assertTrue(h.api.dashboardCalls.isEmpty())
        h.branch.value = "main"
        assertEquals(1, h.api.dashboardCalls.size)
        assertNull(today.ui.value.failure)
    }

    @Test fun aReconnectLoadsAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val today = TodayState(h.env)
        assertEquals(DayStatus.NotOpened, today.ui.value.data!!.status)
        h.api.current = h.api.session("s1")
        h.refetch.tryEmit(Unit)
        assertIs<DayStatus.Open>(today.ui.value.data!!.status)
    }

    @Test fun liveEntryMessagesOfThisBranchLoadAgainButOthersDoNot() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        TodayState(h.env)
        assertEquals(1, h.api.dashboardCalls.size)
        h.messages.tryEmit(EntryCreatedMessage("e1", "2026-10-04", "main"))
        h.messages.tryEmit(EntryUpdatedMessage("e1", "2026-10-04", "main"))
        h.messages.tryEmit(EntryDeletedMessage("e1", "2026-10-04", "main"))
        assertEquals(4, h.api.dashboardCalls.size)
        h.messages.tryEmit(EntryCreatedMessage("e2", "2026-10-04", "other"))
        h.messages.tryEmit(SayMessage("hi", "server"))
        assertEquals(4, h.api.dashboardCalls.size)
    }

    @Test fun anotherBranchReplacesWhatWasShown() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val today = TodayState(h.env)
        h.api.currentError = ClientError.Unreachable()
        h.branch.value = "second"
        // The first branch's figures never stay on screen for the second.
        assertNull(today.ui.value.data)
        assertEquals(Failure.Unreachable, today.ui.value.failure)
        h.api.currentError = null
        h.refetch.tryEmit(Unit)
        assertEquals("second", today.ui.value.data!!.branch)
        assertEquals("second", h.api.dashboardCalls.last().third)
    }

    @Test fun losingTheDashboardPermissionDropsTheTotalsAndGainingItBringsThemBack() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.dashboard = DashboardDto("2026-10-04", "2026-10-04", listOf(DashboardCurrency("LAK", DashboardTotals("0", "0", "0", "0", "0", "0", 0), DashboardTotals("0", "0", "0", "0", "0", "0", 0), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())), listOf(testEntry()))
        val today = TodayState(h.env)
        assertNotNull(today.ui.value.data!!.currencies)
        assertEquals(1, today.ui.value.data!!.recent.size)
        val asked = h.api.dashboardCalls.size
        // The same branch, the config read again without the node.
        h.config.value = ConfigState.Ready(testConfig(permissions = ALL_NODES - xyz.felismp.shoparchive.shared.PermissionNodes.DASHBOARD_VIEW))
        val without = today.ui.value.data!!
        assertFalse(without.showStats)
        assertNull(without.currencies)
        assertTrue(without.recent.isEmpty())
        assertEquals(asked, h.api.dashboardCalls.size, "the dashboard is not asked for")
        h.config.value = ConfigState.Ready(testConfig())
        val back = today.ui.value.data!!
        assertTrue(back.showStats)
        assertEquals(1, back.currencies!!.size)
        assertEquals(asked + 1, h.api.dashboardCalls.size)
    }

    private fun staff(scope: kotlinx.coroutines.CoroutineScope) =
        Harness(scope, ConfigState.Ready(testConfig(permissions = ALL_NODES - PermissionNodes.DASHBOARD_VIEW)))

    @Test fun withoutTheDashboardTheirOwnEntriesOfTodayAreShownNewestFirst() = runTest(UnconfinedTestDispatcher()) {
        val h = staff(backgroundScope)
        val bob = UserRef("u2", "bob")
        h.api.listed = listOf(
            testEntry("a", createdAt = "2026-10-04T09:00:00+07:00"),
            testEntry("b", createdAt = "2026-10-04T10:00:00+07:00").copy(createdBy = bob),
            testEntry("c", createdAt = "2026-10-04T11:00:00+07:00"),
            testEntry("d", createdAt = "2026-10-04T11:00:00+07:00"),
        )
        val data = TodayState(h.env).ui.value.data!!
        assertFalse(data.showStats)
        assertNull(data.currencies)
        assertEquals(listOf("d", "c", "a"), data.mine!!.map { it.id })
        assertEquals(listOf(EntryFilter("2026-10-04", "2026-10-04", "main")), h.api.entryFilters)
        assertTrue(h.api.dashboardCalls.isEmpty(), "the dashboard is not asked for")
    }

    @Test fun withoutTheDashboardAndNothingRecordedTheListIsEmptyNotMissing() = runTest(UnconfinedTestDispatcher()) {
        val h = staff(backgroundScope)
        assertEquals(emptyList(), TodayState(h.env).ui.value.data!!.mine)
    }

    @Test fun withoutTheDashboardOrTheRightToViewEntriesNothingIsAsked() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(permissions = listOf(PermissionNodes.DAY_OPEN))))
        assertNull(TodayState(h.env).ui.value.data!!.mine)
        assertTrue(h.api.entryFilters.isEmpty())
        assertTrue(h.api.dashboardCalls.isEmpty())
    }

    @Test fun anEntriesFailureStillLeavesTheDayStatus() = runTest(UnconfinedTestDispatcher()) {
        val h = staff(backgroundScope)
        h.api.current = h.api.session("s1")
        h.api.listError = ClientError.Unreachable()
        val data = TodayState(h.env).ui.value.data!!
        assertIs<DayStatus.Open>(data.status)
        assertNull(data.mine)
        assertEquals(Failure.Unreachable, data.statsFailure)
    }

    @Test fun withTheDashboardTheEntriesAreNotAsked() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.listed = listOf(testEntry())
        val data = TodayState(h.env).ui.value.data!!
        assertTrue(data.showStats)
        assertNull(data.mine)
        assertTrue(h.api.entryFilters.isEmpty())
    }

    // ---- slips and notes ----

    @Test fun theDashboardsSlipCountIsKept() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.dashboard = DashboardDto("2026-10-04", "2026-10-04", emptyList(), emptyList(), slips = 7)
        assertEquals(7, TodayState(h.env).ui.value.data!!.slips)
    }

    @Test fun aHolderOfTheReadNodeGetsTheUnreadCountOfTheBranchAndItsOldestDayAndALiveNoteMessageAsksAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.unreadAnswer = NotesUnread(3, "2026-10-03")
        val today = TodayState(h.env)
        assertEquals(3, today.ui.value.data!!.unreadNotes)
        assertEquals("2026-10-03", today.ui.value.data!!.oldestUnreadNote)
        assertEquals(listOf("main"), h.api.unreadCalls)
        h.api.unreadAnswer = NotesUnread(4, "2026-10-03")
        h.messages.emit(NoteChangedMessage("market", "2026-10-04"))
        assertEquals(1, h.api.unreadCalls.size, "another branch")
        h.messages.emit(NoteChangedMessage("main", "2026-10-02"))
        assertEquals(4, today.ui.value.data!!.unreadNotes)
    }

    @Test fun theCountIsOfEveryDayNotOnlyTheOpenDaysDate() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.current = h.api.session("s0", date = "2026-10-03")
        h.api.unreadAnswer = NotesUnread(0)
        val data = TodayState(h.env).ui.value.data!!
        assertEquals(listOf("main"), h.api.unreadCalls)
        assertEquals(0, data.unreadNotes)
        assertNull(data.oldestUnreadNote)
    }

    @Test fun withoutTheReadNodeNoCountIsAskedForAndAFailedCountLeavesTheRestOfTodayAlone() = runTest(UnconfinedTestDispatcher()) {
        val plain = Harness(backgroundScope, ConfigState.Ready(testConfig(permissions = listOf(PermissionNodes.DASHBOARD_VIEW, PermissionNodes.NOTE_CREATE))))
        assertNull(TodayState(plain.env).ui.value.data!!.unreadNotes)
        assertTrue(plain.api.unreadCalls.isEmpty())

        val failing = Harness(backgroundScope)
        failing.api.unreadError = ClientError.Unreachable()
        val data = TodayState(failing.env).ui.value.data!!
        assertNull(data.unreadNotes)
        assertNull(data.statsFailure)
        assertTrue(data.showStats)
    }
}
