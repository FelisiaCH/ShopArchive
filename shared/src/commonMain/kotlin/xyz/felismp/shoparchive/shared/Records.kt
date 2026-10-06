package xyz.felismp.shoparchive.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- the vocabulary of records: the words in the files, the JSON and the code are the same ---

@Serializable
enum class EntryType {
    @SerialName("income") INCOME,
    @SerialName("expense") EXPENSE,
}

@Serializable
enum class TenderMethod {
    @SerialName("cash") CASH,
    @SerialName("online") ONLINE,
}

/** Which entry types a category may be used for. */
@Serializable
enum class AppliesTo {
    @SerialName("income") INCOME,
    @SerialName("expense") EXPENSE,
    @SerialName("both") BOTH,
}

fun AppliesTo.covers(type: EntryType): Boolean = this == AppliesTo.BOTH || name == type.name

@Serializable
enum class SessionStatus {
    @SerialName("open") OPEN,
    @SerialName("closed") CLOSED,
}

/** The word a value has in files and JSON. */
fun Enum<*>.wire(): String = name.lowercase()

/** An id the client makes or the server makes: a UUID of version 7, lower case. */
private val UUID_V7 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

fun isUuidV7(text: String): Boolean = UUID_V7.matches(text)

/** A key that names a branch or a category in file names and URLs; it never changes once made. */
private val SLUG = Regex("[a-z0-9-]{1,32}")

fun isSlug(text: String): Boolean = SLUG.matches(text)

/** Whether [text] is a date that exists, written `2026-09-27` (the days of each month and leap years are checked). */
fun isIsoDate(text: String): Boolean {
    val match = Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(text) ?: return false
    val (year, month, day) = match.destructured.toList().map { it.toInt() }
    if (month !in 1..12 || day < 1) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val length = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day <= length
}

// --- DTOs ---

/** Who did something: the account id, which never changes, and the name it had then. */
@Serializable
data class UserRef(val id: String, val name: String)

/** One payment: [amount] is text with exactly the currency's number of decimals. */
@Serializable
data class TenderDto(val currency: String, val method: TenderMethod, val amount: String)

@Serializable
data class SlipDto(val file: String, val sha256: String)

@Serializable
data class EntryDto(
    val id: String,
    val date: String,
    val type: EntryType,
    val branch: String,
    val category: String? = null,
    val item: String,
    val note: String,
    val createdBy: UserRef,
    val createdAt: String,
    val updatedAt: String,
    val deleted: Boolean,
    val tenders: List<TenderDto>,
    val slips: List<SlipDto>,
    /** The day session the entry belongs to; null when `records.require-open-day` is off and none was open. */
    val session: String? = null,
)

/** Body of `POST /api/v1/entries` (the `entry` part when slips come with it). [id] is a UUIDv7 the client makes, so a resend is the same entry. */
@Serializable
data class CreateEntryRequest(
    val id: String,
    val type: EntryType,
    val branch: String,
    val category: String? = null,
    val item: String = "",
    val note: String = "",
    val tenders: List<TenderDto>,
)

/** Body of `PUT /api/v1/entries/{date}/{id}`. [keepSlips] are the files (`slip-1.jpg`) that stay; null keeps all. Slips sent with the request are added. */
@Serializable
data class UpdateEntryRequest(
    val type: EntryType,
    val category: String? = null,
    val item: String = "",
    val note: String = "",
    val tenders: List<TenderDto>,
    val keepSlips: List<String>? = null,
    /** The entry's `updatedAt` the edit started from. When it is not the stored one any more the change is refused (409), so a newer change is never overwritten. Null skips the check. */
    val expectedUpdatedAt: String? = null,
)

@Serializable
data class MoveEntryRequest(val date: String)

@Serializable
data class HistoryChange(val field: String, val from: String, val to: String)

@Serializable
data class HistoryItem(val at: String, val by: UserRef, val action: String, val changes: List<HistoryChange>)

@Serializable
data class BranchDto(val key: String, val displayName: String, val archived: Boolean)

@Serializable
data class CreateBranchRequest(val key: String, val displayName: String)

