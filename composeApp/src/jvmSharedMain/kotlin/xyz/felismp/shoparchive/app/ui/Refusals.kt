package xyz.felismp.shoparchive.app.ui

import org.jetbrains.compose.resources.StringResource
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons

/**
 * The words for each reason key the server can send ([ErrorReasons]). The server's English text is never shown: the app says
 * it in the user's language from here, and a test keeps this table, [ErrorReasons.all] and the string files in step.
 */
internal val REASON_WORDS: Map<String, StringResource> = mapOf(
    ErrorReasons.PIN_REQUIRED to Res.string.reason_pin_required,
    ErrorReasons.PIN_LENGTH to Res.string.reason_pin_length,
    ErrorReasons.PIN_REPEATED to Res.string.reason_pin_repeated,
    ErrorReasons.PIN_SEQUENCE to Res.string.reason_pin_sequence,
    ErrorReasons.PIN_NOT_SET to Res.string.reason_pin_not_set,
    ErrorReasons.PASSWORD_REQUIRED to Res.string.reason_password_required,
    ErrorReasons.PASSWORD_SHORT to Res.string.reason_password_short,
    ErrorReasons.PASSWORD_LONG to Res.string.reason_password_long,
    ErrorReasons.PASSWORD_COMMON to Res.string.reason_password_common,
    ErrorReasons.PASSWORD_HAS_USERNAME to Res.string.reason_password_has_username,
    ErrorReasons.PASSWORD_HAS_SERVER_NAME to Res.string.reason_password_has_server_name,
    ErrorReasons.PERMISSION_MISSING to Res.string.reason_permission_missing,
    ErrorReasons.BRANCH_NOT_ALLOWED to Res.string.reason_branch_not_allowed,
    ErrorReasons.BRANCH_UNKNOWN to Res.string.reason_branch_unknown,
    ErrorReasons.BRANCH_ARCHIVED to Res.string.reason_branch_archived,
    ErrorReasons.COMMAND_CONSOLE_ONLY to Res.string.reason_command_console_only,
    ErrorReasons.DEVICE_PERSONAL to Res.string.reason_device_personal,
    ErrorReasons.DEVICE_HAS_OTHER_USERS to Res.string.reason_device_has_other_users,
    ErrorReasons.SESSION_NO_OPEN_DAY to Res.string.reason_session_no_open_day,
    ErrorReasons.SESSION_CLOSED to Res.string.reason_session_closed,
    ErrorReasons.ENTRY_ID_INVALID to Res.string.reason_entry_id_invalid,
    ErrorReasons.ENTRY_ID_TAKEN to Res.string.reason_entry_id_taken,
    ErrorReasons.ENTRY_NO_AMOUNT to Res.string.reason_entry_no_amount,
    ErrorReasons.ENTRY_CURRENCY_UNKNOWN to Res.string.reason_entry_currency_unknown,
    ErrorReasons.ENTRY_AMOUNT_INVALID to Res.string.reason_entry_amount_invalid,
    ErrorReasons.ENTRY_SLIP_REQUIRED to Res.string.reason_entry_slip_required,
    ErrorReasons.ENTRY_SLIP_NOT_ALLOWED to Res.string.reason_entry_slip_not_allowed,
    ErrorReasons.ENTRY_SLIP_TOO_MANY to Res.string.reason_entry_slip_too_many,
    ErrorReasons.ENTRY_SLIP_TYPE to Res.string.reason_entry_slip_type,
    ErrorReasons.ENTRY_SLIP_UNKNOWN to Res.string.reason_entry_slip_unknown,
    ErrorReasons.ENTRY_CATEGORY_REQUIRED to Res.string.reason_entry_category_required,
    ErrorReasons.ENTRY_CATEGORY_UNKNOWN to Res.string.reason_entry_category_unknown,
    ErrorReasons.ENTRY_CATEGORY_ARCHIVED to Res.string.reason_entry_category_archived,
    ErrorReasons.ENTRY_CATEGORY_TYPE to Res.string.reason_entry_category_type,
    ErrorReasons.ENTRY_CHANGED_ELSEWHERE to Res.string.reason_entry_changed_elsewhere,
    ErrorReasons.ENTRY_DELETED to Res.string.reason_entry_deleted,
    ErrorReasons.ENTRY_PAST_WINDOW to Res.string.reason_entry_past_window,
    ErrorReasons.ENTRY_MOVE_FUTURE to Res.string.reason_entry_move_future,
    ErrorReasons.ENTRY_MOVE_OCCUPIED to Res.string.reason_entry_move_occupied,
    ErrorReasons.CLOSE_COUNT_MISSING to Res.string.reason_close_count_missing,
    ErrorReasons.CLOSE_NOTE_NEEDED to Res.string.reason_close_note_needed,
    ErrorReasons.CLOSE_CURRENCY_UNKNOWN to Res.string.reason_close_currency_unknown,
    ErrorReasons.CLOSE_AMOUNT_INVALID to Res.string.reason_close_amount_invalid,
    ErrorReasons.NOTIFICATION_PENDING to Res.string.reason_notification_pending,
    ErrorReasons.DATE_INVALID to Res.string.reason_date_invalid,
    ErrorReasons.RANGE_ORDER to Res.string.reason_range_order,
    ErrorReasons.RANGE_TOO_LONG to Res.string.reason_range_too_long,
)

/** The sentence for an error [code] alone: used when the server gave no reason, or one this app does not know (a newer server). */
internal fun codeWords(code: ErrorCode): StringResource = when (code) {
        ErrorCode.PROTOCOL_MISMATCH -> Res.string.code_protocol_mismatch
        ErrorCode.UNAUTHORIZED -> Res.string.code_unauthorized
        ErrorCode.FORBIDDEN -> Res.string.code_forbidden
        ErrorCode.NOT_FOUND -> Res.string.code_not_found
        ErrorCode.INVALID_REQUEST -> Res.string.code_invalid_request
        ErrorCode.REAUTH_REQUIRED -> Res.string.code_reauth_required
        ErrorCode.CREDENTIALS_CHANGED -> Res.string.err_credentials_changed
        ErrorCode.PAYLOAD_TOO_LARGE -> Res.string.code_payload_too_large
        ErrorCode.BANNED -> Res.string.err_banned
        ErrorCode.RATE_LIMITED -> Res.string.err_too_many_later
        ErrorCode.NO_OPEN_SESSION -> Res.string.reason_session_no_open_day
        ErrorCode.SESSION_CLOSED -> Res.string.reason_session_closed
        ErrorCode.CONFLICT -> Res.string.code_conflict
        ErrorCode.ENTRY_BROKEN -> Res.string.code_entry_broken
        ErrorCode.STORAGE_BUSY -> Res.string.code_storage_busy
        ErrorCode.INTERNAL -> Res.string.code_internal
        ErrorCode.DEVICE_NOT_RECOGNIZED -> Res.string.err_device_rejected
}

/** What to tell the user about a refusal: the sentence for its [reason] if this app knows it, else the one for its [code]. */
internal fun refusalWords(code: ErrorCode, reason: String?): StringResource = reason?.let(REASON_WORDS::get) ?: codeWords(code)
