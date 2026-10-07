package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WRONG = "000000"

private fun backoff(disableAt: Int = 100, startSeconds: Int = 1, maxMinutes: Int = 1) =
    "config-version: 1\nauth:\n  backoff:\n    start-seconds: $startSeconds\n    max-minutes: $maxMinutes\n    disable-at: $disableAt\n"

/** [NO_BACKOFF] with the given periodic re-auth limits (the defaults are 0: off). */
private fun reauth(everyDays: Int = 30, idleDays: Int = 14) =
    "$NO_BACKOFF  session:\n    reauth-every-days: $everyDays\n    reauth-idle-days: $idleDays\n"

/** The account side of login policy: delays between wrong tries, re-entering the password now and then, idle devices, and ending access at once. */
class AccountPolicyTest {
    @TempDir
    lateinit var root: Path

    private fun AuthEnv.wrongTry(device: xyz.felismp.shoparchive.shared.EnrollResponse, name: String = "mali") =
        assertFailsWith<ApiError> { unlock(device, name, pin = WRONG) }

    private fun AuthEnv.userFile(name: String) = Files.readString(root.resolve("user/$name.yml"))

    private fun deviceFile(id: String) = Files.readString(root.resolve("data/devices/$id.yml"))

    // --- backoff ---

    @Test
    fun eachWrongTryDoublesTheDelayUpToTheMaximumAndALockedAccountCostsNoHash() {
        val hasher = CountingHasher()
        val env = AuthEnv(root, backoff(), hasher = hasher)
        val device = env.enroll("mali")

        for (try_ in 1..8) {
            assertEquals(401, env.wrongTry(device).status, "try $try_")
            val delay = minOf(1L shl (try_ - 1), 60L) // 1, 2, 4 ... capped at max-minutes: 1
            val verifiesBefore = hasher.verifies

            val locked = assertFailsWith<ApiError> { env.unlock(device, "mali") }

            assertEquals(429, locked.status)
            assertEquals(ErrorCode.RATE_LIMITED, locked.code)
            assertEquals(delay.toInt(), locked.retryAfterSeconds, "try $try_")
            assertEquals(verifiesBefore, hasher.verifies, "a locked account is refused before any hash is computed")
            // One second short: still locked.
            env.clock.advance(Duration.ofSeconds(delay - 1))
            assertEquals(429, assertFailsWith<ApiError> { env.unlock(device, "mali") }.status)
            env.clock.advance(Duration.ofSeconds(1))
        }

        env.unlock(device, "mali") // the lock is over, and the right PIN resets the count
        assertContains(env.userFile("mali"), "failed-logins: 0")
        assertContains(env.userFile("mali"), "locked-until: null")
        env.wrongTry(device)
        assertEquals(1, assertFailsWith<ApiError> { env.unlock(device, "mali") }.retryAfterSeconds, "the delay starts again at the first step")
    }

    @Test
    fun theCountAndTheLockAreInTheUserFileSoARestartKeepsThem() {
        val env = AuthEnv(root, backoff(startSeconds = 60))
        val device = env.enroll("mali")
        env.wrongTry(device)
        assertContains(env.userFile("mali"), "failed-logins: 1")
        assertContains(env.userFile("mali"), "locked-until: 2026-10-03T08:01:00Z")

        val restarted = AuthEnv(root, null, env.clock)

        assertEquals(429, assertFailsWith<ApiError> { restarted.unlock(device, "mali") }.status)
        restarted.clock.advance(Duration.ofSeconds(60))
        restarted.unlock(device, "mali")
    }

    @Test
    fun theLockIsPerAccountSoAnotherDeviceOfTheSameUserIsLockedToo() {
        val env = AuthEnv(root, backoff(startSeconds = 30))
        val phone = env.enroll("mali")
        val tablet = env.enroll("mali")

        env.wrongTry(phone)

        assertEquals(429, assertFailsWith<ApiError> { env.unlock(tablet, "mali") }.status)
        env.addUser("kham")
        env.unlock(env.enroll("kham"), "kham") // another account is not slowed down
    }

    @Test
    fun aDelayOfZeroTurnsTheDelayOffButStillCounts() {
        val env = AuthEnv(root, backoff(startSeconds = 0))
        val device = env.enroll("mali")

        repeat(3) { assertEquals(401, env.wrongTry(device).status) }

        assertContains(env.userFile("mali"), "failed-logins: 3")
        env.unlock(device, "mali")
    }

