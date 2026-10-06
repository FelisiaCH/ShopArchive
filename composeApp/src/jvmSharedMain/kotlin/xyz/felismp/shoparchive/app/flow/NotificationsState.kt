package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.NotificationState
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionStatus
import java.time.LocalDate

/** How many messages the list asks for: the newest of the outbox. */
internal const val NOTIFICATION_LIST_LIMIT = 50

/** Whether a message may be put into the outbox again: only once it is sent or failed; the server refuses it while it is queued or unknown. */
fun NotificationDto.canResend(): Boolean = state == NotificationState.SENT || state == NotificationState.FAILED

/**
 * [items] are the newest messages (null before the first answer); [closedDays] the days of today and yesterday that are closed in the branch,
 * whose summary may be sent again; [failure] says why the list is not there; [actionFailure] why the last send-again was refused.
 * [working] holds the ids of what is being sent again right now (a message id or a day's session id).
 */
data class NotificationsUi(
    val loading: Boolean,
    val items: List<NotificationDto>?,
    val closedDays: List<SessionDto>,
    val failure: Failure?,
    val actionFailure: Failure?,
    val working: Set<String>,
    val canSend: Boolean,
    val blocked: Boolean,
) {
    private fun waiting(event: String, code: String) =
        items.orEmpty().any { it.event == event && it.code == code && (it.state == NotificationState.QUEUED || it.state == NotificationState.UNKNOWN) }

    /** Whether "send again" is offered for [n]: it is sent or failed, and no copy of it (or of another message with its code) is still waiting; the server refuses it then. */
    fun canResend(n: NotificationDto): Boolean = n.canResend() && !waiting(n.event, n.code)

    /** Whether the summary of the closed day [session] is already waiting in the outbox (queued or unknown), so sending it again is not offered. */
    fun dayWaiting(session: SessionDto): Boolean = waiting("day.closed", dayCode(session))
}

/** The code the server prints for the summary of a day (`20261003-A3F9C`): the business date and the last five characters of the session id. */
internal fun dayCode(session: SessionDto): String = session.businessDate.replace("-", "") + "-" + session.id.takeLast(5).uppercase()

/** Reports › Messages: what the outbox sent, is sending and could not send, and the buttons to send again. The server decides every state and every refusal. */
class NotificationsState(private val env: Env) {
    private val items = MutableStateFlow<List<NotificationDto>?>(null)
    private val closedDays = MutableStateFlow<List<SessionDto>>(emptyList())
    private val failure = MutableStateFlow<Failure?>(null)
    private val actionFailure = MutableStateFlow<Failure?>(null)
    private val working = MutableStateFlow<Set<String>>(emptySet())
    private val loading = MutableStateFlow(false)
    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<NotificationsUi> = _ui.asStateFlow()

    private fun snapshot() = NotificationsUi(
        loading.value, items.value, closedDays.value, failure.value, actionFailure.value, working.value,
        canSend = env.can.sendNotifications, blocked = !env.canWrite.value,
    )

    private fun publish() {
        _ui.value = snapshot()
    }

    init {
        env.scope.launch {
            merge(
                env.branch.map { },
                env.refetch,
                env.config.map { env.can.viewNotifications to env.can.sendNotifications }.distinctUntilChanged().drop(1).map { }, // the first value is what the first load sees
            ).collectLatest { load() }
        }
        env.scope.launch { env.canWrite.collect { publish() } }
    }

    /** Asks the server for the list again (when the tab is opened, after a send, and from the refresh button). */
    fun reload() {
        env.scope.launch { load() }
    }

    private suspend fun load() {
        // Without the permission nothing is asked (the server would only refuse) and nothing from before is kept.
        if (!env.can.viewNotifications) {
            items.value = null
            closedDays.value = emptyList()
            failure.value = null
            loading.value = false
            publish()
            return
        }
        loading.value = true
        publish()
        try {
            items.value = env.calls.run { env.api.notifications(NOTIFICATION_LIST_LIMIT) }
            failure.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            items.value = null
            failure.value = e.toFailure()
        }
        closedDays.value = loadClosedDays()
        loading.value = false
        publish()
    }

    /** The closed days of today and yesterday in the branch; empty without the right to send or when they cannot be read (the list above still works). */
    private suspend fun loadClosedDays(): List<SessionDto> {
        val branch = env.branch.value
        if (!env.can.sendNotifications || branch == null) return emptyList()
        val today: LocalDate = env.today()
        return try {
            listOf(today, today.minusDays(1)).flatMap { day -> env.calls.run { env.api.sessionsOn(branch, day.toString()) } }
                .filter { it.status == SessionStatus.CLOSED && it.close != null }.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Sends the message [id] again; refused by the server while it is still queued or unknown. */
    fun resend(id: String) = act(id) { env.api.resendNotification(id) }

    /** Sends the summary of the closed day [session] again. */
    fun sendDay(session: SessionDto) = act(session.id) { env.api.notifyDay(session.branch, session.id) }

    private fun act(key: String, call: suspend () -> Unit) {
        if (!env.can.sendNotifications || !env.canWrite.value || key in working.value) return
        working.value = working.value + key
        actionFailure.value = null
        publish()
        env.scope.launch {
            try {
                env.calls.run { call() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionFailure.value = e.toFailure()
            }
            working.value = working.value - key
            load()
        }
    }
}
