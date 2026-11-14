package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.Capabilities
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.DayStatus
import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.app.flow.OpenDayBlock
import xyz.felismp.shoparchive.app.flow.OpenDayPhase
import xyz.felismp.shoparchive.app.flow.OpenDayUi
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.app.flow.TodayData
import xyz.felismp.shoparchive.app.flow.displayAmount
import xyz.felismp.shoparchive.app.flow.groupThousands
import xyz.felismp.shoparchive.app.flow.parseTypedAmount
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LocalizedName

// What each screen shows, worked out from clientCore's state without touching the DOM (so Node can test it). Same rules as composeApp's screens.

// ---- errors ----

/** The reason key `pin.not-set` is worded by `reason_pin_not_set`; the one reason that has another name is the unconfirmed transfer. */
internal fun reasonKey(reason: String) = if (reason == "entry.transfer-unconfirmed") "notice_not_confirmed" else "reason_" + reason.replace('.', '_').replace('-', '_')

internal fun codeKey(code: ErrorCode): String = when (code) {
    ErrorCode.PROTOCOL_MISMATCH -> "code_protocol_mismatch"
    ErrorCode.UNAUTHORIZED -> "code_unauthorized"
    ErrorCode.FORBIDDEN -> "code_forbidden"
    ErrorCode.NOT_FOUND -> "code_not_found"
    ErrorCode.INVALID_REQUEST -> "code_invalid_request"
    ErrorCode.REAUTH_REQUIRED -> "code_reauth_required"
    ErrorCode.CREDENTIALS_CHANGED -> "err_credentials_changed"
    ErrorCode.PAYLOAD_TOO_LARGE -> "code_payload_too_large"
    ErrorCode.BANNED -> "err_banned"
    ErrorCode.RATE_LIMITED -> "err_too_many_later"
    ErrorCode.NO_OPEN_SESSION -> "reason_session_no_open_day"
    ErrorCode.SESSION_CLOSED -> "reason_session_closed"
    ErrorCode.CONFLICT -> "code_conflict"
    ErrorCode.ENTRY_BROKEN -> "code_entry_broken"
    ErrorCode.STORAGE_BUSY -> "code_storage_busy"
    ErrorCode.INTERNAL -> "code_internal"
    ErrorCode.DEVICE_NOT_RECOGNIZED -> "err_device_rejected"
}

/** The sentence for the server's [reason] if this app has words for it, else the one for its [code] (a reason from a newer server). */
internal fun refusalMessage(code: ErrorCode, reason: String?, known: Set<String> = WebStrings.en.keys): Message =
    Message(reason?.let(::reasonKey)?.takeIf { it in known } ?: codeKey(code))

internal fun Problem.message(): Message = when (this) {
    Problem.Unreachable -> Message("err_unreachable")
    is Problem.TooManyAttempts -> if (seconds != null) Message("err_too_many", listOf(seconds.toString())) else Message("err_too_many_later")
    Problem.Banned -> Message("err_banned")
    Problem.WrongCredentials -> Message("err_wrong_credentials")
    Problem.CredentialsChanged -> Message("err_credentials_changed")
    Problem.BadAddress -> Message("err_bad_address")
    Problem.BadUsername -> Message("err_bad_username")
    Problem.PinsDiffer -> Message("err_pins_differ")
    Problem.PinDigits -> Message("err_pin_digits")
    is Problem.PinExactly -> Message("err_pin_exact", listOf(length.toString()))
    Problem.PersonalDevice -> Message("err_personal_device")
    Problem.SessionEnded -> Message("err_session_ended")
    Problem.DeviceRejected -> Message("err_device_rejected")
    Problem.ReauthCancelled -> Message("err_reauth_cancelled")
    is Problem.Rejected -> refusalMessage(code, reason)
    Problem.PinMismatch -> Message("pinmm_body")
    Problem.OtherServer -> Message("err_other_server")
    Problem.ProtocolMismatch -> Message("proto_body")
    Problem.Unknown -> Message("err_unknown")
}

internal fun Failure.message(): Message = when (this) {
    Failure.Unreachable -> Message("err_unreachable")
    is Failure.Refused -> refusalMessage(code, reason)
    is Failure.Client -> problem.message()
    Failure.Other -> Message("err_unknown")
}

