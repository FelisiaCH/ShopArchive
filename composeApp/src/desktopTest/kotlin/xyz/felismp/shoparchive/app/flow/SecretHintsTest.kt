package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.RedeemResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretHintsTest {
    private fun hints(password: String, suggestedMin: Int = 8, server: String = "Khamsay Mart") = passwordHints(password, "mali", server, suggestedMin)

    @Test fun aStrongPasswordHasNoHint() {
        assertEquals(emptyList(), hints("tr1cky-umbrella-9"))
        assertEquals(emptyList(), hints(""))
    }

    @Test fun aShortPasswordSaysItsLengthAndTheSuggestedOne() {
        assertEquals(listOf(PasswordHint.Short(7, 8)), hints("seven-7"))
        assertEquals(emptyList(), hints("eight-ch"))
        assertEquals(listOf(PasswordHint.Short(14, 15)), hints("fourteen-chars", suggestedMin = 15))
        assertEquals(emptyList(), hints("fifteen-chars-ok", suggestedMin = 15))
        // Characters, not UTF-16 units, after NFC: e + accent is one.
        assertEquals(emptyList(), hints("e\u0301".repeat(8)))
        assertEquals(listOf(PasswordHint.Short(7, 8)), hints("e\u0301".repeat(7)))
        // 0 is an older server with no suggestion.
        assertEquals(emptyList(), hints("a", suggestedMin = 0))
    }

    @Test fun aCommonPasswordTheUserNameAndTheServerNameAreNamedInAnyCase() {
        assertEquals(listOf(PasswordHint.Common), hints("Password123"))
        assertEquals(listOf(PasswordHint.Common), hints("QWERTYUIOP"))
        assertEquals(listOf(PasswordHint.HasUserName("mali")), hints("my-MALI-secret-1"))
        assertEquals(listOf(PasswordHint.HasServerName("Khamsay Mart")), hints("khamsay MART rules 1"))
        // No server name known: nothing to compare with, and an empty name must not match everything.
        assertEquals(emptyList(), hints("tr1cky-umbrella-9", server = ""))
    }

    @Test fun everyProblemIsListedAndEachGoesWhenFixed() {
        assertEquals(listOf(PasswordHint.Short(4, 8), PasswordHint.HasUserName("mali")), hints("mali"))
        assertEquals(listOf(PasswordHint.HasUserName("mali")), hints("mali-and-a-long-one"))
    }

    @Test fun aPinNamesTheDigitOrTheEndsOfTheRunOnceAllItsDigitsAreTyped() {
        assertEquals(emptyList(), pinHints("482915", 6))
        assertEquals(listOf(PinHint.Repeated('1')), pinHints("111111", 6))
        assertEquals(listOf(PinHint.Run('1', '6')), pinHints("123456", 6))
        assertEquals(listOf(PinHint.Run('9', '4')), pinHints("987654", 6))
        assertEquals(listOf(PinHint.Run('1', '4')), pinHints("1234", 4))
        assertEquals(emptyList(), pinHints("1234", 6))
        assertEquals(emptyList(), pinHints("11111a", 6))
    }

    @Test fun aWeakSecretStillValidatesSoTheSubmitStaysOn() {
        val r = RedeemResponse("t", "mali", passwordRequired = true, hasPassword = false, hasPin = false, pinLength = 6, suggestedPasswordMin = 15)
        assertNull(validateEnroll(r, EnrollInput("mali", "mali", "111111", "111111", DeviceMode.PERSONAL, "PC")))
    }
}
