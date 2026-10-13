package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.RecordsPolicy
import xyz.felismp.shoparchive.shared.SlipCheckDto
import xyz.felismp.shoparchive.shared.SlipUseDto
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.covers
import xyz.felismp.shoparchive.shared.formatAmount

class TenderRow(val key: Int, val currency: String, val method: TenderMethod, val amount: String)

/**
 * A compressed slip photo waiting to be sent. [code] is the text of the QR read off it, if any. [uses] are the entries already recorded
 * with that code (empty when none, or when the server was not asked); [twiceHere] says the same code is on another slip of this draft.
 * [check] is what the bank that printed the slip says about it (null when no bank was asked or none answered).
 */
class SlipItem(
    val key: Int, val name: String, val bytes: ByteArray,
    val code: String? = null, val uses: List<SlipUseDto> = emptyList(), val twiceHere: Boolean = false, val check: SlipCheckDto? = null,
) {
    fun copy(code: String? = this.code, uses: List<SlipUseDto> = this.uses, twiceHere: Boolean = this.twiceHere, check: SlipCheckDto? = this.check) =
        SlipItem(key, name, bytes, code, uses, twiceHere, check)
}

/** The entry being written. [id] is made once per draft and stays the same on every resend. */
class Draft(
    val id: String,
    val type: EntryType,
    val category: String?,
    val item: String,
    val note: String,
    val tenders: List<TenderRow>,
    val slips: List<SlipItem>,
)

/** Where saving stands (GRILL Q27): [Unknown] is not a failure: the entry may have landed. */
sealed interface SavePhase {
    data object Editing : SavePhase
    data object Saving : SavePhase
    data class Saved(val entry: EntryDto) : SavePhase

    /** The answer never came. The draft is frozen until the person checks again (same id) or drops it. */
    data class Unknown(val failure: Failure) : SavePhase

    /** The server refused; nothing was recorded and the draft is kept. */
    data class Failed(val failure: Failure) : SavePhase
}

enum class RecordIssue { AMOUNT_MISSING, AMOUNT_INVALID, SLIP_NEEDED, SLIP_NOT_ALLOWED, TOO_MANY_SLIPS, CATEGORY_NEEDED }

/** Why Save is off apart from the entry itself. */
enum class SaveBlock { OFFLINE, NO_DAY, NO_BRANCH, LOADING }

enum class SlipNotice { TOO_MANY, TOO_BIG, UNREADABLE }

class RecordUi(
    val draft: Draft,
    val phase: SavePhase,
    val currencies: List<CurrencyInfo>,
    val categories: List<CategoryDto>,
    val suggestions: List<String>,
    val policy: RecordsPolicy,
    /** Rows whose amount text is not a valid amount for its currency. */
    val badRows: Set<Int>,
    val issues: List<RecordIssue>,
    val block: SaveBlock?,
    val slipNotice: SlipNotice?,
    val compressing: Boolean,
    /** The payment lines whose amount was read from a slip and not yet changed by staff; the screen asks them to check it. */
    val amountFromSlip: Set<Int> = emptySet(),
    /** The branch the entry with an unknown result was sent for; it stays that one whatever branch is chosen now. */
    val pendingBranch: String? = null,
    /** The live connection is up: all a resend after an unknown result needs, whatever day or branch is shown. */
    val connected: Boolean = true,
) {
    val online: Boolean get() = draft.tenders.any { it.method == TenderMethod.ONLINE }
    val frozen: Boolean get() = phase is SavePhase.Saving || phase is SavePhase.Unknown
    val canSave: Boolean get() = block == null && issues.isEmpty() && !frozen && !compressing
}

