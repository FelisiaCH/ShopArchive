package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.DashboardTotals
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.SayMessage
import xyz.felismp.shoparchive.shared.SessionStatus
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
}
