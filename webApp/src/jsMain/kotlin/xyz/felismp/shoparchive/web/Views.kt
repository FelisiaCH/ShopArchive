package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.client.RecordStart
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.Capabilities
import xyz.felismp.shoparchive.app.flow.CloseBlock
import xyz.felismp.shoparchive.app.flow.ClosePhase
import xyz.felismp.shoparchive.app.flow.CloseUi
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.DayStatus
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.app.flow.NotesUi
import xyz.felismp.shoparchive.app.flow.OpenDayBlock
import xyz.felismp.shoparchive.app.flow.OpenDayPhase
import xyz.felismp.shoparchive.app.flow.OpenDayUi
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.app.flow.RangeProblem
import xyz.felismp.shoparchive.app.flow.RecordIssue
import xyz.felismp.shoparchive.app.flow.RecordUi
import xyz.felismp.shoparchive.app.flow.SavePhase
import xyz.felismp.shoparchive.app.flow.SlipNotice
import xyz.felismp.shoparchive.app.flow.TenderRow
import xyz.felismp.shoparchive.app.flow.TodayData
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.bankTimeText
import xyz.felismp.shoparchive.app.flow.displayAmount
import xyz.felismp.shoparchive.app.flow.groupThousands
import xyz.felismp.shoparchive.app.flow.parseTypedAmount
import xyz.felismp.shoparchive.app.flow.savedAmountText
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.RecordsPolicy
import xyz.felismp.shoparchive.shared.SessionClose
import xyz.felismp.shoparchive.shared.SlipCheckDto
import xyz.felismp.shoparchive.shared.TenderMethod

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

// ---- navigation ----

class NavItem(val destination: Destination, val label: String)

/** Today, then the screens the person's rights allow; the shell shows only these. */
fun navItems(can: Capabilities): List<NavItem> = listOf(
    NavItem(Destination.TODAY, "nav_today"),
    NavItem(Destination.RECORD, "nav_record"),
    NavItem(Destination.HISTORY, "nav_history"),
    NavItem(Destination.NOTES, "nav_notes"),
).filter { can.allows(it.destination) }

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
 * [closeButton] is the way to Close day, only while the day is open and the person may close it.
 */
