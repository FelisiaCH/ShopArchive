package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.NotificationState
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.SessionClose
import xyz.felismp.shoparchive.shared.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsStateTest {
    private fun note(id: String, state: NotificationState) =
        NotificationDto(id, "20261004-AAAAA", "day.closed", state, "main", "alice", "2026-10-04T20:00:00+07:00", 1, 0, text = "x")

    private fun harness(scope: kotlinx.coroutines.CoroutineScope, permissions: List<String> = ALL_NODES) =
        Harness(scope, ConfigState.Ready(testConfig(permissions = permissions))).also {
            it.api.notificationList = listOf(note("a", NotificationState.SENT), note("b", NotificationState.UNKNOWN), note("c", NotificationState.FAILED), note("d", NotificationState.QUEUED))
        }

    @Test fun listsTheMessagesAndOnlySentAndFailedOnesMayBeSentAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotificationsState(h.env)
        assertEquals(listOf("a", "b", "c", "d"), state.ui.value.items!!.map { it.id })
        assertEquals(listOf(true, false, true, false), state.ui.value.items!!.map { it.canResend() })
        assertEquals(listOf(50), h.api.notificationCalls)
    }

    @Test fun withoutTheViewNodeNothingIsAskedAndNothingKept() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope, permissions = listOf(PermissionNodes.DASHBOARD_VIEW))
        val state = NotificationsState(h.env)
        assertTrue(h.api.notificationCalls.isEmpty())
        assertNull(state.ui.value.items)
        assertEquals(emptyList(), Capabilities(setOf(PermissionNodes.DASHBOARD_VIEW)).reportsTabs.filter { it == ReportsTab.NOTIFICATIONS })
        assertEquals(listOf(ReportsTab.NOTIFICATIONS), Capabilities(setOf(PermissionNodes.NOTIFICATIONS_VIEW)).reportsTabs)
    }

    @Test fun sendAgainNeedsTheSendNode() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope, permissions = listOf(PermissionNodes.NOTIFICATIONS_VIEW))
        val state = NotificationsState(h.env)
        assertFalse(state.ui.value.canSend)
        state.resend("a")
        assertTrue(h.api.resends.isEmpty())
    }

    @Test fun sendingAgainCallsTheServerAndReloads() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotificationsState(h.env)
        state.resend("a")
        assertEquals(listOf("a"), h.api.resends)
        assertEquals(2, h.api.notificationCalls.size)
        assertNull(state.ui.value.actionFailure)
        assertTrue(state.ui.value.working.isEmpty())
    }

    @Test fun aPendingRefusalIsKeptAsTheServersReasonForTheScreenToWord() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.resendError = ClientError.Api(409, ErrorCode.CONFLICT, "pending", reason = ErrorReasons.NOTIFICATION_PENDING)
        val state = NotificationsState(h.env)
        state.resend("d")
        val failure = assertIs<Failure.Refused>(state.ui.value.actionFailure)
        assertEquals(ErrorReasons.NOTIFICATION_PENDING, failure.reason)
    }

    @Test fun closedDaysOfTodayCanHaveTheirSummarySentAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val closed = h.api.session("s-closed", status = SessionStatus.CLOSED).let {
            it.copy(close = SessionClose(mapOf("LAK" to "1"), mapOf("LAK" to "1"), mapOf("LAK" to "0"), mapOf("LAK" to "1"), emptyList(), "", h.api.alice, "2026-10-04T20:00:00+07:00"))
        }
        h.api.todaysSessions = listOf(closed, h.api.session("s-open"))
        val state = NotificationsState(h.env)
        assertEquals(listOf(closed.id), state.ui.value.closedDays.map { it.id })
        state.sendDay(closed)
        assertEquals(listOf("main" to closed.id), h.api.dayNotifies)
    }

    @Test fun sendAgainIsNotOfferedWhileACopyIsWaitingAndNeitherIsTheDaySummary() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val closed = h.api.session("s-12345", status = SessionStatus.CLOSED).copy(close = SessionClose(mapOf("LAK" to "1"), mapOf("LAK" to "1"), mapOf("LAK" to "0"), mapOf("LAK" to "1"), emptyList(), "", h.api.alice, "t"))
        h.api.todaysSessions = listOf(closed)
        h.api.notificationList = listOf(
            note("a", NotificationState.SENT).copy(code = "20261004-12345"),
            note("b", NotificationState.QUEUED).copy(code = "20261004-12345", resendOf = "a"),
            note("c", NotificationState.SENT).copy(code = "20261004-OTHER"),
        )
        val ui = NotificationsState(h.env).ui.value
        assertEquals(listOf(false, false, true), ui.items!!.map { ui.canResend(it) })
        assertTrue(ui.dayWaiting(closed))
    }

    @Test fun offlineBlocksSendingAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotificationsState(h.env)
        h.canWrite.value = false
        assertTrue(state.ui.value.blocked)
        state.resend("a")
        assertTrue(h.api.resends.isEmpty())
    }

    @Test fun aFailedListShowsTheFailureNotAnEmptyList() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.notificationsError = ClientError.Unreachable()
        val state = NotificationsState(h.env)
        assertEquals(Failure.Unreachable, state.ui.value.failure)
        assertNull(state.ui.value.items)
    }
}