@Serializable
data class UpdateBranchRequest(val displayName: String? = null, val archived: Boolean? = null)

@Serializable
data class LocalizedName(val lo: String, val th: String, val en: String)

@Serializable
data class CategoryDto(val key: String, val name: LocalizedName, val appliesTo: AppliesTo, val archived: Boolean)

@Serializable
data class CreateCategoryRequest(val key: String, val name: LocalizedName, val appliesTo: AppliesTo)

@Serializable
data class UpdateCategoryRequest(val name: LocalizedName? = null, val appliesTo: AppliesTo? = null, val archived: Boolean? = null)

/** Body of `POST /api/v1/sessions/{branch}/open`: the change counted in the drawer, per currency. A currency left out is 0. */
@Serializable
data class OpenSessionRequest(val float: Map<String, String> = emptyMap())

/** Body of `POST /api/v1/sessions/{branch}/{id}/close`: all the money counted in the drawer, per currency. [note] is required when anything differs. */
@Serializable
data class CloseSessionRequest(val counted: Map<String, String>, val note: String = "")

/** How a day session ended. Every map is per currency; [variance] has a minus sign when the drawer is short. */
@Serializable
data class SessionClose(
    val counted: Map<String, String>,
    val expected: Map<String, String>,
    val variance: Map<String, String>,
    val handover: Map<String, String>,
    /** Currencies where less was counted than the float: the handover is 0 and the app warns. */
    val belowFloor: List<String>,
    val note: String,
    val closedBy: UserRef,
    val closedAt: String,
)

/** What closing a day would compare the count with, per currency of the shop (all in decimal strings): [expected] = [float] + [cashIn] - [cashOut] of the session's entries. */
@Serializable
data class SessionPreview(
    val float: Map<String, String>,
    val cashIn: Map<String, String>,
    val cashOut: Map<String, String>,
    val expected: Map<String, String>,
)

@Serializable
data class SessionDto(
    val id: String,
    val branch: String,
    val businessDate: String,
    val openedBy: UserRef,
    val openedAt: String,
    val float: Map<String, String>,
    val status: SessionStatus,
    val close: SessionClose? = null,
)

/** Answer to opening a day. [alreadyOpenBy] is set when the day was open already: [session] is that one and nothing was changed. */
@Serializable
data class OpenSessionResponse(val session: SessionDto, val alreadyOpenBy: UserRef? = null)

/** The record rules a client needs to check an entry before it sends it. */
@Serializable
data class RecordsPolicy(
    val requireOpenDay: Boolean = true,
    val requireCategory: Boolean = false,
    val slipMaxCount: Int = 5,
    val slipMaxSizeKb: Int = 5120,
    /** How many days back a person may still change or delete their own entries (`records.edit-window-days`); 0 is the same day only. */
    val editWindowDays: Int = 0,
)

// --- rules shared by the server and the apps ---

class CategoryRule(val appliesTo: AppliesTo, val archived: Boolean)

/** What an entry is checked against. [exponents] maps each currency code to its decimals. */
class EntryRules(
    val exponents: Map<String, Int>,
    val categories: Map<String, CategoryRule>,
    val requireCategory: Boolean,
    val slipMaxCount: Int,
)

/**
 * The first thing wrong with an entry (a [Refusal]: a reason key and an English sentence), or null if it is acceptable:
 * at least one tender, each worth more than 0 in a known currency; slips exactly when something is paid online;
 * a category that exists, is not archived and fits the type (and is there at all when the rules ask for it).
 * Who may write the entry and how big a slip may be are for the server alone.
 */
