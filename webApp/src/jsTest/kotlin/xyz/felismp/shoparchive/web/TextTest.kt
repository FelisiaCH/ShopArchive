package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextTest {
    private val tables = mapOf("en" to WebStrings.en, "th" to WebStrings.th, "lo" to WebStrings.lo)

    private fun assertWorded(key: String) {
        for ((lang, table) in tables) assertTrue(!table[key].isNullOrBlank(), "$key is missing or blank in $lang")
    }

    @Test
    fun formatArgumentsAreFilledInByPosition() {
        val t = Translator("en", mapOf("en" to mapOf("a" to "%2\$s then %1\$s, %1\$d%%")))
        assertEquals("y then x, x%", t.t("a", "x", "y"))
    }

    @Test
    fun aMissingKeyReadsInEnglishThenAsItself() {
        val t = Translator("th", mapOf("en" to mapOf("only.en" to "English"), "th" to emptyMap()))
        assertEquals("English", t.t("only.en"))
        assertEquals("nothing.here", t.t("nothing.here"))
    }

    @Test
    fun aMessageSaysItsDetailAfterIt() {
        val t = Translator("en", mapOf("en" to mapOf("a" to "A %1\$s.", "b" to "B.")))
        assertEquals("A 1. B.", t.text(Message("a", listOf("1"), detail = Message("b"))))
    }

    @Test
    fun theGeneratedStringsAreThere() {
        assertEquals("Log in", WebStrings.en["login_title"])
        assertWorded("login_title")
    }

    @Test
    fun aPluralUsesTheOnlyFormTheLanguageItselfHas() {
        val en = Translator("en", mapOf("en" to mapOf("k.one" to "%1\$d note", "k.other" to "%1\$d notes")))
        assertEquals("1 note", en.plural("k", 1, "1"))
        assertEquals("2 notes", en.plural("k", 2, "2"))

        // Thai has only "other": the English "one" would be wrong.
        val th = Translator("th", mapOf("en" to mapOf("k.one" to "%1\$d note", "k.other" to "notes"), "th" to mapOf("k.other" to "%1\$d โน้ต")))
        assertEquals("1 โน้ต", th.plural("k", 1, "1"))

        assertEquals("other", Translator("en", mapOf("en" to mapOf("k.other" to "other"))).plural("k", 1))
    }

    @Test
    fun theGeneratedPluralsAreThere() {
        assertTrue(WebStrings.en.getValue("notes_count.one").contains("note"))
        assertTrue(WebStrings.en.getValue("notes_count.other").contains("notes"))
        assertTrue(WebStrings.th["notes_count.one"] == null)
        assertTrue(WebStrings.lo["notes_count.one"] == null)
    }

    @Test
    fun everyProblemHasWordsInEveryLanguage() {
        val problems = listOf(
            Problem.Unreachable, Problem.TooManyAttempts(5), Problem.TooManyAttempts(null), Problem.Banned, Problem.WrongCredentials,
            Problem.CredentialsChanged, Problem.BadAddress, Problem.BadUsername, Problem.PinsDiffer, Problem.PinDigits, Problem.PinExactly(6),
            Problem.PersonalDevice, Problem.SessionEnded, Problem.DeviceRejected, Problem.ReauthCancelled,
            Problem.Rejected(ErrorCode.CONFLICT, ErrorReasons.PIN_SEQUENCE), Problem.PinMismatch, Problem.OtherServer, Problem.ProtocolMismatch, Problem.Unknown,
        )
        for (p in problems) assertWorded(p.message().key)
        assertEquals(listOf("6"), Problem.PinExactly(6).message().args)
        assertEquals(listOf("5"), Problem.TooManyAttempts(5).message().args)
        assertEquals("err_too_many_later", Problem.TooManyAttempts(null).message().key)
    }

    @Test
    fun everyErrorCodeAndEveryReasonHasWords() {
        for (code in ErrorCode.entries) assertWorded(codeKey(code))
        for (reason in ErrorReasons.all) assertWorded(reasonKey(reason))
    }

    @Test
    fun aReasonFromANewerServerFallsBackToTheCode() {
        assertEquals("code_conflict", refusalMessage(ErrorCode.CONFLICT, "from.a.newer.server").key)
        assertEquals("code_conflict", refusalMessage(ErrorCode.CONFLICT, null).key)
        assertEquals("reason_pin_sequence", refusalMessage(ErrorCode.INVALID_REQUEST, ErrorReasons.PIN_SEQUENCE).key)
        assertEquals("reason_pin_not_set", refusalMessage(ErrorCode.INVALID_REQUEST, ErrorReasons.PIN_NOT_SET).key)
    }

    @Test
    fun aFailureSaysWhatAProblemWouldSay() {
        assertEquals("err_unreachable", Failure.Unreachable.message().key)
        assertEquals("err_unknown", Failure.Other.message().key)
        assertEquals("err_pins_differ", Failure.Client(Problem.PinsDiffer).message().key)
        assertEquals("reason_session_closed", Failure.Refused(ErrorCode.SESSION_CLOSED, ErrorReasons.SESSION_CLOSED).message().key)
    }

    /** Every literal key the screens and views use, in all three languages (a typo would show as the key itself). */
    @Test
    fun theKeysTheScreensUseExist() {
        val keys = listOf(
            "app_name", "starting", "working", "retry", "cancel", "login_title", "add_user_title", "add_user_back", "username_label", "login_set_pin",
            "setup_code_label", "pin_new", "pin_repeat", "pin_field", "password_enter", "login_submit", "locked_title", "locked_body", "lock_pick_user",
            "lock_add_user", "lock_other_user", "unlock_password", "unlock_pin", "reauth_title", "reauth_body_password", "reauth_body_pin", "reauth_confirm",
            "pinmm_title", "cert_changed_body", "cert_trust", "proto_title", "proto_body", "proto_versions", "proto_unknown",
            "lock_now", "branch_label", "cfg_failed", "no_branch", "status_connecting", "offline_banner",
            "today_title", "today_failed", "today_empty", "today_latest", "today_mine", "today_mine_failed", "today_stats_failed",
            "today_in", "today_out", "today_net", "today_cash", "clock_off", "day_open_by", "day_closed", "day_round", "day_stale", "day_stale_other",
            "openday_card_title", "openday_card_body", "openday_card_body_other", "openday_btn", "openday_again_btn", "needs_connection",
            "openday_hint", "openday_kept_hint", "openday_amount", "openday_invalid", "openday_already", "openday_previous", "record_amount_shown", "go_today",
        )
        keys.forEach(::assertWorded)
    }

    /** Every key the Record and History screens use, in all three languages. */
    @Test
    fun theKeysTheRecordAndHistoryScreensUseExist() {
        val keys = listOf(
            "nav_today", "nav_record", "nav_history",
            "record_title", "block_no_day", "block_no_day_other", "openday_btn", "type_income", "type_expense",
            "category_label", "category_none", "item_label", "note_label", "tenders_title", "method_cash", "method_online",
            "amount_label", "record_amount_shown", "issue_amount_invalid", "issue_amount_missing", "issue_slip_needed", "issue_slip_not_allowed",
            "issue_too_many_slips", "issue_category_needed", "tender_remove", "tender_add",
            "slips_title", "slips_count", "slip_file", "slip_preparing", "detail_slip", "slip_remove", "slip_used_before", "slip_attached_twice",
            "slip_check_unknown", "slip_check_not_shop", "slip_check_known", "slip_check_to",
            "notice_too_many", "notice_too_big", "notice_unreadable", "notice_no_qr", "notice_not_confirmed",
            "record_saving", "record_saved_income", "record_saved_expense", "record_unknown", "record_unknown_branch",
            "record_check_again", "record_discard", "record_failed", "record_save", "needs_connection",
            "filter_range", "filter_from", "filter_to", "range_not_a_date", "range_backwards", "range_too_long",
            "history_failed", "history_empty", "history_deleted", "working", "retry", "back", "cancel",
            "detail_created", "detail_updated", "detail_delete", "detail_delete_ask", "detail_delete_body", "detail_delete_yes",
            "detail_move", "detail_move_date", "detail_move_to", "detail_move_yes", "detail_failed",
        )
        keys.forEach(::assertWorded)
    }

    /** Every key the Close day, Notes and Settings screens use, in all three languages. */
    @Test
    fun theKeysTheCloseNotesAndSettingsScreensUseExist() {
        val keys = listOf(
            "closeday_btn", "closeday_hint", "closeday_counted", "closeday_counted_short", "closeday_expected", "closeday_variance",
            "closeday_matches", "closeday_short", "closeday_over", "closeday_keep", "closeday_handover", "closeday_below_float",
            "closeday_below_float_done", "closeday_note", "closeday_note_required", "closeday_note_needed", "closeday_no_preview",
            "closeday_confirm", "closeday_failed", "closeday_none", "closeday_done", "closeday_note_is", "go_today",
            "nav_notes", "notes_title", "notes_sub", "notes_list_h", "notes_unread_n", "notes_unread", "notes_mark_read",
            "notes_mark_all", "notes_read_at", "notes_seen_owner", "notes_not_seen", "notes_empty_h", "notes_empty_p",
            "notes_composer", "notes_post", "notes_posting", "notes_post_failed_h", "notes_post_failed_p", "notes_foot",
            "notes_prev_day", "notes_next_day", "notes_load_failed", "notes_mark_failed",
            "nav_settings", "language_label", "lang_system", "lang_en", "lang_lo", "lang_th", "record_start_label",
            "record_start_last", "lock_now", "back",
        )
        keys.forEach(::assertWorded)
        assertTrue(WebStrings.en.containsKey("notes_count.one") && WebStrings.en.containsKey("notes_count.other"))
    }
}
