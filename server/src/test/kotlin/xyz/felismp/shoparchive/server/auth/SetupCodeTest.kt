package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.users.isUsableCredential
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LoginRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BACKOFF = "config-version: 1\nauth:\n  backoff:\n    start-seconds: 30\n"

/** An account with no PIN is claimed with the setup code the owner got from `user add` or `user reset`, once. */
class SetupCodeTest {
    @TempDir
    lateinit var root: Path

    private fun AuthEnv.claim(code: String?, ip: String = "10.0.0.9", pin: String = TEST_PIN) = runCatching {
        authService().login(LoginRequest("mali", "Phone", "android", DeviceMode.PERSONAL, newPin = pin, setupCode = code), ip)
    }

    private fun AuthEnv.refusal(code: String?, ip: String = "10.0.0.9") = assertFailsWith<ApiError> { claim(code, ip).getOrThrow() }

    @Test
    fun noCodeOrAWrongCodeIsRefusedWithTheReasonAndSetsNothing() {
        val env = AuthEnv(root)
        env.addUser("mali")

        for (code in listOf(null, "", "000000", "12345", "2468101")) {
            val refused = env.refusal(code)
            assertEquals(401, refused.status)
            assertEquals(ErrorReasons.SETUP_CODE, refused.reason)
        }
        assertNull(env.users.user("mali").pin)
        assertContains(env.audit(), "wrong-setup-code")
    }

    @Test
    fun aWrongCodeFromAnonymousCallerCountsAsAWrongTry() {
        val env = AuthEnv(root, BACKOFF)
        env.addUser("mali")

        assertEquals(401, env.refusal("000000").status)

        val delayed = env.refusal(TEST_CODE, "10.0.0.10")
        assertEquals(429, delayed.status)
        assertEquals(0, env.users.user("mali").failedLogins, "an anonymous try never counts against the account itself")
        env.clock.advance(Duration.ofSeconds(31))
        env.claim(TEST_CODE, "10.0.0.10").getOrThrow()
    }

    @Test
    fun theRightCodeSetsThePinClearsTheCodeAndCannotBeUsedAgain() {
        val env = AuthEnv(root)
        env.addUser("mali")
        assertNotNull(env.users.user("mali").setupCode)

        val device = env.claim(TEST_CODE).getOrThrow()

        env.unlock(device, "mali")
        val user = env.users.user("mali")
        assertTrue(Hasher(1, TEST_COST).verify(TEST_PIN, user.pin))
        assertNull(user.setupCode)
        // The PIN is asked for now, and the spent code is no way in.
        assertEquals(401, env.refusal(TEST_CODE).status)
    }

    @Test
    fun userResetMakesANewCodeAndTheOldOneStopsWorking() {
        val env = AuthEnv(root)
        env.addUser("mali")
        env.claim(TEST_CODE).getOrThrow()

        val code = env.console("user reset mali").printedSetupCode()

        assertEquals(ErrorReasons.SETUP_CODE, env.refusal(TEST_CODE).reason)
        env.claim(code, pin = "739152").getOrThrow()
        assertNull(env.users.user("mali").setupCode)
    }

    @Test
    fun userAddPrintsACodeThatWorks() {
        val env = AuthEnv(root)

        val code = env.console("user add mali").printedSetupCode()

        assertEquals(6, code.length)
        env.claim(code).getOrThrow()
    }

    @Test
    fun anOldPinlessUserWithNoCodeIsClosedUntilUserReset() {
        val env = AuthEnv(root)
        env.users.addUser("mali", "none", emptyList())

        assertEquals(ErrorReasons.SETUP_CODE, env.refusal(TEST_CODE).reason)
        assertEquals(ErrorReasons.SETUP_CODE, env.refusal(null).reason)
        assertNull(env.users.user("mali").pin)

        env.claim(env.console("user reset mali").printedSetupCode()).getOrThrow()
    }

    @Test
    fun theCodeIsKeptAsAHashAndSurvivesAReload() {
        val env = AuthEnv(root)
        env.addUser("mali")

        env.users.load()

        val stored = env.users.user("mali").setupCode
        assertTrue(isUsableCredential(stored))
        assertContains(env.filesUnder("user")["mali.yml"]!!, "setup-code: \"$stored\"")
        assertTrue(Hasher(1, TEST_COST).verify(TEST_CODE, stored))
    }
}