    @Test
    fun reachingDisableAtDisablesTheAccountEndsItsTokensAndUserUnlockBringsItBack() {
        val env = AuthEnv(root, backoff(disableAt = 5, startSeconds = 0))
        val device = env.enroll("mali")
        val token = env.token(device, "mali")

        repeat(5) { env.wrongTry(device) }

        val user = env.users.find("mali")!!
        assertFalse(user.enabled)
        assertEquals("backoff", user.disabledReason)
        assertContains(env.userFile("mali"), "disabled-reason: backoff")
        assertNull(env.authService().authenticate(token))
        assertContains(env.audit(), "account.disabled user=mali")
        assertEquals(401, assertFailsWith<ApiError> { env.unlock(device, "mali") }.status)

        assertEquals(listOf("User 'mali': wrong tries and lock cleared, enabled again"), env.console("user unlock mali"))

        assertTrue(env.users.find("mali")!!.enabled)
        assertEquals(0, env.users.find("mali")!!.failedLogins)
        env.unlock(device, "mali")
        // The token from before stays dead: it ended with the disabling.
        assertNull(env.authService().authenticate(token))
    }

    @Test
    fun aDisableAtOfZeroNeverDisablesTheAccount() {
        val env = AuthEnv(root, backoff(disableAt = 0, startSeconds = 0))
        val device = env.enroll("mali")

        repeat(200) { assertEquals(401, env.wrongTry(device).status) }

        assertTrue(env.users.find("mali")!!.enabled)
        assertContains(env.userFile("mali"), "failed-logins: 200")

        // Turned on, the count so far is already past it: the next wrong try disables the account.
        env.reconfigure(backoff(disableAt = 5, startSeconds = 0))
        env.wrongTry(device)
        assertFalse(env.users.find("mali")!!.enabled)
        assertEquals("backoff", env.users.find("mali")!!.disabledReason)
    }

    @Test
    fun userUnlockClearsTheLockOfAnEnabledAccountAndLeavesAnAdminsDisableAlone() {
        val env = AuthEnv(root, backoff(startSeconds = 60))
        val device = env.enroll("mali")
        env.wrongTry(device)

        assertEquals(listOf("User 'mali': wrong tries and lock cleared"), env.console("user unlock mali"))
        env.unlock(device, "mali")

        env.console("user disable mali")
        assertEquals("admin", env.users.find("mali")!!.disabledReason)
        assertEquals(listOf("User 'mali': wrong tries and lock cleared"), env.console("user unlock mali"))
        assertFalse(env.users.find("mali")!!.enabled, "unlock does not undo what an admin did")
        env.console("user enable mali")
        assertNull(env.users.find("mali")!!.disabledReason)
        env.unlock(device, "mali")
    }

    @Test
    fun aWrongProofAtEnrollmentCountsAndALockedAccountCannotEnroll() {
        val hasher = CountingHasher()
        val env = AuthEnv(root, backoff(startSeconds = 60), hasher = hasher)
        env.enroll("mali")
        val token = env.pairingService().redeem(RedeemRequest(secret = env.pair("mali").secret), "127.0.0.1").enrollmentToken
        fun enroll(pin: String) = env.authService().enroll(token, EnrollRequest("Tablet", "android", DeviceMode.PERSONAL, pin = pin), "127.0.0.1")

        assertEquals(401, assertFailsWith<ApiError> { enroll(WRONG) }.status)
        val verifies = hasher.verifies
        val locked = assertFailsWith<ApiError> { enroll(TEST_PIN) }

        assertEquals(429, locked.status)
        assertEquals(60, locked.retryAfterSeconds)
        assertEquals(verifies, hasher.verifies)
        env.clock.advance(Duration.ofSeconds(60))
        enroll(TEST_PIN)
        assertContains(env.userFile("mali"), "failed-logins: 0")
    }

    @Test
    fun aWrongReauthCountsToo() {
        val env = AuthEnv(root, backoff(startSeconds = 60))
        val device = env.enroll("mali")
        val token = env.token(device, "mali")
        val principal = env.authService().authenticate(token)!!

        assertEquals(401, assertFailsWith<ApiError> { env.authService().reauth(principal, xyz.felismp.shoparchive.shared.ReauthRequest(pin = WRONG), "127.0.0.1") }.status)

        assertEquals(429, assertFailsWith<ApiError> { env.authService().reauth(principal, xyz.felismp.shoparchive.shared.ReauthRequest(pin = TEST_PIN), "127.0.0.1") }.status)
    }

