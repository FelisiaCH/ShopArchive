package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryMovedMessage
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.RecordsPolicy
import xyz.felismp.shoparchive.shared.SlipDto
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import xyz.felismp.shoparchive.shared.covers
import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.isIsoDate

/** Something fetched on its own: a slip image or the history. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val failure: Failure) : Load<Nothing>
}

/** What the last change (edit, delete, move) is doing. [Failed] carries the server's own reason. */
sealed interface ActionPhase {
    data object Idle : ActionPhase
    data object Working : ActionPhase
    data class Failed(val failure: Failure) : ActionPhase
}

/** The entry being changed: [keep] are the slips already stored that stay, [added] are new photos. */
class EditForm(
    val type: EntryType,
    val category: String?,
    val item: String,
    val note: String,
    val tenders: List<TenderRow>,
    val keep: List<SlipDto>,
    val added: List<SlipItem>,
    /** The `updatedAt` of the entry this form started from; the server refuses the change if the entry has moved on since. */
    val base: String,
)

class EditUi(
    val form: EditForm,
    val currencies: List<CurrencyInfo>,
    val categories: List<CategoryDto>,
    val policy: RecordsPolicy,
    val badRows: Set<Int>,
    val issues: List<RecordIssue>,
    val notice: SlipNotice?,
    val compressing: Boolean,
    /** The entry was changed (on another device) after this form started: what is typed stays for re-entering, but it cannot be sent over the newer entry. */
    val stale: Boolean,
) {
    val online: Boolean get() = form.tenders.any { it.method == TenderMethod.ONLINE }
}

class DetailUi(
    val entry: EntryDto,
    /** By slip number. */
    val slips: Map<Int, Load<ByteArray>>,
    val history: Load<List<HistoryItem>>,
    val action: ActionPhase,
    val confirmDelete: Boolean,
    /** The date typed for a move, or null when no move is being asked for. */
    val moveText: String?,
    val edit: EditUi?,
    val canWrite: Boolean,
    /** Whether the person may edit and move this entry (the server's own/all nodes and edit window); the buttons are left out otherwise. */
    val canEdit: Boolean,
    val canDelete: Boolean,
) {
    val moveValid: Boolean get() = moveText != null && isIsoDate(moveText.trim())
}

/** The number in a slip's file name (`slip-3.jpg` is 3). */
internal fun slipNumber(file: String): Int? = Regex("slip-([0-9]+)\\.").find(file)?.groupValues?.get(1)?.toIntOrNull()

/**
 * One entry of History: its slips and history, and changing it (edit, delete, move). Every change goes through the server's rules
 * (own or all, edit window, PIN again); a refusal is shown with the server's words. Lives until [close].
 */
