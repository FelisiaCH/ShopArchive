package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.ReauthRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val WRONG_PIN = "905173"
private const val BACKOFF = "config-version: 1\nauth:\n  backoff:\n    start-seconds: 30\n"

/** A wrong try from a caller that has proven no device of the account slows that address only; it never locks the account's own devices. */
class AnonymousBackoffTest {
    @TempDir
    lateinit var root: Path

    private fun AuthEnv.guess(ip: String, name: String = "mali") = runCatching {
        authService().login(
            LoginRequest(name, "Phone", "android", DeviceMode.PERSONAL, pin = WRONG_PIN, password = "Wrong-Password-1"), ip,
        )
    }.exceptionOrNull() as ApiError

    @Test
    fun anonymousWrongPinsDoNotLockTheOwnersDevice() {
        val env = AuthEnv(root, BACKOFF)
        val device = env.signIn("mali")
        val principal = env.authService().authenticate(env.token(device, "mali"))!!

        assertEquals(401, env.guess("10.0.0.9").status)
        assertEquals(429, env.guess("10.0.0.10").status)

        env.authService().reauth(principal, ReauthRequest(pin = TEST_PIN), "127.0.0.1")
        env.unlock(device, "mali")
        assertContains(env.audit(), "login.fail")
    }

    @Test
    fun repeatedAnonymousWrongPinsAreDelayedWhateverTheAddress() {
        val env = AuthEnv(root, BACKOFF)
        val device = env.signIn("mali")
        env.guess("10.0.0.9")

        val locked = env.guess("10.0.0.9")

        assertEquals(429, locked.status)
        assertEquals(30, locked.retryAfterSeconds)
        assertEquals(429, env.guess("10.0.0.10").status) // many addresses guess no faster than one
        env.unlock(device, "mali") // the owner's own unlock does not wipe the guesser's delay
        assertEquals(429, env.guess("10.0.0.11").status)
        env.clock.advance(Duration.ofSeconds(31))
        assertEquals(401, env.guess("10.0.0.10").status)
        assertEquals(429, env.guess("10.0.0.9").status)
    }

    @Test
    fun wrongPinsOnAKnownDeviceStillLockTheAccount() {
        val env = AuthEnv(root, BACKOFF)
        val device = env.signIn("mali")

        assertEquals(401, assertFailsWith<ApiError> { env.unlock(device, "mali", WRONG_PIN) }.status)

        assertEquals(429, assertFailsWith<ApiError> { env.unlock(device, "mali") }.status)
        assertEquals(429, env.guess("10.0.0.9").status) // an address that has not tried is refused too while the account is locked
    }

    @Test
    fun aWrongPinWithAnotherUsersCredentialOnASharedDeviceCountsAsAnonymous() {
        val env = AuthEnv(root, BACKOFF)
        val alice = env.signIn("alice", DeviceMode.SHARED)
        val bob = env.signIn("bob", DeviceMode.SHARED, onto = alice)

        val request = LoginRequest(
            "alice", "Phone", "android", DeviceMode.SHARED, pin = WRONG_PIN, password = "Wrong-Password-1",
            deviceId = bob.deviceId, deviceCredential = bob.credential,
        )
        assertEquals(401, assertFailsWith<ApiError> { env.authService().login(request, "10.0.0.9") }.status)

        assertEquals(0, env.users.user("alice").failedLogins)
        assertEquals(null, env.users.user("alice").lockedUntil)
        env.unlock(alice, "alice")
        assertEquals(429, assertFailsWith<ApiError> { env.authService().login(request, "10.0.0.10") }.status)
    }

    @Test
    fun aWrongPinWithTheDevicesOwnCredentialLocksTheAccountToo() {
        val env = AuthEnv(root, BACKOFF)
        val device = env.signIn("mali")

        val request = LoginRequest(
            "mali", "Phone", "android", DeviceMode.PERSONAL, pin = WRONG_PIN, password = "Wrong-Password-1",
            deviceId = device.deviceId, deviceCredential = device.credential,
        )
        assertEquals(401, assertFailsWith<ApiError> { env.authService().login(request, "10.0.0.9") }.status)

        assertEquals(429, assertFailsWith<ApiError> { env.unlock(device, "mali") }.status)
    }
}