    // --- periodic re-auth ---

    @Test
    fun aUserWithAPasswordEntersItAgainAfterReauthEveryDaysOrIdleDaysAndAPinIsNotCountedWrongThen() {
        val env = AuthEnv(root, reauth())
        val device = env.enroll("mali")
        val user = env.users.find("mali")!!
        assertTrue(env.users.setCredentials(user.id, Hasher(1, TEST_COST).hash(TEST_PASSWORD), null))
        env.unlock(device, "mali") // the PIN is enough while the password is fresh

        // 15 days without use: past reauth-idle-days (14), within reauth-every-days (30).
        env.clock.advance(Duration.ofDays(15))
        val due = assertFailsWith<ApiError> { env.unlock(device, "mali") }
        assertEquals(401, due.status)
        assertEquals(ErrorCode.REAUTH_REQUIRED, due.code)
        assertTrue(due.passwordRequired)
        assertContains(deviceFile(device.deviceId), "pin-failures: 0")
        assertContains(env.userFile("mali"), "failed-logins: 0")

        env.unlock(device, "mali", pin = null, password = TEST_PASSWORD)
        env.unlock(device, "mali") // fresh again: the PIN works

        // Used every 13 days the idle rule never fires, but 30 days after the password the every-days rule does.
        repeat(2) {
            env.clock.advance(Duration.ofDays(13))
            env.unlock(device, "mali")
        }
        env.clock.advance(Duration.ofDays(13))
        assertEquals(ErrorCode.REAUTH_REQUIRED, assertFailsWith<ApiError> { env.unlock(device, "mali") }.code)
        env.unlock(device, "mali", pin = null, password = TEST_PASSWORD)

        // A wrong password is a wrong try like any other.
        env.clock.advance(Duration.ofDays(31))
        assertEquals(ErrorCode.UNAUTHORIZED, assertFailsWith<ApiError> { env.unlock(device, "mali", pin = null, password = "not the password 1") }.code)
        assertContains(env.userFile("mali"), "failed-logins: 1")
    }

    @Test
    fun reauthDaysOfZeroAreOffAndEachTurnsOnByItself() {
        val env = AuthEnv(root, reauth(everyDays = 0, idleDays = 0))
        val device = env.enroll("mali")
        assertTrue(env.users.setCredentials(env.users.find("mali")!!.id, Hasher(1, TEST_COST).hash(TEST_PASSWORD), null))
        env.unlock(device, "mali")

        env.clock.advance(Duration.ofDays(1000))
        env.unlock(device, "mali") // both off: the PIN is enough, however long ago the password was entered

        // Only every-days: the password was last entered 1000 days ago.
        env.reconfigure(reauth(everyDays = 30, idleDays = 0))
        assertEquals(ErrorCode.REAUTH_REQUIRED, assertFailsWith<ApiError> { env.unlock(device, "mali") }.code)
        env.unlock(device, "mali", pin = null, password = TEST_PASSWORD)

        // Only idle-days: 15 days without use, the password entered as long ago.
        env.reconfigure(reauth(everyDays = 0, idleDays = 14))
        env.clock.advance(Duration.ofDays(15))
        assertEquals(ErrorCode.REAUTH_REQUIRED, assertFailsWith<ApiError> { env.unlock(device, "mali") }.code)
        env.unlock(device, "mali", pin = null, password = TEST_PASSWORD)
    }

    @Test
    fun aPinFromAUserWhoAlwaysNeedsThePasswordIsAnsweredWithAPasswordRequestNotCountedWrong() {
        val env = AuthEnv(root)
        val device = env.enroll("mali")
        val user = env.users.find("mali")!!
        assertTrue(env.users.setCredentials(user.id, Hasher(1, TEST_COST).hash(TEST_PASSWORD), null))
        env.users.setOp("mali", true) // ops always need the password (auth.password-required-for)

        repeat(10) {
            val asked = assertFailsWith<ApiError> { env.unlock(device, "mali") }
            assertEquals(ErrorCode.REAUTH_REQUIRED, asked.code)
            assertTrue(asked.passwordRequired)
        }

        assertContains(deviceFile(device.deviceId), "pin-failures: 0")
        assertContains(env.userFile("mali"), "failed-logins: 0")
        env.unlock(device, "mali", pin = null, password = TEST_PASSWORD)
    }

