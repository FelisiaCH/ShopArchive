package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.app.client.EntryFilter
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryMovedMessage
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.isIsoDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Why a typed date range cannot be asked for. */
enum class RangeProblem { NOT_A_DATE, BACKWARDS, TOO_LONG }

/** The most days (counting both ends) the server answers for at once. */
const val MAX_RANGE_DAYS = 366

/** The range [from]..[to] typed as `yyyy-MM-dd`, or why it is not one the server takes. */
fun checkRange(from: String, to: String): RangeProblem? {
    if (!isIsoDate(from.trim()) || !isIsoDate(to.trim())) return RangeProblem.NOT_A_DATE
    val days = ChronoUnit.DAYS.between(LocalDate.parse(from.trim()), LocalDate.parse(to.trim()))
    return when {
        days < 0 -> RangeProblem.BACKWARDS
        days >= MAX_RANGE_DAYS -> RangeProblem.TOO_LONG
        else -> null
    }
}

/** [loading] is true while a (re)load runs; the list stays on screen meanwhile. [entries] is newest first and null before the first answer. */
data class HistoryUi(
    val from: String,
    val to: String,
    val rangeProblem: RangeProblem?,
    val branch: String?,
    val type: EntryType?,
    val currency: String?,
    val category: String?,
    val includeDeleted: Boolean,
    val loading: Boolean = true,
    val entries: List<EntryDto>? = null,
    val failure: Failure? = null,
)

/** History: the entries of a date range, filtered, and the entry being looked at. Reloads on a filter change, a reconnect and any live entry message. */
class HistoryState(private val env: Env, private val compressor: SlipCompressor) {
    private class Form(
        val from: String, val to: String, val branch: String? = null, val type: EntryType? = null,
        val currency: String? = null, val category: String? = null, val includeDeleted: Boolean = false,
    ) {
        fun copy(
            from: String = this.from, to: String = this.to, branch: String? = this.branch, type: EntryType? = this.type,
            currency: String? = this.currency, category: String? = this.category, includeDeleted: Boolean = this.includeDeleted,
        ) = Form(from, to, branch, type, currency, category, includeDeleted)

        /** Null while the range is not one the server takes. */
        fun filter(): EntryFilter? =
            if (checkRange(from, to) != null) null else EntryFilter(from.trim(), to.trim(), branch, type, currency, category, includeDeleted)
    }

    private val month = env.today().let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
    private val form = MutableStateFlow(Form(month.first.toString(), month.second.toString()))
    private val result = MutableStateFlow<Triple<Boolean, List<EntryDto>?, Failure?>>(Triple(true, null, null))
    private val _detail = MutableStateFlow<EntryDetailState?>(null)

    /** The entry opened for reading or changing, null when the list shows. */
    val detail: StateFlow<EntryDetailState?> = _detail.asStateFlow()

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<HistoryUi> = _ui.asStateFlow()

    init {
        env.scope.launch {
            merge(
                form.map { it.filter() }.distinctUntilChanged().map { },
                // History is hidden without view access: nothing is asked for until the config grants it, and then it is.
                env.config.map { it.capabilities().viewEntries }.distinctUntilChanged().drop(1).map { }, // a change only: the first load comes from the form
                env.refetch,
                env.messages.filter { it is EntryCreatedMessage || it is EntryUpdatedMessage || it is EntryDeletedMessage || it is EntryMovedMessage }.map { },
            ).collectLatest { load() }
        }
    }

    private fun snapshot(): HistoryUi {
        val f = form.value
        val (loading, entries, failure) = result.value
        return HistoryUi(f.from, f.to, checkRange(f.from, f.to), f.branch, f.type, f.currency, f.category, f.includeDeleted, loading, entries, failure)
    }

    private fun publish() {
        _ui.value = snapshot()
    }

    private suspend fun load() {
        val filter = form.value.filter()
        if (!env.can.viewEntries) {
            result.value = Triple(false, result.value.second, null)
            publish()
            return
        }
        if (filter == null) {
            result.value = Triple(false, result.value.second, null)
            publish()
            return
        }
        result.value = Triple(true, result.value.second, null)
        publish()
        try {
            val entries = env.calls.run { env.api.entries(filter) }.sortedWith(compareByDescending<EntryDto> { it.date }.thenByDescending { it.createdAt }.thenByDescending { it.id })
            result.value = Triple(false, entries, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.value = Triple(false, result.value.second, e.toFailure())
        }
        publish()
    }

    /** Asks again with the same filters (after a failure). */
    fun reload() {
        env.scope.launch { load() }
    }

    private fun change(next: (Form) -> Form) {
        form.value = next(form.value)
        publish()
    }

    fun setFrom(text: String) = change { it.copy(from = text) }
    fun setTo(text: String) = change { it.copy(to = text) }
    fun setBranch(key: String?) = change { it.copy(branch = key) }
    fun setType(type: EntryType?) = change { it.copy(type = type) }
    fun setCurrency(code: String?) = change { it.copy(currency = code) }
    fun setCategory(key: String?) = change { it.copy(category = key) }
    fun setIncludeDeleted(on: Boolean) = change { it.copy(includeDeleted = on) }

    /** Opens [entry] (a row of the list). */
    fun open(entry: EntryDto) {
        _detail.value?.close()
        _detail.value = EntryDetailState(env, entry, compressor) { reload() }
    }

    fun closeDetail() {
        _detail.value?.close()
        _detail.value = null
    }
}
