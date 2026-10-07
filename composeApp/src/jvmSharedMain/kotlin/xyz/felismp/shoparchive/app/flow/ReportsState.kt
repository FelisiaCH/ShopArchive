package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.app.client.ExportFile
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryMovedMessage
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class ReportRange { TODAY, WEEK, MONTH, CUSTOM }

enum class ReportsTab { OVERVIEW, CLOSE_DAY, EXPORT, NOTIFICATIONS }

/** The dates of a quick range ending today: this week starts on Monday, this month on the 1st. */
internal fun rangeDates(range: ReportRange, today: LocalDate): Pair<LocalDate, LocalDate>? = when (range) {
    ReportRange.TODAY -> today to today
    ReportRange.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
    ReportRange.MONTH -> today.withDayOfMonth(1) to today
    ReportRange.CUSTOM -> null
}

/** [now] minus [before] in minor units of a currency with [exponent] decimals; signed totals are read as signed. */
internal fun totalsDelta(now: String, before: String, exponent: Int): Long? {
    val a = parseSignedTotal(now, exponent) ?: return null
    val b = parseSignedTotal(before, exponent) ?: return null
    return a - b
}

/** [data] is null before the first answer, or when [failure] says why the dashboard is not there (the server's own words when it refused). */
data class ReportsUi(
    val range: ReportRange,
    val from: String,
    val to: String,
    val rangeProblem: RangeProblem?,
    val branch: String?,
    val currency: String?,
    val loading: Boolean = true,
    val data: DashboardDto? = null,
    val failure: Failure? = null,
) {
    /** The currencies to show: all, or just the one chosen. */
    val shown: List<DashboardCurrency> get() = data?.currencies.orEmpty().filter { currency == null || it.currency == currency }
}

/** Reports › Overview: the dashboard of a range and branch, per currency, as tables. */
class ReportsState(private val env: Env) {
    private class Form(val range: ReportRange, val from: String, val to: String, val branch: String?, val currency: String?)

    private val start = env.today().let { Form(ReportRange.TODAY, it.toString(), it.toString(), null, null) }
    private val form = MutableStateFlow(start)
    private val answer = MutableStateFlow<Triple<Boolean, DashboardDto?, Failure?>>(Triple(true, null, null))
    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<ReportsUi> = _ui.asStateFlow()

    private fun branchOf(f: Form) = f.branch ?: env.branch.value

    private fun snapshot(): ReportsUi {
        val f = form.value
        val (loading, data, failure) = answer.value
        return ReportsUi(f.range, f.from, f.to, checkRange(f.from, f.to), branchOf(f), f.currency, loading, data, failure)
    }

    init {
        env.scope.launch {
            merge(
                combine(form, env.branch) { f, _ -> Triple(checkRange(f.from, f.to) == null, branchOf(f), f.from.trim() to f.to.trim()) }.distinctUntilChanged().map { },
                env.refetch,
                // A change of rights (the config is read again) loses the figures or brings them in.
                env.config.map { it.capabilities().dashboard }.distinctUntilChanged().drop(1).map { }, // the first value is the state the first load already sees
                env.messages.filter {
                    (it is EntryCreatedMessage || it is EntryUpdatedMessage || it is EntryDeletedMessage || it is EntryMovedMessage) && it.recordBranch() == branchOf(form.value)
                }.map { },
            ).collectLatest { load() }
        }
    }

