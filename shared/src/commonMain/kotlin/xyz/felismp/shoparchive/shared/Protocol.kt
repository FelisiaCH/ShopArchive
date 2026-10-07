package xyz.felismp.shoparchive.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Version of the HTTP/WebSocket protocol; bumped when a client and server of different versions can no longer talk. */
const val PROTOCOL_VERSION = 2

/** Request header every client sends with [PROTOCOL_VERSION]; the server answers a missing or different one with [ErrorCode.PROTOCOL_MISMATCH]. */
const val PROTOCOL_HEADER = "X-ShopArchive-Protocol"

/** Body of `GET /api/v1/info`, the one endpoint a client can call before it knows the protocol. */
@Serializable
data class InfoResponse(
    val serverId: String,
    val name: String,
    val motd: String,
    val version: String,
    val protocol: Int,
)

@Serializable
enum class ErrorCode {
    PROTOCOL_MISMATCH,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    /** The body or a value in it is not acceptable (the message says why). */
    INVALID_REQUEST,
    /** A pairing secret or manual code that is wrong, used, expired or unknown; deliberately says nothing more. */
    PAIRING_INVALID,
    /** An important action needs the PIN or password to be entered again (`POST /api/v1/reauth`). */
    REAUTH_REQUIRED,
    /** A PIN or password this request meant to set was set by another request first. Ask the person for the existing one and send it with the same enrollment token. */
    CREDENTIALS_CHANGED,
    PAYLOAD_TOO_LARGE,
    BANNED,
    /** Too many requests from this address, or too many wrong tries on this account: wait the seconds in the `Retry-After` header. */
    RATE_LIMITED,
    /** The enrollment token was real but its time ran out: redeem a new pairing (ask for a new link or code). A wrong or used token is [UNAUTHORIZED]. */
    ENROLLMENT_EXPIRED,
    /** Adding a user to a shared device: the device id or device credential sent is not one the server accepts (the device was removed, or its user was). Not a wrong PIN; try another stored credential or pair again. */
    DEVICE_NOT_RECOGNIZED,
    /** The branch has no open day (never opened, or closed): open one before recording entries. */
    NO_OPEN_SESSION,
    /** The day session was closed already; it closes once. */
    SESSION_CLOSED,
    /** The request clashes with what is stored (e.g. an entry id that belongs to someone else). */
    CONFLICT,
    /** The entry's files are damaged or exist twice; it is left alone until the admin fixes it. */
    ENTRY_BROKEN,
    /** The disk would not take the write for several seconds (usually antivirus holding a file); nothing was changed, try again. */
    STORAGE_BUSY,
    INTERNAL,
}

/**
 * Why a request was refused, as keys the app turns into its own words (the server's [ErrorResponse.message] is English and
 * is for logs, the console and curl). Lowercase with dots and dashes; a key never changes meaning once released.
 * An app that meets a key it does not know falls back to a sentence for the [ErrorCode].
 */
object ErrorReasons {
    const val PIN_REQUIRED = "pin.required"
    const val PIN_LENGTH = "pin.length"
    const val PIN_REPEATED = "pin.repeated"
    const val PIN_SEQUENCE = "pin.sequence"
    /** `/login` for a user who has no PIN yet: send it again with `newPin`. */
    const val PIN_NOT_SET = "pin.not-set"
    const val PASSWORD_REQUIRED = "password.required"
    const val PASSWORD_SHORT = "password.short"
    const val PASSWORD_LONG = "password.long"
    const val PASSWORD_COMMON = "password.common"
    const val PASSWORD_HAS_USERNAME = "password.has-username"
    const val PASSWORD_HAS_SERVER_NAME = "password.has-server-name"

    const val PERMISSION_MISSING = "permission.missing"
    const val BRANCH_NOT_ALLOWED = "branch.not-allowed"
    const val BRANCH_UNKNOWN = "branch.unknown"
    const val BRANCH_ARCHIVED = "branch.archived"
    const val DEVICE_PERSONAL = "device.personal"
    const val DEVICE_HAS_OTHER_USERS = "device.has-other-users"
    const val COMMAND_CONSOLE_ONLY = "command.console-only"

    const val SESSION_NO_OPEN_DAY = "session.no-open-day"
    const val SESSION_CLOSED = "session.closed"

