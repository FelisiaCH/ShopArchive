package xyz.felismp.shoparchive.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.users.isUsableCredential
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.RedeemResponse
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NEW_PIN = "135790"

/** Holds `hash` (which `enroll` calls inside its account lock) until the test lets go. */
private class BlockingHasher : Hasher(2, TEST_COST) {
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)

    @Volatile var armed = false

    override fun hash(secret: String): String {
        if (armed) {
            armed = false
            inside.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test never released the hasher" }
        }
        return super.hash(secret)
    }
}

/**
 * `user reset` and `user disable` end every enrollment grant of the account, not only its access tokens. A pending pairing is no
 * longer cancelled by them: after a reset anyone with the name sets the PIN at login anyway, and pairing itself is going away.
 */
class ResetRevokesEnrollmentTest {
    @TempDir
    lateinit var root: Path

    @AfterTest
    fun tearDown() = Log.close()

    private suspend fun ApplicationTestBuilder.redeem(secret: String) =
        postJson("/api/v1/pair/redeem", RedeemRequest.serializer(), RedeemRequest(secret = secret))

    private suspend fun ApplicationTestBuilder.enroll(token: String, newPin: String? = null, pin: String? = null) =
        postJson("/api/v1/enroll", EnrollRequest.serializer(), EnrollRequest("Phone", "android", DeviceMode.PERSONAL, pin = pin, newPin = newPin), token)

    private suspend fun ApplicationTestBuilder.grant(env: AuthEnv, name: String): RedeemResponse =
        redeem(env.pair(name).secret).parsed(RedeemResponse.serializer())

    @Test
    fun anEnrollmentTokenHeldBeforeUserResetIsDeadAndTheUserSetsANewPinAtLogin() = env().run {
        enroll("mali") // has a PIN and a device
        api {
            val held = grant(this@run, "mali")

            console("user reset mali")

            // The account has no PIN now, so a stale grant would let its holder choose one.
            assertEquals(HttpStatusCode.Unauthorized, enroll(held.enrollmentToken, newPin = NEW_PIN).status)
            assertFalse(isUsableCredential(users.user("mali").pin))
            assertTrue(auth.devices.devicesOf(users.user("mali").id).isEmpty())

            assertTrue(terminal.isEmpty(), "the reset shows no pairing")
            val login = postJson("/api/v1/login", LoginRequest.serializer(), LoginRequest("mali", "Phone", "android", DeviceMode.PERSONAL, newPin = NEW_PIN))
            assertEquals(HttpStatusCode.OK, login.status)
            assertTrue(isUsableCredential(users.user("mali").pin))
        }
    }

    @Test
    fun anEnrollInsideTheLockWhileUserResetRunsLeavesNoUsableDeviceForTheOldGrant() {
        val hasher = BlockingHasher()
        AuthEnv(root, hasher = hasher).run {
            addUser("mali")
            val token = pairingService().redeem(RedeemRequest(secret = pair("mali").secret), "127.0.0.1").enrollmentToken
            hasher.armed = true
            val outcome = AtomicReference<Any>()
            val enrolling = thread {
                outcome.set(runCatching { authService().enroll(token, EnrollRequest("Phone", "android", DeviceMode.PERSONAL, newPin = NEW_PIN), "127.0.0.1") }.let { it.getOrNull() ?: it.exceptionOrNull() })
            }
            assertTrue(hasher.inside.await(10, TimeUnit.SECONDS), "enroll should be inside its lock")
            val resetting = thread { console("user reset mali") }
            // It has to wait for the enroll rather than run beside it.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (resetting.state != Thread.State.BLOCKED && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(Thread.State.BLOCKED, resetting.state)

            hasher.release.countDown()
            enrolling.join(10_000)
            resetting.join(10_000)

            val user = users.user("mali")
            assertFalse(isUsableCredential(user.pin))
            assertTrue(auth.devices.devicesOf(user.id).isEmpty())
            val result = outcome.get()
            if (result is xyz.felismp.shoparchive.shared.EnrollResponse) {
                assertFailsWith<ApiError> { unlock(result, "mali", NEW_PIN) }
            } else {
                assertTrue(result is ApiError, "enroll returned $result")
            }
            assertEquals(EnrollmentLookup.Unknown, sessions().enrollment(token))
        }
    }

    @Test
    fun aRedeemWaitingOnTheAccountLockWhileUserResetRunsSeesTheAccountAfterTheReset() {
        // The pairing outlives the reset now, but the redeem waits for the reset to finish: its grant is not one the reset left half-revoked.
        val result = redeemRacing("user reset mali")
        assertTrue(result is RedeemResponse && !result.hasPin && !result.hasPassword, "redeem returned $result")
    }

    @Test
    fun aRedeemWaitingOnTheAccountLockWhileUserDisableRunsGetsNoGrant() {
        val result = redeemRacing("user disable mali")
        assertTrue(result is ApiError && result.status == 401 && result.code == ErrorCode.PAIRING_INVALID, "redeem returned $result")
    }

    /** The redeem has found its pairing and waits for the account lock, which the console command holds when it revokes; what the redeem returned or threw. */
    private fun redeemRacing(command: String): Any? = AuthEnv(root).run {
        addUser("mali")
        val secret = pair("mali").secret
        val outcome = AtomicReference<Any>()
        val redeeming = thread(start = false) {
            outcome.set(runCatching { pairingService().redeem(RedeemRequest(secret = secret), "127.0.0.1") }.let { it.getOrNull() ?: it.exceptionOrNull() })
        }
        sessions().withAccount(users.user("mali").id) {
            redeeming.start()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (redeeming.state != Thread.State.BLOCKED && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(Thread.State.BLOCKED, redeeming.state, "redeem should wait for the account lock")
            console(command)
        }
        redeeming.join(10_000)

        outcome.get()
    }

    @Test
    fun anEnrollmentTokenHeldBeforeUserDisableStaysDeadAfterEnableAndAPairingIsNoWayInWithoutThePin() = env().run {
        enroll("mali")
        api {
            val held = grant(this@run, "mali")
            val pending = pair("mali")

            console("user disable mali")

            assertEquals(HttpStatusCode.Unauthorized, enroll(held.enrollmentToken, pin = TEST_PIN).status)

            console("user enable mali")

            // Enabling again must not bring the old grant back.
            assertEquals(HttpStatusCode.Unauthorized, enroll(held.enrollmentToken, pin = TEST_PIN).status)
            // The pairing outlives the disable now, but its grant still needs the PIN the user has.
            val again = redeem(pending.secret)
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(HttpStatusCode.Unauthorized, enroll(again.parsed(RedeemResponse.serializer()).enrollmentToken).status)
            assertEquals(HttpStatusCode.OK, redeem(pair("mali").secret).status)
        }
    }

    private fun env() = AuthEnv(root)

    private fun AuthEnv.sessions() = auth.sessions
}
