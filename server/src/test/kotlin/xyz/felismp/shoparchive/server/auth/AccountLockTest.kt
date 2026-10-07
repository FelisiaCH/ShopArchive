package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.users.isUsableCredential
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
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
import kotlin.test.assertTrue

private const val NEW_PIN = "135790"

/** Holds `hash` (which `login` calls inside its account lock) until the test lets go. */
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

/** `user reset` and `user disable` take the account lock a login holds, so they never run between a login's checks and its writes. */
class AccountLockTest {
    @TempDir
    lateinit var root: Path

    @AfterTest
    fun tearDown() = Log.close()

    private fun login(name: String, pin: String? = null, newPin: String? = null) =
        LoginRequest(name, "Phone", "android", DeviceMode.PERSONAL, pin = pin, newPin = newPin)

    @Test
    fun aLoginInsideTheLockWhileUserResetRunsLeavesNoUsableDevice() {
        val hasher = BlockingHasher()
        AuthEnv(root, hasher = hasher).run {
            addUser("mali")
            hasher.armed = true
            val outcome = AtomicReference<Any>()
            val loggingIn = thread {
                outcome.set(runCatching { authService().login(login("mali", newPin = NEW_PIN), "127.0.0.1") }.let { it.getOrNull() ?: it.exceptionOrNull() })
            }
            assertTrue(hasher.inside.await(10, TimeUnit.SECONDS), "login should be inside its lock")
            val resetting = thread { console("user reset mali") }
            // It has to wait for the login rather than run beside it.
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (resetting.state != Thread.State.BLOCKED && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(Thread.State.BLOCKED, resetting.state)

            hasher.release.countDown()
            loggingIn.join(10_000)
            resetting.join(10_000)

            val user = users.user("mali")
            assertFalse(isUsableCredential(user.pin))
            assertTrue(auth.devices.devicesOf(user.id).isEmpty())
            val result = outcome.get()
            if (result is LoginResponse) {
                assertFailsWith<ApiError> { unlock(result, "mali", NEW_PIN) }
            } else {
                assertTrue(result is ApiError, "login returned $result")
            }
        }
    }

    @Test
    fun aLoginWaitingOnTheAccountLockWhileUserResetRunsSeesTheAccountAfterTheReset() {
        val result = loginRacing("user reset mali")
        assertTrue(result is ApiError && result.status == 401 && result.reason == ErrorReasons.PIN_NOT_SET, "login returned $result")
    }

    @Test
    fun aLoginWaitingOnTheAccountLockWhileUserDisableRunsIsRefused() {
        val result = loginRacing("user disable mali")
        assertTrue(result is ApiError && result.status == 401 && result.code == ErrorCode.UNAUTHORIZED, "login returned $result")
    }

    /** The login has found the account and waits for its lock, which the console command holds while it runs; what the login returned or threw. */
    private fun loginRacing(command: String): Any? = AuthEnv(root).run {
        signIn("mali") // has a PIN and a device
        val outcome = AtomicReference<Any>()
        val loggingIn = thread(start = false) {
            outcome.set(runCatching { authService().login(login("mali", pin = TEST_PIN), "127.0.0.1") }.let { it.getOrNull() ?: it.exceptionOrNull() })
        }
        auth.sessions.withAccount(users.user("mali").id) {
            loggingIn.start()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (loggingIn.state != Thread.State.BLOCKED && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(Thread.State.BLOCKED, loggingIn.state, "login should wait for the account lock")
            console(command)
        }
        loggingIn.join(10_000)

        outcome.get()
    }
}