    const val ENTRY_ID_INVALID = "entry.id-invalid"
    const val ENTRY_ID_TAKEN = "entry.id-taken"
    const val ENTRY_NO_AMOUNT = "entry.no-amount"
    const val ENTRY_CURRENCY_UNKNOWN = "entry.currency-unknown"
    const val ENTRY_AMOUNT_INVALID = "entry.amount-invalid"
    const val ENTRY_SLIP_REQUIRED = "entry.slip-required"
    const val ENTRY_SLIP_NOT_ALLOWED = "entry.slip-not-allowed"
    const val ENTRY_SLIP_TOO_MANY = "entry.slip-too-many"
    const val ENTRY_SLIP_TYPE = "entry.slip-type"
    const val ENTRY_SLIP_UNKNOWN = "entry.slip-unknown"
    const val ENTRY_CATEGORY_REQUIRED = "entry.category-required"
    const val ENTRY_CATEGORY_UNKNOWN = "entry.category-unknown"
    const val ENTRY_CATEGORY_ARCHIVED = "entry.category-archived"
    const val ENTRY_CATEGORY_TYPE = "entry.category-type"
    const val ENTRY_CHANGED_ELSEWHERE = "entry.changed-elsewhere"
    const val ENTRY_DELETED = "entry.deleted"
    const val ENTRY_PAST_WINDOW = "entry.past-window"
    const val ENTRY_MOVE_FUTURE = "entry.move-future"
    const val ENTRY_MOVE_OCCUPIED = "entry.move-occupied"

    const val CLOSE_COUNT_MISSING = "close.count-missing"
    const val CLOSE_NOTE_NEEDED = "close.note-needed"
    const val CLOSE_CURRENCY_UNKNOWN = "close.currency-unknown"
    const val CLOSE_AMOUNT_INVALID = "close.amount-invalid"

    const val NOTIFICATION_PENDING = "notification.pending"

    const val DATE_INVALID = "date.invalid"
    const val RANGE_ORDER = "range.order"
    const val RANGE_TOO_LONG = "range.too-long"

    /** Every key above, for a client that must have words for each. */
    val all: List<String> = listOf(
        PIN_REQUIRED, PIN_LENGTH, PIN_REPEATED, PIN_SEQUENCE,
        PASSWORD_REQUIRED, PASSWORD_SHORT, PASSWORD_LONG, PASSWORD_COMMON, PASSWORD_HAS_USERNAME, PASSWORD_HAS_SERVER_NAME,
        PERMISSION_MISSING, BRANCH_NOT_ALLOWED, BRANCH_UNKNOWN, BRANCH_ARCHIVED, DEVICE_PERSONAL, DEVICE_HAS_OTHER_USERS, COMMAND_CONSOLE_ONLY,
        SESSION_NO_OPEN_DAY, SESSION_CLOSED,
        ENTRY_ID_INVALID, ENTRY_ID_TAKEN, ENTRY_NO_AMOUNT, ENTRY_CURRENCY_UNKNOWN, ENTRY_AMOUNT_INVALID, ENTRY_SLIP_REQUIRED,
        ENTRY_SLIP_NOT_ALLOWED, ENTRY_SLIP_TOO_MANY, ENTRY_SLIP_TYPE, ENTRY_SLIP_UNKNOWN, ENTRY_CATEGORY_REQUIRED, ENTRY_CATEGORY_UNKNOWN,
        ENTRY_CATEGORY_ARCHIVED, ENTRY_CATEGORY_TYPE, ENTRY_CHANGED_ELSEWHERE, ENTRY_DELETED, ENTRY_PAST_WINDOW, ENTRY_MOVE_FUTURE, ENTRY_MOVE_OCCUPIED,
        CLOSE_COUNT_MISSING, CLOSE_NOTE_NEEDED, CLOSE_CURRENCY_UNKNOWN, CLOSE_AMOUNT_INVALID,
        NOTIFICATION_PENDING,
        DATE_INVALID, RANGE_ORDER, RANGE_TOO_LONG,
    )
}

/** A rule that failed: the stable [reason] key ([ErrorReasons]) and the English [message] for the log. */
class Refusal(val reason: String, val message: String)

/**
 * Body of every error response. [protocol] is the server's [PROTOCOL_VERSION], always set, so a client that
 * gets [ErrorCode.PROTOCOL_MISMATCH] knows which version to ask the user to update to. [passwordRequired] is true
 * on [ErrorCode.REAUTH_REQUIRED] from `/unlock` when the PIN is not enough and the app must ask for the password.
 * [reason] is a stable machine key from [ErrorReasons] (null when the [code] says enough); the app words it in the user's
 * language. [message] stays English, for logs, the console and curl, and the app does not show it.
 */