// ---- the server list (only ever the page's own server) ----

/** [retry]: the button that adds the page's server again. It shows after a failure, and after the first try when nothing else is going on (never loops by itself). */
data class ServersView(val message: Message, val error: Boolean, val retry: Boolean)

fun serversView(s: AppState.Servers, autoAddFired: Boolean): ServersView {
    val problem = s.problem
    return when {
        s.busy -> ServersView(Message("working"), error = false, retry = false)
        problem != null -> ServersView(problem.message(), error = true, retry = true)
        else -> ServersView(Message("working"), error = false, retry = autoAddFired)
    }
}

// ---- the shell around the screens ----

sealed interface ShellBody {
    data object Loading : ShellBody
    data class Failed(val message: Message) : ShellBody
    data object NoBranch : ShellBody
    data object Ready : ShellBody
}

fun shellBody(config: ConfigState, branch: String?): ShellBody = when {
    config is ConfigState.Loading -> ShellBody.Loading
    config is ConfigState.Failed -> ShellBody.Failed(Message("cfg_failed"))
    branch == null -> ShellBody.NoBranch
    else -> ShellBody.Ready
}

fun connectionBanner(status: ConnectionStatus): Message? = when (status) {
    ConnectionStatus.CONNECTED -> null
    ConnectionStatus.CONNECTING -> Message("status_connecting")
    ConnectionStatus.OFFLINE -> Message("offline_banner")
}

// ---- Today ----

/** `08:12` from a server timestamp like `2026-10-04T08:12:30+07:00` (the shop's own time); the text itself if it is not one. */
fun clockTime(timestamp: String): String = Regex("""T(\d\d:\d\d)""").find(timestamp)?.groupValues?.get(1) ?: timestamp

/** The name in the page's language, English if that is empty. */
fun LocalizedName.pick(language: String): String = when (language) {
    "lo" -> lo
    "th" -> th
    else -> en
}.ifBlank { en }

class DayButton(val label: String, val enabled: Boolean, val primary: Boolean)

class CurrencyCard(val code: String, val rows: List<Pair<String, String>>)

class Amount(val text: String, val income: Boolean)

class Line(val title: String, val caption: String, val amounts: List<Amount>)

/**
 * What Today shows. [chips]: the day's status and the round ("Round N of today" from the second one on). [notOpened] with its [body]: the card that
 * says the day is not open. [openButton] is the way to the Open day form (null for someone who may not open it, and while the day is open).
 */
class TodayView(
    val banners: List<Message>,
    val chips: List<Message>,
    val notOpened: Boolean,
    val body: Message?,
    val needsConnection: Boolean,
    val openButton: DayButton?,
    val cards: List<CurrencyCard>,
    val linesTitle: String?,
    val lines: List<Line>,
    val empty: Boolean,
    val statsBanner: Message?,
)

private fun money(amount: String, code: String) = groupThousands(amount) + " " + code

private fun line(e: EntryDto, categoryName: (String?) -> String?): Line {
    val sign = if (e.type == EntryType.INCOME) "+" else "-"
    return Line(
        e.item.ifBlank { categoryName(e.category) ?: "—" }, clockTime(e.createdAt) + " · " + e.createdBy.name,
        e.tenders.map { Amount(sign + money(it.amount, it.currency), e.type == EntryType.INCOME) },
    )
}

private fun card(c: DashboardCurrency) = CurrencyCard(
    c.currency,
    listOf(
        "today_in" to money(c.totals.income, c.currency),
        "today_out" to money(c.totals.expense, c.currency),
        "today_net" to money(c.totals.net, c.currency),
        "today_cash" to money(c.totals.cashNet, c.currency),
    ),
)