/** Record: one entry at a time. The draft lives as long as the unlocked session. */
class RecordState(
    private val env: Env,
    private val today: TodayState,
    private val compressor: SlipCompressor,
    private val reader: SlipReader,
) {
    private class Inner(
        val draft: Draft,
        val phase: SavePhase = SavePhase.Editing,
        val recent: List<String> = emptyList(),
        val notice: SlipNotice? = null,
        val compressing: Int = 0,
        /** The exact request of the first try, kept while its result is unknown so a resend cannot differ. */
        val sent: Sent? = null,
        /** The payment lines filled from a slip (row key to slip key), each until staff change its amount, remove it or the draft is replaced. */
        val amountFromSlip: Map<Int, Int> = emptyMap(),
        /** The slips whose photo is still being read: saving waits for them, so their code goes along. */
        val reading: Set<Int> = emptySet(),
    )

    private class Sent(val request: CreateEntryRequest, val slips: List<ByteArray>)

    private var keys = 0
    private var suggestionJob: Job? = null
    private val inner = MutableStateFlow(Inner(freshDraft(EntryType.EXPENSE, "")))

    val ui: StateFlow<RecordUi> = combine(inner, env.config, env.canWrite, env.branch, today.ui) { s, config, canWrite, branch, day ->
        val ready = (config as? ConfigState.Ready)?.config
        val policy = ready?.records ?: RecordsPolicy()
        val exponents = ready?.currencies.orEmpty().associate { it.code to it.exponent }
        val d = s.draft
        val categories = ready?.categories.orEmpty().filter { !it.archived && it.appliesTo.covers(d.type) }
        val bad = d.tenders.filter { it.amount.isNotEmpty() && amountOf(it, exponents) == null }.map { it.key }.toSet()
        val online = d.tenders.any { it.method == TenderMethod.ONLINE }
        val issues = buildList {
            if (d.tenders.any { it.amount.isEmpty() }) add(RecordIssue.AMOUNT_MISSING)
            if (bad.isNotEmpty()) add(RecordIssue.AMOUNT_INVALID)
            if (online && d.slips.isEmpty()) add(RecordIssue.SLIP_NEEDED)
            if (!online && d.slips.isNotEmpty()) add(RecordIssue.SLIP_NOT_ALLOWED)
            if (d.slips.size > policy.slipMaxCount) add(RecordIssue.TOO_MANY_SLIPS)
            if (policy.requireCategory && d.category == null) add(RecordIssue.CATEGORY_NEEDED)
        }
        val dayStatus = day.data?.takeIf { it.branch == branch }?.status
        RecordUi(
            d, s.phase, ready?.currencies.orEmpty(), categories,
            s.recent.filter { it != d.item && it.contains(d.item.trim(), ignoreCase = true) }.take(SUGGESTIONS),
            policy, bad, issues,
            block = when {
                ready == null -> SaveBlock.LOADING
                branch == null -> SaveBlock.NO_BRANCH
                !canWrite -> SaveBlock.OFFLINE
                policy.requireOpenDay && dayStatus != null && dayStatus !is DayStatus.Open -> SaveBlock.NO_DAY
                else -> null
            },
            s.notice, s.compressing > 0 || s.draft.slips.any { it.key in s.reading }, s.amountFromSlip.keys, s.sent?.request?.branch?.takeIf { s.phase is SavePhase.Unknown }, canWrite,
        )
    }.stateIn(env.scope, SharingStarted.Eagerly, initialUi())

    init {
        env.scope.launch {
            // The first currency is the default once the config is there.
            val ready = env.config.first { it is ConfigState.Ready } as ConfigState.Ready
            val code = ready.config.currencies.firstOrNull()?.code.orEmpty()
            inner.update { s -> s.copy(s.draft.copy(tenders = s.draft.tenders.map { if (it.currency.isEmpty()) it.copy(currency = code) else it })) }
            loadSuggestions(inner.value.draft.category)
        }
    }

    private fun initialUi() = RecordUi(inner.value.draft, SavePhase.Editing, emptyList(), emptyList(), emptyList(), RecordsPolicy(), emptySet(), emptyList(), SaveBlock.LOADING, null, false)

    private fun freshDraft(type: EntryType, currency: String) =
        Draft(uuidV7(env.now()), type, null, "", "", listOf(TenderRow(++keys, currency, TenderMethod.CASH, "")), emptyList())

    private fun Inner.copy(draft: Draft = this.draft, phase: SavePhase = this.phase, recent: List<String> = this.recent, notice: SlipNotice? = this.notice, compressing: Int = this.compressing, sent: Sent? = this.sent, amountFromSlip: Map<Int, Int> = this.amountFromSlip, reading: Set<Int> = this.reading) =
        Inner(draft, phase, recent, notice, compressing, sent, amountFromSlip, reading)

    private fun Draft.copy(
        type: EntryType = this.type, category: String? = this.category, item: String = this.item, note: String = this.note,
        tenders: List<TenderRow> = this.tenders, slips: List<SlipItem> = this.slips,
    ) = Draft(id, type, category, item, note, tenders, slips)

    private fun TenderRow.copy(currency: String = this.currency, method: TenderMethod = this.method, amount: String = this.amount) =
        TenderRow(key, currency, method, amount)

    private fun amountOf(row: TenderRow, exponents: Map<String, Int>): Long? =
        exponents[row.currency]?.let { parseTypedAmount(row.amount, it) }

    private fun currencyCodes() = (env.config.value as? ConfigState.Ready)?.config?.currencies.orEmpty().map { it.code }

    /** Changes the draft; refused while saving or while the answer is unknown (the id may already be stored with the old content). */
    private fun edit(change: (Draft) -> Draft) = editInner { s ->
        val draft = change(s.draft)
        // The amount read from a slip stops being "read" once its line is gone or its amount is changed.
        val read = s.amountFromSlip.filterKeys { key -> draft.tenders.firstOrNull { it.key == key }?.amount == s.draft.tenders.firstOrNull { it.key == key }?.amount }
        s.copy(draft, phase = SavePhase.Editing, amountFromSlip = read)
    }

    private fun editInner(change: (Inner) -> Inner) {
        inner.update { s -> if (s.phase is SavePhase.Saving || s.phase is SavePhase.Unknown) s else change(s) }
    }

    fun setType(type: EntryType) {
        edit { d ->
            val categories = (env.config.value as? ConfigState.Ready)?.config?.categories.orEmpty()
            val keep = d.category?.takeIf { key -> categories.any { it.key == key && !it.archived && it.appliesTo.covers(type) } }
            d.copy(type = type, category = keep)
        }
        loadSuggestions(inner.value.draft.category)
    }

    fun setCategory(key: String?) {
        edit { it.copy(category = key) }
        loadSuggestions(inner.value.draft.category)
    }

    fun setItem(text: String) = edit { it.copy(item = text) }
    fun setNote(text: String) = edit { it.copy(note = text) }

    fun setAmount(row: Int, text: String) =
        edit { d -> d.copy(tenders = d.tenders.map { if (it.key == row) it.copy(amount = text) else it }) }

    fun setCurrency(row: Int, code: String) =
        edit { d -> d.copy(tenders = d.tenders.map { if (it.key == row) it.copy(currency = code) else it }) }

    fun setMethod(row: Int, method: TenderMethod) =
        edit { d -> d.copy(tenders = d.tenders.map { if (it.key == row) it.copy(method = method) else it }) }

    /** Another payment line, in the first currency not used yet. */
    fun addTender() = edit { d ->
        val codes = currencyCodes()
        val code = codes.firstOrNull { c -> d.tenders.none { it.currency == c } } ?: codes.firstOrNull().orEmpty()
        d.copy(tenders = d.tenders + TenderRow(++keys, code, TenderMethod.CASH, ""))
    }

    fun removeTender(row: Int) = edit { d -> if (d.tenders.size > 1) d.copy(tenders = d.tenders.filter { it.key != row }) else d }

    fun removeSlip(key: Int) {
        edit { d -> d.copy(slips = d.slips.filter { it.key != key }.withTwiceHere()) }
        inner.update { it.copy(notice = null) }
    }

    /** A photo was picked: compress it and add it if it is readable, small enough and there is room. */
    fun addSlip(name: String, raw: ByteArray) {
        val phase = inner.value.phase
        if (phase is SavePhase.Saving || phase is SavePhase.Unknown) return
        val limits = ui.value.policy
        if (inner.value.draft.slips.size + inner.value.compressing >= limits.slipMaxCount) {
            inner.update { it.copy(notice = SlipNotice.TOO_MANY) }
            return
        }
        inner.update { it.copy(notice = null, compressing = it.compressing + 1) }
        env.scope.launch {
            val jpeg = try {
                compressor.compress(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            var added: Int? = null
            inner.update { s ->
                val s2 = s.copy(compressing = s.compressing - 1)
                added = null
                when {
                    jpeg == null -> s2.copy(notice = SlipNotice.UNREADABLE)
                    jpeg.size > limits.slipMaxSizeKb * 1024L -> s2.copy(notice = SlipNotice.TOO_BIG)
                    else -> {
                        val slip = SlipItem(++keys, name, jpeg)
                        added = slip.key
                        s2.copy(s2.draft.copy(slips = s2.draft.slips + slip), phase = SavePhase.Editing, reading = s2.reading + slip.key)
                    }
                }
            }
            added?.let { if (jpeg != null) readSlip(it, jpeg) }
        }
    }

    /** Reads the photo of slip [slip] once: its amount goes on a payment line, its code on the slip; then asks who used that code already and what the bank says. */
    private suspend fun readSlip(slip: Int, jpeg: ByteArray) {
        val scan = try {
            reader.tryRead(jpeg)?.also { scan ->
                scan.text?.let { fillAmountFromSlip(slip, it) }
                scan.code?.let { setCode(slip, it) }
            }
        } finally {
            inner.update { it.copy(reading = it.reading - slip) }
        }
        scan?.code?.let {
            askUses(slip, it)
            askCheck(slip, it)
        }
    }

    /**
     * Puts the paid amount in [text], read off the photo of slip [slip], in the first empty online line of that currency, if that slip is still
     * attached (not removed, nor the draft replaced, while it was read). What staff typed is never replaced.
     */
    private fun fillAmountFromSlip(slip: Int, text: String) {
        val currencies = ui.value.currencies
        val (code, minor) = slipAmount(text, currencies.associate { it.code to it.exponent }) ?: return
        val exponent = currencies.first { it.code == code }.exponent
        editInner { s ->
            if (s.draft.slips.none { it.key == slip }) return@editInner s
            // Looked for now, not before the read: staff may have typed an amount meanwhile.
            val row = s.draft.tenders.firstOrNull { it.method == TenderMethod.ONLINE && it.amount.isEmpty() && it.currency == code } ?: return@editInner s
            val tenders = s.draft.tenders.map { if (it.key == row.key) it.copy(amount = formatAmount(minor, exponent)) else it }
            s.copy(s.draft.copy(tenders = tenders), phase = SavePhase.Editing, amountFromSlip = s.amountFromSlip + (row.key to slip))
        }
    }

    /**
     * Puts the amount the bank gave for slip [slip] on the online line of that currency the slip filled before, or else on the first empty one,
     * if that slip is still attached. It replaces what was read off the photo; what staff typed is never replaced.
     */
    private fun fillCheckedAmount(slip: Int, code: String, amount: String) {
        val exponent = ui.value.currencies.firstOrNull { it.code == code }?.exponent ?: return
        val minor = parseTypedAmount(amount, exponent) ?: return
        editInner { s ->
            if (s.draft.slips.none { it.key == slip }) return@editInner s
            val online = s.draft.tenders.filter { it.method == TenderMethod.ONLINE && it.currency == code }
            val row = online.firstOrNull { s.amountFromSlip[it.key] == slip } ?: online.firstOrNull { it.amount.isEmpty() } ?: return@editInner s
            val tenders = s.draft.tenders.map { if (it.key == row.key) it.copy(amount = formatAmount(minor, exponent)) else it }
            s.copy(s.draft.copy(tenders = tenders), phase = SavePhase.Editing, amountFromSlip = s.amountFromSlip + (row.key to slip))
        }
    }

    /** Gives slip [slip] the [code] read off its photo, if that slip is still attached. */
    private fun setCode(slip: Int, code: String) = editInner { s ->
        if (s.draft.slips.none { it.key == slip }) s else s.copy(s.draft.copy(slips = s.draft.slips.map { if (it.key == slip) it.copy(code = code) else it }.withTwiceHere()))
    }

    /**
     * Asks the server which entries carry [code], the code of slip [slip], already. It only warns: saving is never held for the answer,
     * and a code the server could not be asked about shows nothing.
     */
    private suspend fun askUses(slip: Int, code: String) {
        if (inner.value.draft.slips.none { it.key == slip && it.code == code }) return
        val uses = try {
            env.calls.run { env.api.slipUses(code) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        // Kept even while saving: the warning is not part of what is sent, and a refused save goes back to a draft that still needs it.
        inner.update { s -> s.copy(s.draft.copy(slips = s.draft.slips.map { if (it.key == slip && it.code == code) it.copy(uses = uses) else it })) }
    }

    /**
     * Asks the server what the bank says about [code], the code of slip [slip]. Like [askUses] it never holds saving, and no answer shows nothing.
     * A transfer the bank knows puts its amount on a payment line.
     */
    private suspend fun askCheck(slip: Int, code: String) {
        if (inner.value.draft.slips.none { it.key == slip && it.code == code }) return
        val check = try {
            env.calls.run { env.api.slipCheck(code) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        } ?: return
        // Kept even while saving, like the uses.
        inner.update { s -> s.copy(s.draft.copy(slips = s.draft.slips.map { if (it.key == slip && it.code == code) it.copy(check = check) else it })) }
        val amount = check.amount
        val currency = check.currency
        if (check.known && amount != null && currency != null) fillCheckedAmount(slip, currency, amount)
    }

    /** The slips with [SlipItem.twiceHere] set on every one whose code is on another slip of the draft too. */
    private fun List<SlipItem>.withTwiceHere() = map { s -> s.copy(twiceHere = s.code != null && count { it.code == s.code } > 1) }

    private fun loadSuggestions(category: String?) {
        suggestionJob?.cancel()
        suggestionJob = env.scope.launch {
            val items = try {
                env.calls.run { env.api.recentItems(category) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList() // suggestions are a convenience
            }
            inner.update { it.copy(recent = items) }
        }
    }

    /** Saves the entry, or sends it again after a refusal. */
    fun save() = send()

    /** After an unknown result: asks the server again with the same id; it answers with the entry if it already landed. */
    fun checkAgain() {
        if (inner.value.phase is SavePhase.Unknown) send()
    }

    /** Gives up a draft whose result is unknown and starts an empty one. */
    fun discardDraft() {
        if (inner.value.phase !is SavePhase.Saving) inner.update { Inner(freshDraft(it.draft.type, currencyCodes().firstOrNull().orEmpty()), recent = it.recent) }
    }

    private fun send() {
        val held = inner.value.sent?.takeIf { inner.value.phase is SavePhase.Unknown }
        val sent = held ?: build() ?: return
        // A resend after an unknown result needs only the connection: it is the same request, whatever branch or day is shown now.
        if (held != null && !env.canWrite.value) return
        inner.update { it.copy(phase = SavePhase.Saving, notice = null, sent = sent) }
        val d = inner.value.draft
        env.scope.launch {
            try {
                val created = env.calls.run { env.api.createEntry(sent.request, sent.slips) }
                inner.update { Inner(freshDraft(d.type, d.tenders.first().currency), SavePhase.Saved(created.entry), it.recent) }
                today.reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A refusal means nothing was recorded. A lost connection (or anything else unexpected) after sending may mean it was.
                val unknown = e !is ClientError.Api && (e is ClientError.Unreachable || e !is ClientError)
                inner.update { it.copy(phase = if (unknown) SavePhase.Unknown(e.toFailure()) else SavePhase.Failed(e.toFailure()), sent = if (unknown) sent else null) }
            }
        }
    }

    /** The request for the draft as it is now, or null if it cannot be sent. */
    private fun build(): Sent? {
        val branch = env.branch.value ?: return null
        val state = ui.value
        if (state.phase is SavePhase.Saving || !state.canSave) return null
        val d = inner.value.draft
        val exponents = state.currencies.associate { it.code to it.exponent }
        val tenders = d.tenders.map { TenderDto(it.currency, it.method, formatAmount(amountOf(it, exponents) ?: return null, exponents.getValue(it.currency))) }
        return Sent(CreateEntryRequest(d.id, d.type, branch, d.category, d.item.trim(), d.note.trim(), tenders, d.slips.map { it.code }), d.slips.map { it.bytes })
    }

    private companion object {
        const val SUGGESTIONS = 6
    }
}