@Serializable
data class ErrorResponse(
    val code: ErrorCode,
    val message: String,
    val protocol: Int,
    val passwordRequired: Boolean = false,
    val reason: String? = null,
)

/** What a QR code or `shoparchive://pair?d=<base64url of this JSON>` link carries. [fp] is the SHA-256 of the server certificate's public key as hex, no spaces: the pin every later connection is checked against. */
@Serializable
data class PairPayload(
    val v: Int,
    /** Server id (`data/server-id`). */
    val sid: String,
    val fp: String,
    /** One-time secret, base64url of 16 random bytes. */
    val sec: String,
    val u: String,
    /** Up to two `host:port` the server can be reached at. */
    val ep: List<String>,
)

/** Body of `POST /api/v1/pair/redeem`: either [secret] (from the QR or link) or [username] with [code] (typed in). */
@Serializable
data class RedeemRequest(val secret: String? = null, val username: String? = null, val code: String? = null)

/**
 * [enrollmentToken] works only on `POST /api/v1/enroll`. The flags tell the app which fields the enrollment needs.
 * [suggestedPasswordMin] (in characters; 0 means no suggestion) and [serverName] are for the app's hints about a weak new password: the server accepts any.
 */
@Serializable
data class RedeemResponse(
    val enrollmentToken: String,
    val username: String,
    val passwordRequired: Boolean,
    val hasPassword: Boolean,
    val hasPin: Boolean,
    val pinLength: Int,
    val suggestedPasswordMin: Int = 0,
    val serverName: String = "",
    /** Seconds [enrollmentToken] stays valid, counted by the server when it answered, so a wrong clock on the device does not matter. 0 means unknown. */
    val expiresInSeconds: Long = 0,
)

@Serializable
enum class DeviceMode {
    @SerialName("shared") SHARED,
    @SerialName("personal") PERSONAL,
}

/**
 * Body of `POST /api/v1/enroll`. [password] and [pin] are the ones the user already has; [newPassword] and [newPin]
 * set them when the user has none. A shared device adds a second user by sending its [deviceId] and one of its
 * [deviceCredential]s; the label, platform and mode of the device are then the ones it already has.
 */
@Serializable
data class EnrollRequest(
    val deviceLabel: String,
    val platform: String,
    val mode: DeviceMode,
    val password: String? = null,
    val newPassword: String? = null,
    val pin: String? = null,
    val newPin: String? = null,
    val deviceId: String? = null,
    val deviceCredential: String? = null,
)

/**
 * Body of `POST /api/v1/login`: no pairing, the [username] and the PIN (or the password if the user needs one) are enough.
 * [password] and [pin] are the ones the user already has; [newPassword] and [newPin] set them when the user has none (a user
 * without a PIN is told so with [ErrorReasons.PIN_NOT_SET]). A shared device adds a second user by sending its [deviceId] and one
 * of its [deviceCredential]s; the label, platform and mode of the device are then the ones it already has.
 */
@Serializable
data class LoginRequest(
    val username: String,
    val deviceLabel: String,
    val platform: String,
    val mode: DeviceMode,
    val pin: String? = null,
    val newPin: String? = null,
    val password: String? = null,
    val newPassword: String? = null,
    val deviceId: String? = null,
    val deviceCredential: String? = null,
)

/** The device credential is shown once: the device must keep it. */
@Serializable
data class EnrollResponse(val deviceId: String, val credential: String)

/** Body of `POST /api/v1/unlock`: the user's password if the user needs one, else the PIN; neither where [AuthPolicy.unlockWithoutPin] lets the credential alone do. */
@Serializable
data class UnlockRequest(
    val deviceId: String,
    val username: String,
    val credential: String,
    val pin: String? = null,
    val password: String? = null,
)

@Serializable
data class UnlockResponse(val accessToken: String, val expiresInSeconds: Int)

/** Body of `POST /api/v1/reauth`: the password if the user needs one, else the PIN. */
@Serializable
data class ReauthRequest(val pin: String? = null, val password: String? = null)