    @Test
    fun theUnlockAnswerOverHttpTellsTheAppThatAPasswordIsNeeded() = AuthEnv(root, reauth()).run {
        val device = enroll("mali")
        assertTrue(users.setCredentials(users.find("mali")!!.id, Hasher(1, TEST_COST).hash(TEST_PASSWORD), null))
        clock.advance(Duration.ofDays(15))
        api {
            val request = UnlockRequest(device.deviceId, "mali", device.credential, pin = TEST_PIN)

            val due = postJson("/api/v1/unlock", UnlockRequest.serializer(), request)
            val wrong = postJson("/api/v1/unlock", UnlockRequest.serializer(), request.copy(credential = "nope"))

            assertEquals(401, due.status.value)
            val body = due.parsed(ErrorResponse.serializer())
            assertEquals(ErrorCode.REAUTH_REQUIRED, body.code)
            assertTrue(body.passwordRequired)
            assertFalse(wrong.parsed(ErrorResponse.serializer()).passwordRequired)
        }
    }

    @Test
    fun aUserWithoutAPasswordKeepsUnlockingWithThePinAndAUserWhoNeedsOneAlwaysUsesIt() {
        val env = AuthEnv(root)
        val kham = env.enroll("kham")
        env.addUser("boss", op = true)
        val boss = env.enroll("boss")

        env.clock.advance(Duration.ofDays(60))

        env.unlock(kham, "kham")
        assertEquals(ErrorCode.REAUTH_REQUIRED, assertFailsWith<ApiError> { env.unlock(boss, "boss") }.code, "an op's PIN is never enough: the app is asked for the password")
        env.unlock(boss, "boss", pin = null, password = TEST_PASSWORD)
    }

    // --- idle devices ---

    @Test
    fun aUserNotUsedOnADeviceForIdleExpiryDaysIsRefusedAndTheirBlockIsRemovedWhileTheOthersStay() {
        val env = AuthEnv(root, "$NO_BACKOFF  device:\n    idle-expiry-days: 90\n")
        val shared = env.enroll("mali", DeviceMode.SHARED)
        val kham = env.enroll("kham", DeviceMode.SHARED, onto = shared)
        env.clock.advance(Duration.ofDays(80))
        env.unlock(kham, "kham") // kham is used on day 80
        env.clock.advance(Duration.ofDays(11)) // day 91: mali has not been used for 91 days, kham for 11

        val refused = assertFailsWith<ApiError> { env.unlock(shared, "mali") }

        assertEquals(401, refused.status)
        assertFalse("  mali:" in deviceFile(shared.deviceId))
        assertTrue("  kham:" in deviceFile(shared.deviceId))
        assertContains(env.audit(), "device.expired user=mali device=${shared.deviceId} ip=127.0.0.1 result=idle")
        assertEquals(401, assertFailsWith<ApiError> { env.unlock(shared, "mali") }.status)
        env.unlock(kham, "kham")
    }

    @Test
    fun theIdleLimitIsConfig() {
        val env = AuthEnv(root, "config-version: 1\nauth:\n  device:\n    idle-expiry-days: 2\n  backoff:\n    start-seconds: 0\n")
        val device = env.enroll("mali")

        env.clock.advance(Duration.ofDays(1))
        env.unlock(device, "mali")
        env.clock.advance(Duration.ofDays(3))

        assertEquals(401, assertFailsWith<ApiError> { env.unlock(device, "mali") }.status)
    }

    @Test
    fun anIdleLimitOfZeroNeverExpiresAUser() {
        val env = AuthEnv(root, "$NO_BACKOFF  device:\n    idle-expiry-days: 0\n")
        val device = env.enroll("mali")

        env.clock.advance(Duration.ofDays(1000))
        env.unlock(device, "mali")

        env.reconfigure("$NO_BACKOFF  device:\n    idle-expiry-days: 2\n")
        env.clock.advance(Duration.ofDays(3))
        assertEquals(401, assertFailsWith<ApiError> { env.unlock(device, "mali") }.status)
    }

    // --- ending access at once ---