fun todayView(data: TodayData, can: Capabilities, canWrite: Boolean, categoryName: (String?) -> String?): TodayView {
    val banners = mutableListOf<Message>()
    data.clockOffMinutes?.let { banners += Message("clock_off", listOf(it.toString())) }
    val chips = mutableListOf<Message>()
    val round = data.round.takeIf { it >= 2 }?.let { Message("day_round", listOf(it.toString())) }
    var body: Message? = null
    var button: DayButton? = null
    val notOpened = data.status == DayStatus.NotOpened
    when (val status = data.status) {
        DayStatus.NotOpened -> {
            body = Message(if (can.openDay) "openday_card_body" else "openday_card_body_other")
            if (can.openDay) button = DayButton("openday_btn", canWrite, primary = true)
        }
        is DayStatus.Open -> {
            chips += Message("day_open_by", listOf(clockTime(status.session.openedAt), status.session.openedBy.name))
            round?.let { chips += it }
            data.staleFrom?.let { banners += Message(if (can.closeDay) "day_stale" else "day_stale_other", listOf(it)) }
        }
        is DayStatus.Closed -> {
            chips += Message("day_closed")
            round?.let { chips += it }
            if (can.openDay) button = DayButton("openday_again_btn", canWrite, primary = false)
        }
    }
    var cards = emptyList<CurrencyCard>()
    var title: String? = null
    var lines = emptyList<Line>()
    var empty = false
    var statsBanner: Message? = null
    if (!data.showStats) {
        statsBanner = data.statsFailure?.let { Message("today_mine_failed", detail = it.message()) }
        data.mine?.let { mine ->
            title = "today_mine"
            lines = mine.map { line(it, categoryName) }
            empty = mine.isEmpty()
        }
    } else {
        statsBanner = data.statsFailure?.let { Message("today_stats_failed", detail = it.message()) }
        cards = data.currencies?.filter { it.totals.count > 0 }?.map(::card).orEmpty()
        if (data.recent.isEmpty()) empty = data.statsFailure == null
        else {
            title = "today_latest"
            lines = data.recent.take(10).map { line(it, categoryName) }
        }
    }
    return TodayView(banners, chips, notOpened, body, needsConnection = button != null && !canWrite, openButton = button, cards, title, lines, empty, statsBanner)
}

// ---- Open day ----

class OpenDayField(val code: String, val text: String, val invalid: Boolean, val shown: String?)

class OpenDayBanner(val message: Message, val error: Boolean)

/**
 * [keptHint]: what the previous round of the same day left in the drawer; it is only said, the fields start empty (the drawer is counted again). [goToday]
 * shows with "already opened". [confirmEnabled] is off while saving, offline or until what the form needs has loaded.
 */
class OpenDayView(
    val keptHint: Message?,
    val fields: List<OpenDayField>,
    val banner: OpenDayBanner?,
    val goToday: Boolean,
    val needsConnection: Boolean,
    val confirmEnabled: Boolean,
    val saving: Boolean,
)

fun openDayView(ui: OpenDayUi): OpenDayView {
    val kept = ui.currencies.mapNotNull { c ->
        val minor = ui.keptHint[c.code]?.let { parseTypedAmount(it, c.exponent, allowZero = true) } ?: return@mapNotNull null
        displayAmount(minor, c.exponent) + " " + c.code
    }
    val fields = ui.currencies.map { c ->
        val text = ui.amounts[c.code] ?: ui.prefill[c.code].orEmpty()
        val minor = parseTypedAmount(text, c.exponent, allowZero = true)
        OpenDayField(
            c.code, text, invalid = c.code in ui.invalid || (text.isNotEmpty() && minor == null),
            shown = if (minor != null && text.isNotEmpty()) displayAmount(minor, c.exponent) + " " + c.code else null,
        )
    }
    val saving = ui.phase is OpenDayPhase.Saving
    val banner = when (val p = ui.phase) {
        OpenDayPhase.Saving -> OpenDayBanner(Message("working"), error = false)
        is OpenDayPhase.AlreadyOpen -> OpenDayBanner(Message("openday_already", listOf(p.by.name)), error = false)
        is OpenDayPhase.PreviousOpen -> OpenDayBanner(Message("openday_previous", listOf(p.session.businessDate, p.session.openedBy.name)), error = true)
        is OpenDayPhase.Failed -> OpenDayBanner(p.failure.message(), error = true)
        else -> null
    }
    return OpenDayView(
        keptHint = kept.takeIf { it.isNotEmpty() }?.let { Message("openday_kept_hint", listOf(it.joinToString(", "))) },
        fields, banner, goToday = ui.phase is OpenDayPhase.AlreadyOpen, needsConnection = ui.blocked == OpenDayBlock.OFFLINE,
        confirmEnabled = ui.blocked == null && !saving, saving = saving,
    )
}