/**
 * One device in `GET /api/v1/devices` (the caller's own). [lastUsed] is when the caller last unlocked it, [userCount]
 * how many users it holds (a personal device holds one), [current] whether it is the device making the request.
 */
@Serializable
data class DeviceInfo(
    val id: String,
    val label: String,
    val platform: String,
    val mode: DeviceMode,
    val lastUsed: String,
    val userCount: Int,
    val current: Boolean,
)

/** Body of `PUT /api/v1/devices/{id}/mode`. */
@Serializable
data class SetModeRequest(val mode: DeviceMode)

/**
 * What the app does about login, from the server's config. [passwordRequired] is for the caller: false means the PIN alone unlocks.
 * [unlockWithoutPin]: a device that holds one user unlocks with its credential alone (false from a server that does not know it).
 */
@Serializable
data class AuthPolicy(
    val pinLength: Int,
    val passwordRequired: Boolean,
    val autoLockSharedMinutes: Int,
    val autoLockPersonalMinutes: Int,
    val biometricsPersonal: Boolean,
    val reauthWindowMinutes: Int,
    val unlockWithoutPin: Boolean = false,
)

/** A currency a record can be in; [exponent] is the number of decimal places of its smallest unit. */
@Serializable
data class CurrencyInfo(val code: String, val exponent: Int)

/**
 * Body of `GET /api/v1/config`. [endpoints] are up to two `host:port` the server can be reached at now.
 * [branches] are the ones the caller works in (all of them with `shoparchive.branch.all`); [categories] are all of them, archived ones marked.
 * [permissions] are the permission nodes the caller holds now (an op holds every registered one), so the app can leave out what the server would refuse.
 * [userId] is the caller's user id, which is what an entry's `createdBy.id` is compared with to tell the caller's own entries (a name can be renamed or reused).
 */
@Serializable
data class ConfigResponse(
    val auth: AuthPolicy,
    val endpoints: List<String>,
    val currencies: List<CurrencyInfo>,
    val branches: List<BranchDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val records: RecordsPolicy = RecordsPolicy(),
    val permissions: List<String> = emptyList(),
    val userId: String = "",
)

/** Body of `POST /api/v1/pairings`. */
@Serializable
data class CreatePairingRequest(val username: String)

/** [manualCode] is `XXXXX-XXXXX`, or null when the server has turned manual codes off. [fingerprint] is for the person to compare on screen. */
@Serializable
data class PairingResponse(val link: String, val manualCode: String?, val fingerprint: String, val expiresAt: String)

/** A message the server pushes on `/api/v1/ws`: JSON with a `type` field. */
@Serializable
sealed interface WsMessage

@Serializable
@SerialName("say")
data class SayMessage(val message: String, val from: String) : WsMessage

/** Another device of the same user was just paired. */
@Serializable
@SerialName("device.paired")
data class DevicePairedMessage(val deviceId: String, val label: String) : WsMessage

/** A record was written; pushed to the users who may see [branch]. [date] is the business date, in `GET /api/v1/entries/{date}/{id}`. */
@Serializable
@SerialName("entry.created")
data class EntryCreatedMessage(val id: String, val date: String, val branch: String) : WsMessage

@Serializable
@SerialName("entry.updated")
data class EntryUpdatedMessage(val id: String, val date: String, val branch: String) : WsMessage

@Serializable
@SerialName("entry.deleted")
data class EntryDeletedMessage(val id: String, val date: String, val branch: String) : WsMessage

/** [fromDate] is where the entry was; [date] is where it is now. */
@Serializable
@SerialName("entry.moved")
data class EntryMovedMessage(val id: String, val date: String, val fromDate: String, val branch: String) : WsMessage

@Serializable
@SerialName("session.opened")
data class SessionOpenedMessage(val id: String, val branch: String, val businessDate: String) : WsMessage

@Serializable
@SerialName("session.closed")
data class SessionClosedMessage(val id: String, val branch: String, val businessDate: String) : WsMessage

/** Body of `POST /api/v1/command`: one line as typed in the app's console. */
@Serializable
data class CommandRequest(val line: String)

/** What the command said, one entry per line. A line may hold a pairing link, which the app shows as a QR. */
@Serializable
data class CommandResponse(val lines: List<String>)

/** Body of `POST /api/v1/command/complete`: the line so far; its last word (empty after a space) is the one to complete. */
@Serializable
data class CompleteRequest(val line: String)

@Serializable
data class CompleteResponse(val candidates: List<String>)