    @Test
    fun disablingChangingTheRoleAndResettingEndTheTokensAtOnce() {
        val env = AuthEnv(root)
        env.console("role create cashier")
        val device = env.enroll("mali")

        val disabled = env.token(device, "mali")
        env.console("user disable mali")
        env.console("user enable mali")
        assertNull(env.authService().authenticate(disabled), "enabling again does not bring a token back")

        val roled = env.token(device, "mali")
        assertNotNull(env.authService().authenticate(roled))
        env.console("user role mali cashier")
        assertNull(env.authService().authenticate(roled))

        val reset = env.token(device, "mali")
        env.console("user reset mali")
        assertNull(env.authService().authenticate(reset))
    }

    // --- user reset, devices ---

    @Test
    fun userResetTakesTheUserOffEveryDeviceClearsPasswordAndPinAndShowsNoPairing() {
        val env = AuthEnv(root, backoff(startSeconds = 60))
        val phone = env.enroll("mali")
        val shared = env.enroll("mali", DeviceMode.SHARED)
        val kham = env.enroll("kham", DeviceMode.SHARED, onto = shared)
        val token = env.token(phone, "mali")
        env.wrongTry(phone) // a lock and a count that the reset also forgets
        env.terminal.clear()

        val replies = env.console("user reset mali")

        assertEquals(listOf("User 'mali' reset: off 2 devices, password and PIN cleared. They set a new PIN at their next login."), replies)
        assertTrue(replies.none { "shoparchive://" in it || "Manual code" in it || '█' in it }, replies.toString())
        assertTrue(env.terminal.isEmpty(), env.terminal.toString())
        assertEquals(0, env.auth.pairing.pendingCount())
        assertFalse("  mali:" in deviceFile(phone.deviceId))
        assertFalse("  mali:" in deviceFile(shared.deviceId))
        assertTrue("  kham:" in deviceFile(shared.deviceId))
        val user = env.users.find("mali")!!
        assertNull(user.pin)
        assertNull(user.password)
        assertEquals(0, user.failedLogins)
        assertNull(user.lockedUntil)
        assertNull(env.authService().authenticate(token))
        assertEquals(401, assertFailsWith<ApiError> { env.unlock(phone, "mali") }.status)
        assertContains(env.audit(), "account.reset user=mali")
        // The user chooses a new PIN at the next login, and the other user of the shared device is untouched.
        val pinNotSet = assertFailsWith<ApiError> { env.authService().login(LoginRequest("mali", "Phone", "android", DeviceMode.PERSONAL), "127.0.0.1") }
        assertEquals(ErrorReasons.PIN_NOT_SET, pinNotSet.reason)
        env.unlock(env.authService().login(LoginRequest("mali", "Phone", "android", DeviceMode.PERSONAL, newPin = TEST_PIN), "127.0.0.1"), "mali")
        env.unlock(kham, "kham")
        // An unknown user changes nothing.
        assertEquals(listOf("No user 'nobody'"), env.console("user reset nobody"))
        assertEquals(listOf("Usage: user reset <name>"), env.console("user reset"))
    }

    @Test
    fun userResetAlsoBringsBackAnAccountTheServerDisabled() {
        val env = AuthEnv(root, backoff(disableAt = 5, startSeconds = 0))
        val device = env.enroll("mali")
        repeat(5) { env.wrongTry(device) }
        assertFalse(env.users.find("mali")!!.enabled)

        env.console("user reset mali")

        assertTrue(env.users.find("mali")!!.enabled)
        assertTrue(env.terminal.isEmpty())
        env.authService().login(LoginRequest("mali", "Phone", "android", DeviceMode.PERSONAL, newPin = TEST_PIN), "127.0.0.1")
    }

    @Test
    fun devicesListsTheDevicesOfAUserWithModeAndLastUse() {
        val env = AuthEnv(root)
        val phone = env.enroll("mali")
        val shared = env.enroll("mali", DeviceMode.SHARED)
        env.clock.advance(Duration.ofHours(1))
        env.unlock(shared, "mali")

        val lines = env.console("devices mali")

        assertEquals(2, lines.size)
        assertContains(lines.single { it.startsWith(phone.deviceId) }, "Phone of mali (android, personal), last used 2026-10-03T08:00:00Z")
        assertContains(lines.single { it.startsWith(shared.deviceId) }, "(android, shared), last used 2026-10-03T09:00:00Z")
        assertEquals(listOf("'kham' is on no device"), env.also { it.addUser("kham") }.console("devices kham"))
        assertEquals(listOf("No user 'nobody'"), env.console("devices nobody"))
        assertEquals(listOf("Usage: devices <name>"), env.console("devices"))
    }
}