fun checkEntry(type: EntryType, category: String?, tenders: List<TenderDto>, slipCount: Int, rules: EntryRules): Refusal? {
    if (tenders.isEmpty()) return Refusal(ErrorReasons.ENTRY_NO_AMOUNT, "An entry needs at least one amount.")
    for (tender in tenders) {
        val exponent = rules.exponents[tender.currency]
            ?: return Refusal(ErrorReasons.ENTRY_CURRENCY_UNKNOWN, "The currency '${tender.currency}' is not one this shop uses.")
        if (parseAmount(tender.amount, exponent) == null) {
            return Refusal(
                ErrorReasons.ENTRY_AMOUNT_INVALID,
                "The amount '${tender.amount}' is not valid for ${tender.currency}: write a number above 0" +
                    (if (exponent == 0) " without decimals." else " with at most $exponent decimals."),
            )
        }
    }
    val online = tenders.any { it.method == TenderMethod.ONLINE }
    if (online && slipCount == 0) return Refusal(ErrorReasons.ENTRY_SLIP_REQUIRED, "A payment made online needs a slip.")
    if (!online && slipCount > 0) return Refusal(ErrorReasons.ENTRY_SLIP_NOT_ALLOWED, "Slips are for payments made online only.")
    if (slipCount > rules.slipMaxCount) return Refusal(ErrorReasons.ENTRY_SLIP_TOO_MANY, "An entry takes at most ${rules.slipMaxCount} slips.")
    if (category == null) return if (rules.requireCategory) Refusal(ErrorReasons.ENTRY_CATEGORY_REQUIRED, "A category is needed.") else null
    val rule = rules.categories[category] ?: return Refusal(ErrorReasons.ENTRY_CATEGORY_UNKNOWN, "The category '$category' does not exist.")
    if (rule.archived) return Refusal(ErrorReasons.ENTRY_CATEGORY_ARCHIVED, "The category '$category' is not in use any more.")
    if (!rule.appliesTo.covers(type)) return Refusal(ErrorReasons.ENTRY_CATEGORY_TYPE, "The category '$category' is not for ${type.wire()} entries.")
    return null
}

/** `jpg` or `png` if [head] (the first bytes of a file) starts like one of them, else null. A file's name or its declared type proves nothing. */
fun detectSlipExtension(head: ByteArray): String? {
    fun starts(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it] == bytes[it].toByte() }
    return when {
        starts(0xFF, 0xD8, 0xFF) -> "jpg"
        starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
        else -> null
    }
}

/** The figures that close a day, for one currency, all in minor units. */
class CloseFigures(val expected: Long, val variance: Long, val handover: Long, val belowFloor: Boolean)

/**
 * Expected = [float] + [cashIn] - [cashOut]; variance = [counted] - expected; handover = counted - float, never below 0
 * ([CloseFigures.belowFloor] when it would have been). One formula for the app's preview and the server.
 */
fun closeFigures(float: Long, cashIn: Long, cashOut: Long, counted: Long): CloseFigures {
    val expected = float + cashIn - cashOut
    return CloseFigures(expected, counted - expected, maxOf(0L, counted - float), counted < float)
}

// --- notifications (P11): what the outbox shows to the Reports and Admin screens ---

/**
 * Where a message in the outbox is. [QUEUED]: waiting for its turn or for a retry; [SENT]: the channel took it; [UNKNOWN]: the channel
 * did not answer in time, so it may or may not have arrived - it is tried again by itself and this is not a failure; [FAILED]: given up
 * (the channel refused it for good, or every attempt was used) and waiting for someone to send it again.
 */
@Serializable
enum class NotificationState {
    @SerialName("queued") QUEUED,
    @SerialName("sent") SENT,
    @SerialName("unknown") UNKNOWN,
    @SerialName("failed") FAILED,
}

/**
 * One message of the outbox. [code] is printed in the message itself (it names the entry or the day), so a message that arrives twice
 * can be told as the same one; a message sent again has the same [code] and [resendOf] names the one it repeats.
 * [text] is the core's one-line description of it, not what the channel showed. [lastError] is the reason the channel gave last, if any.
 */
@Serializable
data class NotificationDto(
    val id: String,
    val code: String,
    val event: String,
    val state: NotificationState,
    val branch: String,
    val user: String,
    val createdAt: String,
    val attempts: Int,
    val failures: Int,
    val lastAttemptAt: String? = null,
    val nextAttemptAt: String? = null,
    val lastError: String? = null,
    val resendOf: String? = null,
    val text: String,
)
