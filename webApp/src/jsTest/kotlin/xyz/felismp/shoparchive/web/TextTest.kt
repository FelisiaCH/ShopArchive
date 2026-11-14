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
}
