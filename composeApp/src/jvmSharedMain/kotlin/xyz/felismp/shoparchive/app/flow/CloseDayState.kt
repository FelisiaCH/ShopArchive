package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryMovedMessage
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.closeFigures
import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.parseAmount

sealed interface ClosePhase {
    data object Idle : ClosePhase
    data object Saving : ClosePhase

    /** The day is closed; [session] carries what the server worked out (`close`). */
    data class Closed(val session: SessionDto) : ClosePhase
    data class Failed(val failure: Failure) : ClosePhase
}

enum class CloseBlock { LOADING, NO_BRANCH, OFFLINE }

/**
 * One currency of the drawer, in minor units. [expected] and [variance] are null while the preview is not there (the server's preview was refused or has not answered);
 * [counted] is null while the typed text is not an amount ([invalid]). [belowFloor]: less is in the drawer than the change that must stay, so the handover is 0.
 */
class CloseRow(
    val currency: CurrencyInfo,
    val text: String,
    val float: Long,
    val counted: Long?,
    val invalid: Boolean,
    val expected: Long?,
    val variance: Long?,
    val handover: Long?,
    val belowFloor: Boolean,
)

/**
 * [session] is the open day being closed, null when there is none. [result] is how a closed day ended: the one just closed here, or the
 * day Today shows as closed. [previewFailure] is set when the expected amounts could not be worked out; the server then judges the count.
 */
class CloseUi(
    val session: SessionDto?,
    val rows: List<CloseRow>,
    val note: String,
    val noteRequired: Boolean,
    val previewFailure: Failure?,
    val phase: ClosePhase,
    val result: SessionDto?,
    val blocked: CloseBlock?,
) {
    val canClose: Boolean get() = session != null && blocked == null && phase !is ClosePhase.Saving && phase !is ClosePhase.Closed &&
        rows.none { it.invalid } && (!noteRequired || note.isNotBlank())
}

/**
 * Close day: all the money in the drawer counted per currency, compared with what should be there (the change + cash in - cash out of the day,
 * asked of the server's preview, which counts the day's session like the close does; the server's figures at the close are the final ones), then closed. Does not lock edits.
 */
class CloseDayState(private val env: Env, private val today: TodayState) {
    private class Form(val counted: Map<String, String> = emptyMap(), val note: String = "", val phase: ClosePhase = ClosePhase.Idle)

    /** What the server says the drawer should hold, per currency (decimal strings); the failure when it could not say. */
    private class Preview(val sessionId: String, val expected: Map<String, String>?, val failure: Failure?)

    private val form = MutableStateFlow(Form())
    private val preview = MutableStateFlow<Preview?>(null)

    private fun openSession(day: TodayUi): SessionDto? =
        day.data?.takeIf { it.branch == env.branch.value }?.status.let { (it as? DayStatus.Open)?.session }

    val ui: StateFlow<CloseUi> = combine(form, preview, today.ui, env.config, env.canWrite) { f, pv, day, config, canWrite ->
        val ready = (config as? ConfigState.Ready)?.config
        val session = openSession(day)
        val cash = pv?.takeIf { it.sessionId == session?.id }
        val rows = ready?.currencies.orEmpty().map { c ->
            val float = session?.float?.get(c.code)?.let { parseAmount(it, c.exponent, allowZero = true) } ?: 0L
            val text = f.counted[c.code].orEmpty()
            val counted = if (text.isBlank()) 0L else parseTypedAmount(text, c.exponent, allowZero = true)
            val figures = counted?.let { n -> closeFigures(float, 0, 0, n) }
            val expected = cash?.expected?.get(c.code)?.let { parseSignedTotal(it, c.exponent) }
            CloseRow(c, text, float, counted, text.isNotBlank() && counted == null, expected, if (counted != null && expected != null) counted - expected else null, figures?.handover, figures?.belowFloor == true)
        }
        val closed = (day.data?.takeIf { it.branch == env.branch.value }?.status as? DayStatus.Closed)?.session?.takeIf { it.close != null }
        CloseUi(
            session, rows, f.note, noteRequired = rows.any { (it.variance ?: 0L) != 0L }, previewFailure = cash?.failure,
            phase = f.phase, result = (f.phase as? ClosePhase.Closed)?.session ?: closed,
            blocked = when {
                ready == null -> CloseBlock.LOADING
                env.branch.value == null -> CloseBlock.NO_BRANCH
                !canWrite -> CloseBlock.OFFLINE
                else -> null
            },
        )
    }.stateIn(env.scope, SharingStarted.Eagerly, CloseUi(null, emptyList(), "", false, null, ClosePhase.Idle, null, CloseBlock.LOADING))

    init {
        env.scope.launch {
            merge(
                today.ui.map { openSession(it)?.id }.distinctUntilChanged().map { },
                env.refetch,
                env.messages.filter {
                    (it is EntryCreatedMessage || it is EntryUpdatedMessage || it is EntryDeletedMessage || it is EntryMovedMessage) && it.recordBranch() == env.branch.value
                }.map { },
            ).collectLatest { loadPreview() }
        }
        // A new open day (or another branch) is another drawer.
        env.scope.launch { today.ui.map { openSession(it)?.id }.distinctUntilChanged().filterNotNull().collect { reset() } }
        env.scope.launch { env.branch.drop(1).collect { reset() } }
    }

    private suspend fun loadPreview() {
        if (!env.can.closeDay) return // the server would refuse the preview
        val session = openSession(today.ui.value) ?: return
        preview.value = try {
            Preview(session.id, env.calls.run { env.api.sessionPreview(session.branch, session.id) }.expected, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Preview(session.id, null, e.toFailure())
        }
    }

    fun setCounted(code: String, text: String) {
        val f = form.value
        if (f.phase is ClosePhase.Saving || f.phase is ClosePhase.Closed) return
        form.value = Form(f.counted + (code to text), f.note)
    }

    fun setNote(text: String) {
        val f = form.value
        if (f.phase is ClosePhase.Saving || f.phase is ClosePhase.Closed) return
        form.value = Form(f.counted, text)
    }

    /** Empties the form (after leaving the screen, a new day or another branch). */
    fun reset() {
        if (form.value.phase !is ClosePhase.Saving) form.value = Form()
    }

    fun confirm() {
        val state = ui.value
        val session = state.session ?: return
        if (!state.canClose) return
        val counted = state.rows.associate { it.currency.code to formatAmount(it.counted ?: return, it.currency.exponent) }
        val f = form.value
        form.value = Form(f.counted, f.note, ClosePhase.Saving)
        env.scope.launch {
            val phase = try {
                ClosePhase.Closed(env.calls.run { env.api.closeSession(session.branch, session.id, CloseSessionRequest(counted, f.note.trim())) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClosePhase.Failed(e.toFailure())
            }
            form.value = Form(f.counted, f.note, phase)
            today.reload() // closed here, or already closed elsewhere: Today tells which
        }
    }
}
