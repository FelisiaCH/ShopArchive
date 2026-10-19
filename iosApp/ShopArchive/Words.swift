import Foundation
import ClientCore

/// The language the app's text is in: the one chosen in Settings, else the system's. text() reads it; RootView draws everything again when it changes.
enum Language {
    private(set) static var bundle = Bundle.main
    private(set) static var locale = Locale.current

    /// `tag` is en, lo or th; nil follows the system.
    static func choose(_ tag: String?) {
        if let tag, let path = Bundle.main.path(forResource: tag, ofType: "lproj"), let chosen = Bundle(path: path) {
            bundle = chosen
            locale = Locale(identifier: tag)
        } else {
            bundle = .main
            locale = .current
        }
    }
}

/// The text for a key of Localizable.strings, which is generated from the Android/Windows strings.xml; `args` fill its %1$@ / %1$d.
func text(_ key: String) -> String {
    Language.bundle.localizedString(forKey: key, value: nil, table: nil)
}

func text(_ key: String, _ args: CVarArg...) -> String {
    String(format: text(key), locale: Language.locale, arguments: args)
}

/// What to tell the user about a problem; the same words as Compose's Problem.text().
func problemText(_ problem: any Problem) -> String {
    switch onEnum(of: problem) {
    case .unreachable: return text("err_unreachable")
    case .tooManyAttempts(let p):
        guard let seconds = p.seconds else { return text("err_too_many_later") }
        return text("err_too_many", String(seconds.intValue))
    case .banned: return text("err_banned")
    case .wrongCredentials: return text("err_wrong_credentials")
    case .credentialsChanged: return text("err_credentials_changed")
    case .badAddress: return text("err_bad_address")
    case .badUsername: return text("err_bad_username")
    case .pinsDiffer: return text("err_pins_differ")
    case .pinDigits: return text("err_pin_digits")
    case .pinExactly(let p): return text("err_pin_exact", p.length)
    case .personalDevice: return text("err_personal_device")
    case .sessionEnded: return text("err_session_ended")
    case .deviceRejected: return text("err_device_rejected")
    case .reauthCancelled: return text("err_reauth_cancelled")
    case .rejected(let p): return refusalWords(code: p.code, reason: p.reason)
    case .pinMismatch: return text("pinmm_body")
    case .otherServer: return text("err_other_server")
    case .protocolMismatch: return text("proto_body")
    case .unknown: return text("err_unknown")
    }
}

/// What to tell the user about a failed call; the same words as Compose's Failure.text().
func failureText(_ failure: any Failure) -> String {
    switch onEnum(of: failure) {
    case .unreachable: return text("err_unreachable")
    case .refused(let f): return refusalWords(code: f.code, reason: f.reason)
    case .client(let f): return problemText(f.problem)
    case .other: return text("err_unknown")
    }
}

/// What to tell the user about a refusal: the sentence for its reason if this app knows it, else the one for its code (a newer server's reason is not known here).
private func refusalWords(code: ErrorCode, reason: String?) -> String {
    text(reason.flatMap { reasonKeys[$0] } ?? codeKey(code))
}