class TodayView(
    val banners: List<Message>,
    val chips: List<Message>,
    val notOpened: Boolean,
    val body: Message?,
    val needsConnection: Boolean,
    val openButton: DayButton?,
    val closeButton: DayButton?,
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
    var closeButton: DayButton? = null
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
            if (can.closeDay) closeButton = DayButton("closeday_btn", enabled = true, primary = data.staleFrom != null)
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
    return TodayView(banners, chips, notOpened, body, needsConnection = button != null && !canWrite, openButton = button, closeButton = closeButton, cards, title, lines, empty, statsBanner)
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

// ---- Close day ----

/** `+1,500` / `-1,500` for [minor] units of a currency with [exponent] decimals; a plain `0` for zero. */
fun signedAmount(minor: Long, exponent: Int): String = (if (minor > 0) "+" else "") + displayAmount(minor, exponent)

/** [text]: the signed amount with its currency (null when the count matches); [words]: matches / short / over, said below the line when it does not. */
class CloseVariance(val text: String?, val words: Message)

/**
 * One currency of the drawer: the amount typed, what the server expects, the difference, and — once something is counted — what stays as change and what is
 * handed over. [belowFloor]: less is in the drawer than the change that must stay.
 */
class CloseRowView(
    val code: String,
    val text: String,
    val invalid: Boolean,
    val expected: String?,
    val variance: CloseVariance?,
    val keep: String?,
    val handover: String?,
    val belowFloor: Boolean,
)

class CloseBanner(val message: Message, val error: Boolean)

/**
 * The Close day form. [noteLabel] is the key of the note's label (required while the count differs); [noteNeeded] says the required note is still empty.
 * [confirmEnabled] is off while saving, offline, with a wrong amount, or without the note the difference needs.
 */
class CloseDayView(
    val hint: Message,
    val previewBanner: Message?,
    val rows: List<CloseRowView>,
    val noteLabel: String,
    val noteNeeded: Boolean,
    val banner: CloseBanner?,
    val needsConnection: Boolean,
    val confirmEnabled: Boolean,
    val saving: Boolean,
)

fun closeDayView(ui: CloseUi): CloseDayView {
    val rows = ui.rows.map { row ->
        val code = row.currency.code
        fun money(minor: Long) = displayAmount(minor, row.currency.exponent) + " " + code
        val variance = row.variance?.let { v ->
            val words = when {
                v == 0L -> Message("closeday_matches")
                v < 0 -> Message("closeday_short", listOf(money(-v)))
                else -> Message("closeday_over", listOf(money(v)))
            }
            CloseVariance(if (v == 0L) null else signedAmount(v, row.currency.exponent) + " " + code, words)
        }
        CloseRowView(
            code, row.text, row.invalid, row.expected?.let(::money), variance,
            keep = row.counted?.let { money(row.float) },
            handover = row.counted?.let { money(row.handover ?: 0L) },
            belowFloor = row.belowFloor,
        )
    }
    val saving = ui.phase is ClosePhase.Saving
    val banner = when (val p = ui.phase) {
        ClosePhase.Saving -> CloseBanner(Message("working"), error = false)
        is ClosePhase.Failed -> CloseBanner(Message("closeday_failed", detail = p.failure.message()), error = true)
        else -> null
    }
    return CloseDayView(
        hint = Message("closeday_hint", listOf(ui.session?.businessDate.orEmpty())),
        previewBanner = ui.previewFailure?.let { Message("closeday_no_preview", detail = it.message()) },
        rows = rows,
        noteLabel = if (ui.noteRequired) "closeday_note_required" else "closeday_note",
        noteNeeded = ui.noteRequired && ui.note.isBlank(),
        banner = banner,
        needsConnection = ui.blocked == CloseBlock.OFFLINE,
        confirmEnabled = ui.canClose,
        saving = saving,
    )
}

/** One currency as the server closed it. [variance] has a sign when it is not zero; [belowFloor] can still be true with nothing to hand over. */
class CloseResultCard(
    val code: String,
    val counted: String,
    val expected: String,
    val variance: String,
    val handover: String,
    val belowFloor: Boolean,
)

/** What the server worked out at the close: [done] says who closed the day, [note] is the one left with it (null when empty). */
class CloseResultView(val done: Message, val cards: List<CloseResultCard>, val note: Message?)

fun closeResultView(date: String, close: SessionClose): CloseResultView = CloseResultView(
    done = Message("closeday_done", listOf(date, close.closedBy.name)),
    cards = close.counted.keys.map { code ->
        fun money(map: Map<String, String>) = groupThousands(map[code] ?: "0") + " " + code
        val variance = close.variance[code] ?: "0"
        val sign = if (variance.startsWith("-") || variance.trim('0', '.').isEmpty()) "" else "+"
        CloseResultCard(code, money(close.counted), money(close.expected), sign + groupThousands(variance) + " " + code, money(close.handover), code in close.belowFloor)
    },
    note = close.note.takeIf { it.isNotBlank() }?.let { Message("closeday_note_is", listOf(it)) },
)

// ---- Notes ----

/** What the line under a note says or offers: when the reader read it, the button to mark it read, whether the owner saw the writer's note, or nothing. */
sealed interface NoteFoot {
    data class ReadAt(val time: String) : NoteFoot
    data class MarkRead(val id: String, val enabled: Boolean) : NoteFoot
    data class Seen(val time: String) : NoteFoot
    data object NotSeen : NoteFoot
    data object None : NoteFoot
}

/** The reader sees when they read it, or the button that says they did; the writer sees whether the owner has. The owner's own notes show neither. */
fun noteFoot(ui: NotesUi, note: NoteDto): NoteFoot {
    val own = note.createdBy.id == ui.userId
    return when {
        ui.canRead && !own -> ui.readAt(note)?.let { NoteFoot.ReadAt(clockTime(it)) }
            ?: NoteFoot.MarkRead(note.id, enabled = ui.connected && note.id !in ui.marking)
        own && !ui.canRead -> note.reads.firstOrNull { it.by.id != ui.userId }?.let { NoteFoot.Seen(clockTime(it.at)) } ?: NoteFoot.NotSeen
        else -> NoteFoot.None
    }
}

// ---- Settings ----

class LanguageOption(val tag: String?, val label: String)

/** The app's order: the system's language first, then each one it can show. */
val languageOptions = listOf(
    LanguageOption(null, "lang_system"),
    LanguageOption("en", "lang_en"),
    LanguageOption("lo", "lang_lo"),
    LanguageOption("th", "lang_th"),
)

class RecordStartOption(val start: RecordStart, val label: String)

/** What Record starts with: income, expense, or the kind used last. */
val recordStartOptions = listOf(
    RecordStartOption(RecordStart.INCOME, "type_income"),
    RecordStartOption(RecordStart.EXPENSE, "type_expense"),
    RecordStartOption(RecordStart.LAST, "record_start_last"),
)

// ---- Record ----

/** The branch's name on this device, or its key when the shop no longer lists it. */
fun Workspace.branchName(key: String): String = branches.firstOrNull { it.key == key }?.displayName ?: key

/** A category's name in the page's language, or the key when the shop no longer lists it. Null for no category. */
fun categoryName(categories: List<CategoryDto>, key: String?, language: String): String? =
    key?.let { k -> categories.firstOrNull { it.key == k }?.name?.pick(language) ?: k }

fun recordIssueKey(issue: RecordIssue): String = when (issue) {
    RecordIssue.AMOUNT_MISSING -> "issue_amount_missing"
    RecordIssue.AMOUNT_INVALID -> "issue_amount_invalid"
    RecordIssue.SLIP_NEEDED -> "issue_slip_needed"
    RecordIssue.SLIP_NOT_ALLOWED -> "issue_slip_not_allowed"
    RecordIssue.TOO_MANY_SLIPS -> "issue_too_many_slips"
    RecordIssue.CATEGORY_NEEDED -> "issue_category_needed"
}

/** What the save is doing: its banner, the branch an unknown save belongs to, and the two ways out of an unknown result. */
class RecordPhaseView(
    val banner: Message?,
    val error: Boolean,
    val note: Message?,
    val checkAgain: Boolean,
    val checkEnabled: Boolean,
    val discard: Boolean,
)

fun recordPhaseView(ui: RecordUi, failureText: (Message) -> String, branchName: (String) -> String): RecordPhaseView = when (val phase = ui.phase) {
    SavePhase.Editing -> RecordPhaseView(null, false, null, false, false, false)
    SavePhase.Saving -> RecordPhaseView(Message("record_saving"), false, null, false, false, false)
    is SavePhase.Saved -> RecordPhaseView(
        Message(
            if (phase.entry.type == EntryType.INCOME) "record_saved_income" else "record_saved_expense",
            listOf(savedAmountText(phase.entry.tenders, ui.currencies)),
        ),
        false, null, false, false, false,
    )
    is SavePhase.Unknown -> RecordPhaseView(
        Message("record_unknown"), false,
        ui.pendingBranch?.let { Message("record_unknown_branch", listOf(branchName(it))) },
        true, ui.connected, true,
    )
    is SavePhase.Failed -> RecordPhaseView(Message("record_failed", listOf(failureText(phase.failure.message()))), true, null, false, false, false)
}

/** The amount the server will read from a payment line, or null when it is not an amount of [row]'s currency. */
fun tenderShown(row: TenderRow, currencies: List<CurrencyInfo>): String? {
    val currency = currencies.firstOrNull { it.code == row.currency } ?: return null
    val minor = parseTypedAmount(row.amount, currency.exponent) ?: return null
    return displayAmount(minor, currency.exponent) + " " + currency.code
}

class CheckBanner(val message: Message, val error: Boolean)

/** What the bank said about a slip: the transfer it knows, one paid to another account, or one it does not know. */
fun slipCheckBanner(check: SlipCheckDto, toText: (String) -> String = { it }): CheckBanner = when {
    !check.known -> CheckBanner(Message("slip_check_unknown", listOf(check.source)), error = true)
    check.toShop == false -> CheckBanner(Message("slip_check_not_shop", listOf(check.to ?: check.toAccount.orEmpty())), error = true)
    else -> {
        val details = listOfNotNull(
            check.amount?.let { groupThousands(it) + (check.currency?.let { c -> " $c" } ?: "") },
            check.time?.let(::bankTimeText),
            check.to?.let(toText),
        )
        CheckBanner(Message("slip_check_known", listOf(check.source, details.joinToString(" · "))), error = false)
    }
}

fun slipNoticeMessage(notice: SlipNotice, policy: RecordsPolicy): Message = when (notice) {
    SlipNotice.TOO_MANY -> Message("notice_too_many", listOf(policy.slipMaxCount.toString()))
    SlipNotice.TOO_BIG -> Message("notice_too_big", listOf(policy.slipMaxSizeKb.toString()))
    SlipNotice.UNREADABLE -> Message("notice_unreadable")
    SlipNotice.NO_QR -> Message("notice_no_qr")
    SlipNotice.ALREADY_ADDED -> Message("slip_attached_twice")
    SlipNotice.NOT_CONFIRMED -> Message("notice_not_confirmed")
}

// ---- History ----

fun rangeProblemKey(problem: RangeProblem): String = when (problem) {
    RangeProblem.NOT_A_DATE -> "range_not_a_date"
    RangeProblem.BACKWARDS -> "range_backwards"
    RangeProblem.TOO_LONG -> "range_too_long"
}

class DateGroup(val date: String, val entries: List<EntryDto>)

/** The entries as the server sent them (newest first), with one heading per date. */
fun historyGroups(entries: List<EntryDto>): List<DateGroup> =
    entries.groupBy { it.date }.map { (date, rows) -> DateGroup(date, rows) }

fun methodKey(method: TenderMethod): String = if (method == TenderMethod.CASH) "method_cash" else "method_online"

/** An entry's item, or its category's name, or a dash when it has neither. */
fun historyRowTitle(entry: EntryDto, categories: List<CategoryDto>, language: String): String =
    entry.item.ifBlank { categoryName(categories, entry.category, language) ?: "—" }

/** A change that did not happen; a cancelled PIN prompt already says "Not done", so it is not said twice. */
fun notDoneMessage(failure: Failure): Message =
    if (failure == Failure.Client(Problem.ReauthCancelled)) failure.message() else Message("detail_failed", detail = failure.message())
