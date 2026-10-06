package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.SessionDto

sealed interface DayStatus {
    data object NotOpened : DayStatus
    data class Open(val session: SessionDto) : DayStatus
    data class Closed(val session: SessionDto) : DayStatus
}

/**
 * What Today shows for [branch]. [date] is the business date the figures are for: the open day's, else today's.
 * [staleFrom] is set when the open day began before today (it was never closed). [currencies] is null when the dashboard was refused ([statsFailure]).
 */
data class TodayData(
    val branch: String,
    val date: String,
    val status: DayStatus,
    val staleFrom: String?,
    val currencies: List<DashboardCurrency>?,
    val recent: List<EntryDto>,
    val statsFailure: Failure?,
    /** False when the person may not see the dashboard: no totals or latest lines are asked for or shown. */
    val showStats: Boolean = true,
)

/** [loading] only before the first answer; a reload keeps showing the last [data]. [failure] is set when the day itself could not be read. */
data class TodayUi(val loading: Boolean = true, val data: TodayData? = null, val failure: Failure? = null)

/** Today: the day's status, the money and the latest lines of the chosen branch. Reloads on a branch change, a reconnect and live entry or day messages for the branch. */
class TodayState(private val env: Env) {
    private val _ui = MutableStateFlow(TodayUi())
    val ui: StateFlow<TodayUi> = _ui.asStateFlow()
    private val poke = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        env.scope.launch {
            merge(
                env.branch.filterNotNull().map { },
                env.refetch,
                // A change of rights (the config is read again) loses the totals or brings them in.
                env.config.map { it.capabilities().dashboard }.distinctUntilChanged().drop(1).map { }, // the first value is the state the first load already sees
                env.messages.filter { it.recordBranch() != null && it.recordBranch() == env.branch.value }.map { },
                poke,
            ).collectLatest { env.branch.value?.let { load(it) } }
        }
    }

    /** Asks for a new look now (after the person did something that changes the day). */
    fun reload() {
        poke.tryEmit(Unit)
    }

    private suspend fun load(branch: String) {
        // Totals the person may no longer see are dropped at once, not after the reload.
        val before = _ui.value.let { b ->
            if (env.can.dashboard || b.data?.showStats != true) b
            else b.copy(data = b.data.copy(currencies = null, recent = emptyList(), statsFailure = null, showStats = false))
        }
        _ui.value = if (before.data?.branch == branch) before.copy(failure = null) else TodayUi()
        try {
            val today = env.today().toString()
            val status = status(branch, today)
            val open = (status as? DayStatus.Open)?.session
            val date = open?.businessDate ?: today
            var stats: DashboardDto? = null
            var statsFailure: Failure? = null
            val showStats = env.can.dashboard
            try {
                if (showStats) stats = env.calls.run { env.api.dashboard(date, date, branch) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statsFailure = e.toFailure() // the status above still shows, e.g. when the user may not see the dashboard
            }
            _ui.value = TodayUi(
                loading = false,
                data = TodayData(
                    branch, date, status, staleFrom = open?.businessDate?.takeIf { it != today },
                    currencies = stats?.currencies, recent = stats?.recent.orEmpty(), statsFailure = statsFailure, showStats = showStats,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(loading = false, failure = e.toFailure())
        }
    }

    private suspend fun status(branch: String, today: String): DayStatus = try {
        DayStatus.Open(env.calls.run { env.api.currentSession(branch) })
    } catch (e: ClientError.Api) {
        if (e.code != ErrorCode.NO_OPEN_SESSION) throw e
        // No day is open: it may have been opened and closed today.
        val closed = try {
            env.calls.run { env.api.sessionsOn(branch, today) }.lastOrNull()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
        if (closed == null) DayStatus.NotOpened else DayStatus.Closed(closed)
    }
}