    private suspend fun load() {
        val f = form.value
        val branch = branchOf(f)
        // Without the dashboard permission nothing is asked (the server would only refuse) and nothing from before is kept.
        if (!env.can.dashboard) {
            answer.value = Triple(false, null, null)
            _ui.value = snapshot()
            return
        }
        if (branch == null || checkRange(f.from, f.to) != null) {
            answer.value = Triple(false, answer.value.second, null)
            _ui.value = snapshot()
            return
        }
        answer.value = Triple(true, answer.value.second, null)
        _ui.value = snapshot()
        answer.value = try {
            Triple(false, env.calls.run { env.api.dashboard(f.from.trim(), f.to.trim(), branch) }, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Triple(false, null, e.toFailure())
        }
        _ui.value = snapshot()
    }

    fun reload() {
        env.scope.launch { load() }
    }

    /** [now] minus [before], two totals of [currency] as the server sent them (they may be negative), in minor units; null if either is not a number. */
    fun delta(currency: String, now: String, before: String): Long? {
        val exponent = (env.config.value as? ConfigState.Ready)?.config?.currencies?.firstOrNull { it.code == currency }?.exponent ?: return null
        return totalsDelta(now, before, exponent)
    }

    private fun change(next: (Form) -> Form) {
        form.value = next(form.value)
        _ui.value = snapshot()
    }

    fun selectRange(range: ReportRange) = change { f ->
        val dates = rangeDates(range, env.today())
        if (dates == null) Form(range, f.from, f.to, f.branch, f.currency) else Form(range, dates.first.toString(), dates.second.toString(), f.branch, f.currency)
    }

    fun setFrom(text: String) = change { Form(ReportRange.CUSTOM, text, it.to, it.branch, it.currency) }
    fun setTo(text: String) = change { Form(ReportRange.CUSTOM, it.from, text, it.branch, it.currency) }
    fun setBranch(key: String) = change { Form(it.range, it.from, it.to, key, it.currency) }
    fun setCurrency(code: String?) = change { Form(it.range, it.from, it.to, it.branch, code) }
}

enum class SaveResult { SAVED, CANCELLED, FAILED }

sealed interface ExportPhase {
    data object Idle : ExportPhase
    data object Working : ExportPhase

    /** The file is made; the screen hands it to the platform's save dialog and tells [ExportState.delivered] how that went. */
    data class Ready(val file: ExportFile) : ExportPhase
    data class Failed(val failure: Failure) : ExportPhase
}

data class ExportUi(
    val from: String,
    val to: String,
    val rangeProblem: RangeProblem?,
    val branch: String?,
    /** `csv` or `xlsx`. */
    val format: String,
    val phase: ExportPhase = ExportPhase.Idle,
    /** How the last hand-over of the file went; null before one. */
    val saved: SaveResult? = null,
    val blocked: Boolean = false,
) {
    val canExport: Boolean get() = branch != null && rangeProblem == null && !blocked && phase !is ExportPhase.Working
}

/** Reports › Export: a CSV or XLSX of a range and branch, then saved where the person chooses. */
class ExportState(private val env: Env) {
    private class Form(val from: String, val to: String, val branch: String?, val format: String, val phase: ExportPhase = ExportPhase.Idle, val saved: SaveResult? = null)

    private val form = MutableStateFlow(env.today().let { Form(it.withDayOfMonth(1).toString(), it.toString(), null, "csv") })
    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<ExportUi> = _ui.asStateFlow()

    private fun snapshot(): ExportUi {
        val f = form.value
        return ExportUi(f.from, f.to, checkRange(f.from, f.to), f.branch ?: env.branch.value, f.format, f.phase, f.saved, blocked = !env.canWrite.value)
    }

    init {
        // The branch and the connection are the shell's; follow them.
        env.scope.launch { env.branch.collect { _ui.value = snapshot() } }
        env.scope.launch { env.canWrite.collect { _ui.value = snapshot() } }
    }

    private fun ExportPhase.settled() = if (this is ExportPhase.Working) this else ExportPhase.Idle

    private fun change(next: (Form) -> Form) {
        form.value = next(form.value)
        _ui.value = snapshot()
    }

    fun setFrom(text: String) = change { Form(text, it.to, it.branch, it.format, it.phase.settled()) }
    fun setTo(text: String) = change { Form(it.from, text, it.branch, it.format, it.phase.settled()) }
    fun setBranch(key: String) = change { Form(it.from, it.to, key, it.format, it.phase.settled()) }
    fun setFormat(format: String) = change { Form(it.from, it.to, it.branch, format, it.phase.settled()) }

    fun export() {
        if (!ui.value.canExport) return
        val f = form.value
        val branch = _ui.value.branch ?: return
        change { Form(f.from, f.to, f.branch, f.format, ExportPhase.Working) }
        env.scope.launch {
            val phase = try {
                ExportPhase.Ready(env.calls.run { env.api.export(f.from.trim(), f.to.trim(), branch, f.format) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportPhase.Failed(e.toFailure())
            }
            change { Form(it.from, it.to, it.branch, it.format, phase) }
        }
    }

    /** The platform's save dialog was used for the ready file. */
    fun delivered(result: SaveResult) = change { Form(it.from, it.to, it.branch, it.format, it.phase, result) }
}
