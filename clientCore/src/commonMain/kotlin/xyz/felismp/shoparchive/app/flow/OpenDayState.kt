package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.SessionClose
import xyz.felismp.shoparchive.shared.SessionClosedMessage
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.UserRef
import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.parseAmount

sealed interface OpenDayPhase {
    data object Idle : OpenDayPhase
    data object Saving : OpenDayPhase

    /** The day is open now; the screen goes to Today. */
    data object Opened : OpenDayPhase

    /** Someone opened today's day first: nothing was changed. */
    data class AlreadyOpen(val by: UserRef) : OpenDayPhase

    /** An earlier day was never closed, so it is still the open one. */
    data class PreviousOpen(val session: SessionDto) : OpenDayPhase
    data class Failed(val failure: Failure) : OpenDayPhase
}

enum class OpenDayBlock { OFFLINE, NO_BRANCH, LOADING }

/**
 * [amounts] is what was typed; [prefill] is the change the last close of the branch left in the drawer, which a currency stays at until it is typed in
 * (a field with no key in [amounts] shows and sends [prefill]; a field cleared by hand has its key and is 0).
 * [invalid] are the currencies whose text is not an amount; [blocked] is why Confirm is off (null when it is on).
 */
data class OpenDayUi(
    val currencies: List<CurrencyInfo> = emptyList(),
    val amounts: Map<String, String> = emptyMap(),
    val prefill: Map<String, String> = emptyMap(),
    val invalid: Set<String> = emptySet(),
    val phase: OpenDayPhase = OpenDayPhase.Idle,
    val blocked: OpenDayBlock? = OpenDayBlock.LOADING,
)

/** Open day: the change counted in the drawer, one amount per currency (empty means 0). */
class OpenDayState(private val env: Env, private val today: TodayState) {
    private class Form(val amounts: Map<String, String> = emptyMap(), val invalid: Set<String> = emptySet(), val phase: OpenDayPhase = OpenDayPhase.Idle)

    private val form = MutableStateFlow(Form())

    /** The last close of a branch, read when the screen state starts, on a branch change, a reconnect and a live close; null when there is none or it could not be read. */
    private val lastClose = MutableStateFlow<Pair<String, SessionClose>?>(null)

    val ui: StateFlow<OpenDayUi> = combine(form, lastClose, env.config, env.canWrite, env.branch) { f, last, config, canWrite, branch ->
        val currencies = (config as? ConfigState.Ready)?.config?.currencies.orEmpty()
        OpenDayUi(
            currencies, f.amounts, keptBy(last?.takeIf { it.first == branch }?.second, currencies), f.invalid, f.phase,
            blocked = when {
                config !is ConfigState.Ready -> OpenDayBlock.LOADING
                branch == null -> OpenDayBlock.NO_BRANCH
                !canWrite -> OpenDayBlock.OFFLINE
                else -> null
            },
        )
    }.stateIn(env.scope, SharingStarted.Eagerly, OpenDayUi())

    init {
        // Another branch is another drawer.
        env.scope.launch { env.branch.drop(1).collect { reset() } }
        env.scope.launch {
            merge(
                env.branch.filterNotNull().map { },
                env.refetch,
                env.messages.filter { it is SessionClosedMessage && it.branch == env.branch.value }.map { },
            ).collectLatest { env.branch.value?.let { loadLastClose(it) } }
        }
    }

    private suspend fun loadLastClose(branch: String) {
        if (!env.can.openDay) return
        val today = env.today()
        try {
            val sessions = env.calls.run { env.api.sessionsBetween(branch, today.minus(365, DateTimeUnit.DAY).toString(), today.toString()) }
            lastClose.value = sessions.lastOrNull { it.close != null }?.close?.let { branch to it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The fields stay empty, as before.
        }
    }

    /** What stayed in the drawer at [close], per currency: counted less what was handed over. A currency with nothing left has no entry. */
    private fun keptBy(close: SessionClose?, currencies: List<CurrencyInfo>): Map<String, String> {
        if (close == null) return emptyMap()
        val kept = linkedMapOf<String, String>()
        for (c in currencies) {
            val counted = close.counted[c.code]?.let { parseAmount(it, c.exponent, allowZero = true) } ?: continue
            val handover = close.handover[c.code]?.let { parseAmount(it, c.exponent, allowZero = true) } ?: 0L
            if (counted > handover) kept[c.code] = formatAmount(counted - handover, c.exponent)
        }
        return kept
    }

    fun setAmount(code: String, text: String) {
        val f = form.value
        if (f.phase is OpenDayPhase.Saving) return
        form.value = Form(f.amounts + (code to text), f.invalid - code)
    }

    /** Empties the form (after leaving the screen or switching branch). */
    fun reset() {
        if (form.value.phase !is OpenDayPhase.Saving) form.value = Form()
    }

    fun confirm() {
        val branch = env.branch.value ?: return
        val config = (env.config.value as? ConfigState.Ready)?.config ?: return
        val f = form.value
        if (!env.canWrite.value || f.phase is OpenDayPhase.Saving) return
        val prefill = keptBy(lastClose.value?.takeIf { it.first == branch }?.second, config.currencies)
        val float = linkedMapOf<String, String>()
        val invalid = mutableSetOf<String>()
        for (currency in config.currencies) {
            val text = f.amounts[currency.code] ?: prefill[currency.code].orEmpty()
            if (text.isEmpty()) continue
            val minor = parseTypedAmount(text, currency.exponent, allowZero = true)
            if (minor == null) invalid += currency.code else float[currency.code] = formatAmount(minor, currency.exponent)
        }
        if (invalid.isNotEmpty()) {
            form.value = Form(f.amounts, invalid)
            return
        }
        form.value = Form(f.amounts, phase = OpenDayPhase.Saving)
        env.scope.launch {
            val phase = try {
                val answer = env.calls.run { env.api.openSession(branch, OpenSessionRequest(float)) }
                val by = answer.alreadyOpenBy
                when {
                    by == null -> OpenDayPhase.Opened
                    answer.session.businessDate == env.today().toString() -> OpenDayPhase.AlreadyOpen(by)
                    else -> OpenDayPhase.PreviousOpen(answer.session)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OpenDayPhase.Failed(e.toFailure())
            }
            form.value = Form(f.amounts, phase = phase)
            if (phase !is OpenDayPhase.Failed) today.reload()
        }
    }
}