class EntryDetailState(
    private val env: Env,
    first: EntryDto,
    private val compressor: SlipCompressor,
    private val changed: () -> Unit,
) {
    private val job = SupervisorJob(env.scope.coroutineContext[Job])
    private val scope = CoroutineScope(env.scope.coroutineContext + job)

    private class Inner(
        val entry: EntryDto,
        val slips: Map<Int, Load<ByteArray>> = emptyMap(),
        val history: Load<List<HistoryItem>> = Load.Loading,
        val action: ActionPhase = ActionPhase.Idle,
        val confirmDelete: Boolean = false,
        val moveText: String? = null,
        val edit: EditForm? = null,
        val notice: SlipNotice? = null,
        val compressing: Int = 0,
    ) {
        fun copy(
            entry: EntryDto = this.entry, slips: Map<Int, Load<ByteArray>> = this.slips, history: Load<List<HistoryItem>> = this.history,
            action: ActionPhase = this.action, confirmDelete: Boolean = this.confirmDelete, moveText: String? = this.moveText,
            edit: EditForm? = this.edit, notice: SlipNotice? = this.notice, compressing: Int = this.compressing,
        ) = Inner(entry, slips, history, action, confirmDelete, moveText, edit, notice, compressing)
    }

    private val inner = MutableStateFlow(Inner(first))
    private var keys = 0
    private var loading: Job? = null

    val ui: StateFlow<DetailUi> = combine(inner, env.config, env.canWrite) { s, config, canWrite ->
        val ready = (config as? ConfigState.Ready)?.config
        val policy = ready?.records ?: RecordsPolicy()
        val exponents = ready?.currencies.orEmpty().associate { it.code to it.exponent }
        val edit = s.edit?.let { f ->
            val bad = f.tenders.filter { it.amount.isNotEmpty() && exponents[it.currency]?.let { e -> parseTypedAmount(it.amount, e) } == null }.map { it.key }.toSet()
            val online = f.tenders.any { it.method == TenderMethod.ONLINE }
            val slipCount = f.keep.size + f.added.size
            val issues = buildList {
                if (f.tenders.any { it.amount.isEmpty() }) add(RecordIssue.AMOUNT_MISSING)
                if (bad.isNotEmpty()) add(RecordIssue.AMOUNT_INVALID)
                if (online && slipCount == 0) add(RecordIssue.SLIP_NEEDED)
                if (!online && slipCount > 0) add(RecordIssue.SLIP_NOT_ALLOWED)
                if (slipCount > policy.slipMaxCount) add(RecordIssue.TOO_MANY_SLIPS)
                if (policy.requireCategory && f.category == null) add(RecordIssue.CATEGORY_NEEDED)
            }
            // A category the entry already has stays choosable even if it was archived or retyped since.
            val categories = ready?.categories.orEmpty().filter { (!it.archived && it.appliesTo.covers(f.type)) || it.key == s.entry.category }
            EditUi(f, ready?.currencies.orEmpty(), categories, policy, bad, issues, s.notice, s.compressing > 0, stale = f.base != s.entry.updatedAt)
        }
        val can = config.capabilities()
        DetailUi(
            s.entry, s.slips, s.history, s.action, s.confirmDelete, s.moveText, edit, canWrite,
            can.canEdit(s.entry, env.today()), can.canDelete(s.entry, env.today()),
        )
    }.stateIn(
        scope, SharingStarted.Eagerly,
        env.config.value.capabilities().let { DetailUi(first, emptyMap(), Load.Loading, ActionPhase.Idle, false, null, null, env.canWrite.value, it.canEdit(first, env.today()), it.canDelete(first, env.today())) },
    )

    init {
        loadParts(first)
        scope.launch {
            env.messages.filter { m ->
                (m is EntryUpdatedMessage && m.id == first.id) || (m is EntryDeletedMessage && m.id == first.id) || (m is EntryMovedMessage && m.id == first.id)
            }.collect { m -> refresh((m as? EntryMovedMessage)?.date) }
        }
        scope.launch { env.refetch.collect { refresh(null) } }
    }

    /** Stops everything this entry's screen was doing. */
    fun close() {
        job.cancel()
    }

    private fun loadParts(entry: EntryDto) {
        loading?.cancel()
        inner.update { it.copy(slips = entry.slips.mapNotNull { s -> slipNumber(s.file) }.associateWith { Load.Loading }, history = Load.Loading) }
        loading = scope.launch {
            launch {
                try {
                    val items = env.calls.run { env.api.history(entry.date, entry.id) }
                    inner.update { it.copy(history = Load.Ready(items)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    inner.update { it.copy(history = Load.Failed(e.toFailure())) }
                }
            }
            for (n in entry.slips.mapNotNull { slipNumber(it.file) }) launch {
                val answer: Load<ByteArray> = try {
                    Load.Ready(env.calls.run { env.api.slip(entry.date, entry.id, n) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Load.Failed(e.toFailure())
                }
                inner.update { it.copy(slips = it.slips + (n to answer)) }
            }
        }
    }

    /** Reads the entry again (someone else changed it, or the connection came back). [date] is the entry's new date after a move elsewhere. */
    private suspend fun refresh(date: String?) {
        val now = inner.value.entry
        try {
            val fresh = env.calls.run { env.api.entry(date ?: now.date, now.id) }
            inner.update { it.copy(entry = fresh) }
            loadParts(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            inner.update { it.copy(action = ActionPhase.Failed(e.toFailure())) }
        }
    }

    // ---- delete and move ----

    fun askDelete() = inner.update { if (it.entry.deleted || it.action is ActionPhase.Working) it else it.copy(confirmDelete = true, moveText = null, action = ActionPhase.Idle) }

    fun cancelDelete() = inner.update { it.copy(confirmDelete = false, action = ActionPhase.Idle) }

    fun confirmDelete() {
        val s = inner.value
        if (!s.confirmDelete) return
        act { env.api.deleteEntry(s.entry.date, s.entry.id) }
    }

    fun openMove() = inner.update { if (it.entry.deleted || it.action is ActionPhase.Working) it else it.copy(moveText = it.entry.date, confirmDelete = false, action = ActionPhase.Idle) }

    fun setMoveDate(text: String) = inner.update { if (it.moveText == null) it else it.copy(moveText = text) }

    fun cancelMove() = inner.update { it.copy(moveText = null, action = ActionPhase.Idle) }

    fun confirmMove() {
        val s = inner.value
        val target = s.moveText?.trim() ?: return
        if (!isIsoDate(target)) return
        act { env.api.moveEntry(s.entry.date, s.entry.id, target) }
    }

    /** Runs a change that answers the entry as it is now. Needs the connection; a refusal keeps the question open with the server's words. */
    private fun act(call: suspend () -> EntryDto) {
        if (inner.value.action is ActionPhase.Working || !env.canWrite.value) return
        inner.update { it.copy(action = ActionPhase.Working) }
        scope.launch {
            try {
                val fresh = env.calls.run { call() }
                inner.update { it.copy(entry = fresh, action = ActionPhase.Idle, confirmDelete = false, moveText = null, edit = null, notice = null) }
                loadParts(fresh)
                changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                inner.update { it.copy(action = ActionPhase.Failed(e.toFailure())) }
                // Someone changed the entry first: read the newer one; the typed form stays beside it, marked stale.
                if (e is ClientError.Api && e.code == ErrorCode.CONFLICT) refresh(null)
            }
        }
    }

    // ---- edit ----

    fun startEdit() {
        inner.update { s ->
            val e = s.entry
            if (e.deleted || s.action is ActionPhase.Working) s
            else s.copy(
                edit = EditForm(e.type, e.category, e.item, e.note, e.tenders.map { TenderRow(++keys, it.currency, it.method, it.amount) }, e.slips, emptyList(), e.updatedAt),
                confirmDelete = false, moveText = null, action = ActionPhase.Idle, notice = null,
            )
        }
    }

    fun cancelEdit() = inner.update { if (it.action is ActionPhase.Working) it else it.copy(edit = null, notice = null, action = ActionPhase.Idle) }

    private fun change(change: (EditForm) -> EditForm) = inner.update { s ->
        if (s.edit == null || s.action is ActionPhase.Working) s else s.copy(edit = change(s.edit), action = ActionPhase.Idle)
    }

    private fun EditForm.with(
        type: EntryType = this.type, category: String? = this.category, item: String = this.item, note: String = this.note,
        tenders: List<TenderRow> = this.tenders, keep: List<SlipDto> = this.keep, added: List<SlipItem> = this.added,
    ) = EditForm(type, category, item, note, tenders, keep, added, base)

    private fun TenderRow.with(currency: String = this.currency, method: TenderMethod = this.method, amount: String = this.amount) = TenderRow(key, currency, method, amount)

    fun setType(type: EntryType) = change { f ->
        val categories = (env.config.value as? ConfigState.Ready)?.config?.categories.orEmpty()
        val own = inner.value.entry.category
        f.with(type = type, category = f.category?.takeIf { key -> categories.any { it.key == key && ((!it.archived && it.appliesTo.covers(type)) || key == own) } })
    }

    fun setCategory(key: String?) = change { it.with(category = key) }
    fun setItem(text: String) = change { it.with(item = text) }
    fun setNote(text: String) = change { it.with(note = text) }
    fun setAmount(row: Int, text: String) = change { f -> f.with(tenders = f.tenders.map { if (it.key == row) it.with(amount = text) else it }) }
    fun setCurrency(row: Int, code: String) = change { f -> f.with(tenders = f.tenders.map { if (it.key == row) it.with(currency = code) else it }) }
    fun setMethod(row: Int, method: TenderMethod) = change { f -> f.with(tenders = f.tenders.map { if (it.key == row) it.with(method = method) else it }) }

    fun addTender() = change { f ->
        val codes = (env.config.value as? ConfigState.Ready)?.config?.currencies.orEmpty().map { it.code }
        val code = codes.firstOrNull { c -> f.tenders.none { it.currency == c } } ?: codes.firstOrNull().orEmpty()
        f.with(tenders = f.tenders + TenderRow(++keys, code, TenderMethod.CASH, ""))
    }

    fun removeTender(row: Int) = change { f -> if (f.tenders.size > 1) f.with(tenders = f.tenders.filter { it.key != row }) else f }

    /** A stored slip stops being kept (it is removed when the change is saved). */
    fun removeStoredSlip(file: String) = change { f -> f.with(keep = f.keep.filter { it.file != file }) }

    fun removeNewSlip(key: Int) = change { f -> f.with(added = f.added.filter { it.key != key }) }

    /** A photo was picked: compress it and add it if it is readable, small enough and there is room. */
    fun addSlip(name: String, raw: ByteArray) {
        val s = inner.value
        val form = s.edit ?: return
        if (s.action is ActionPhase.Working) return
        val limits = (env.config.value as? ConfigState.Ready)?.config?.records ?: RecordsPolicy()
        if (form.keep.size + form.added.size + s.compressing >= limits.slipMaxCount) {
            inner.update { it.copy(notice = SlipNotice.TOO_MANY) }
            return
        }
        inner.update { it.copy(notice = null, compressing = it.compressing + 1) }
        scope.launch {
            val jpeg = try {
                compressor.compress(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            inner.update { st ->
                val done = st.copy(compressing = st.compressing - 1)
                val current = done.edit
                when {
                    jpeg == null -> done.copy(notice = SlipNotice.UNREADABLE)
                    jpeg.size > limits.slipMaxSizeKb * 1024L -> done.copy(notice = SlipNotice.TOO_BIG)
                    current == null -> done // the edit was closed meanwhile
                    else -> done.copy(edit = current.with(added = current.added + SlipItem(++keys, name, jpeg)))
                }
            }
        }
    }

    /** Sends the change. The typed amounts are parsed like on Record; the server has the last word. */
    fun saveEdit() {
        val state = ui.value
        val edit = state.edit ?: return
        if (edit.issues.isNotEmpty() || edit.compressing || edit.stale || !state.canWrite) return
        val s = inner.value
        val exponents = edit.currencies.associate { it.code to it.exponent }
        val form = edit.form
        val tenders = form.tenders.map { row ->
            val exponent = exponents[row.currency] ?: return
            TenderDto(row.currency, row.method, formatAmount(parseTypedAmount(row.amount, exponent) ?: return, exponent))
        }
        val request = UpdateEntryRequest(form.type, form.category, form.item.trim(), form.note.trim(), tenders, form.keep.map { it.file }, form.base)
        act { env.api.updateEntry(s.entry.date, s.entry.id, request, form.added.map { it.bytes }) }
    }
}