/// The string key for each reason the server can send (ErrorReasons); mirrors REASON_WORDS in Refusals.kt.
private let reasonKeys: [String: String] = {
    let r = ErrorReasons.shared
    return [
        r.PIN_REQUIRED: "reason_pin_required",
        r.PIN_LENGTH: "reason_pin_length",
        r.PIN_REPEATED: "reason_pin_repeated",
        r.PIN_SEQUENCE: "reason_pin_sequence",
        r.PIN_NOT_SET: "reason_pin_not_set",
        r.PASSWORD_REQUIRED: "reason_password_required",
        r.PASSWORD_SHORT: "reason_password_short",
        r.PASSWORD_LONG: "reason_password_long",
        r.PASSWORD_COMMON: "reason_password_common",
        r.PASSWORD_HAS_USERNAME: "reason_password_has_username",
        r.PASSWORD_HAS_SERVER_NAME: "reason_password_has_server_name",
        r.PERMISSION_MISSING: "reason_permission_missing",
        r.BRANCH_NOT_ALLOWED: "reason_branch_not_allowed",
        r.BRANCH_UNKNOWN: "reason_branch_unknown",
        r.BRANCH_ARCHIVED: "reason_branch_archived",
        r.COMMAND_CONSOLE_ONLY: "reason_command_console_only",
        r.DEVICE_PERSONAL: "reason_device_personal",
        r.DEVICE_HAS_OTHER_USERS: "reason_device_has_other_users",
        r.SESSION_NO_OPEN_DAY: "reason_session_no_open_day",
        r.SESSION_CLOSED: "reason_session_closed",
        r.ENTRY_ID_INVALID: "reason_entry_id_invalid",
        r.ENTRY_ID_TAKEN: "reason_entry_id_taken",
        r.ENTRY_NO_AMOUNT: "reason_entry_no_amount",
        r.ENTRY_CURRENCY_UNKNOWN: "reason_entry_currency_unknown",
        r.ENTRY_AMOUNT_INVALID: "reason_entry_amount_invalid",
        r.ENTRY_SLIP_REQUIRED: "reason_entry_slip_required",
        r.ENTRY_SLIP_NOT_ALLOWED: "reason_entry_slip_not_allowed",
        r.ENTRY_SLIP_TOO_MANY: "reason_entry_slip_too_many",
        r.ENTRY_SLIP_TYPE: "reason_entry_slip_type",
        r.ENTRY_SLIP_UNKNOWN: "reason_entry_slip_unknown",
        r.ENTRY_SLIP_CODE_INVALID: "reason_entry_slip_code_invalid",
        r.ENTRY_TRANSFER_UNCONFIRMED: "notice_not_confirmed",
        r.ENTRY_CATEGORY_REQUIRED: "reason_entry_category_required",
        r.ENTRY_CATEGORY_UNKNOWN: "reason_entry_category_unknown",
        r.ENTRY_CATEGORY_ARCHIVED: "reason_entry_category_archived",
        r.ENTRY_CATEGORY_TYPE: "reason_entry_category_type",
        r.ENTRY_CHANGED_ELSEWHERE: "reason_entry_changed_elsewhere",
        r.ENTRY_DELETED: "reason_entry_deleted",
        r.ENTRY_PAST_WINDOW: "reason_entry_past_window",
        r.ENTRY_MOVE_FUTURE: "reason_entry_move_future",
        r.ENTRY_MOVE_OCCUPIED: "reason_entry_move_occupied",
        r.CLOSE_COUNT_MISSING: "reason_close_count_missing",
        r.CLOSE_NOTE_NEEDED: "reason_close_note_needed",
        r.CLOSE_CURRENCY_UNKNOWN: "reason_close_currency_unknown",
        r.CLOSE_AMOUNT_INVALID: "reason_close_amount_invalid",
        r.NOTIFICATION_PENDING: "reason_notification_pending",
        r.USER_NOT_FOUND: "reason_user_not_found",
        r.USER_EXISTS: "reason_user_exists",
        r.USER_NAME_INVALID: "reason_user_name_invalid",
        r.USER_SELF_RESET: "reason_user_self_reset",
        r.ROLE_NOT_FOUND: "reason_role_not_found",
        r.KEY_INVALID: "reason_key_invalid",
        r.KEY_EXISTS: "reason_key_exists",
        r.NAME_REQUIRED: "reason_name_required",
        r.DATE_INVALID: "reason_date_invalid",
        r.RANGE_ORDER: "reason_range_order",
        r.RANGE_TOO_LONG: "reason_range_too_long",
    ]
}()

/// The string key for an error code alone; mirrors codeWords in Refusals.kt.
private func codeKey(_ code: ErrorCode) -> String {
    switch code {
    case .protocolMismatch: return "code_protocol_mismatch"
    case .unauthorized: return "code_unauthorized"
    case .forbidden: return "code_forbidden"
    case .notFound: return "code_not_found"
    case .invalidRequest: return "code_invalid_request"
    case .reauthRequired: return "code_reauth_required"
    case .credentialsChanged: return "err_credentials_changed"
    case .payloadTooLarge: return "code_payload_too_large"
    case .banned: return "err_banned"
    case .rateLimited: return "err_too_many_later"
    case .noOpenSession: return "reason_session_no_open_day"
    case .sessionClosed: return "reason_session_closed"
    case .conflict: return "code_conflict"
    case .entryBroken: return "code_entry_broken"
    case .storageBusy: return "code_storage_busy"
    case .internal: return "code_internal"
    case .deviceNotRecognized: return "err_device_rejected"
    }
}
