package xyz.felismp.shoparchive.server.auth

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val OTHER_PIN = "905173"

/** `POST /api/v1/login`: a name and a PIN give a device credential. */
class LoginTest {
    @TempDir
    lateinit var root: Path

    private fun loginRequest(
        name: String, pin: String? = null, newPin: String? = null, mode: DeviceMode = DeviceMode.PERSONAL, onto: LoginResponse? = null,
        deviceCredential: String? = onto?.credential,
    ) = LoginRequest(name, "Phone of $name", "android", mode, pin = pin, newPin = newPin, deviceId = onto?.deviceId, deviceCredential = deviceCredential)

    private suspend fun ApplicationTestBuilder.login(request: LoginRequest) = postJson("/api/v1/login", LoginRequest.serializer(), request)

    private suspend fun ApplicationTestBuilder.loggedIn(request: LoginRequest): LoginResponse {
        val response = login(request)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.parsed(LoginResponse.serializer())
    }

    private suspend fun ApplicationTestBuilder.unlock(device: LoginResponse, name: String, pin: String) =
        postJson("/api/v1/unlock", UnlockRequest.serializer(), UnlockRequest(device.deviceId, name, device.credential, pin = pin))

    private suspend fun HttpResponse.answer() = status to parsed(ErrorResponse.serializer())

    @Test
    fun anUnknownUserADisabledUserAndAWrongPinGetTheSameAnswer() = AuthEnv(root).run {
        addUser("mali")
        addUser("dana")
        api {
            loggedIn(loginRequest("mali", newPin = TEST_PIN))
            loggedIn(loginRequest("dana", newPin = TEST_PIN))
            console("user disable dana")

            val wrongPin = login(loginRequest("mali", pin = OTHER_PIN)).answer()
            val unknown = login(loginRequest("ghost", pin = TEST_PIN)).answer()
            val disabled = login(loginRequest("dana", pin = TEST_PIN)).answer()
            val invalidName = login(loginRequest("no such name!", pin = TEST_PIN)).answer()

            assertEquals(HttpStatusCode.Unauthorized, wrongPin.first)
            assertEquals(ErrorCode.UNAUTHORIZED, wrongPin.second.code)
            for (other in listOf(unknown, disabled, invalidName)) {
                assertEquals(wrongPin.first, other.first)
                assertEquals(wrongPin.second, other.second)
            }
        }
    }

    @Test
    fun aUserWithoutAPinIsToldSoAndSetsItInTheSameRequest() = AuthEnv(root).run {
        addUser("mali")
        api {
            val notSet = login(loginRequest("mali", pin = TEST_PIN))
            assertEquals(HttpStatusCode.Unauthorized, notSet.status)
            assertEquals(ErrorCode.UNAUTHORIZED, notSet.errorCode())
            assertEquals(ErrorReasons.PIN_NOT_SET, notSet.errorReason())

            val device = loggedIn(loginRequest("mali", newPin = TEST_PIN))

            assertEquals(HttpStatusCode.OK, unlock(device, "mali", TEST_PIN).status)
            assertTrue(Hasher(1, TEST_COST).verify(TEST_PIN, users.find("mali")!!.pin), "the PIN is set")
            // From now on the PIN is asked for, and a new one is no way in.
            loggedIn(loginRequest("mali", pin = TEST_PIN))
            assertEquals(HttpStatusCode.Unauthorized, login(loginRequest("mali", newPin = OTHER_PIN)).status)
        }
    }

    @Test
    fun aUserMadeWithUserAddLogsInWithTheNameAndSetsTheirOwnPin() = AuthEnv(root).run {
        console("user add staff1")
        api {
            val notSet = login(loginRequest("staff1"))
            assertEquals(HttpStatusCode.Unauthorized, notSet.status)
            assertEquals(ErrorReasons.PIN_NOT_SET, notSet.errorReason())

            val device = loggedIn(loginRequest("staff1", newPin = TEST_PIN))
            assertEquals(HttpStatusCode.OK, unlock(device, "staff1", TEST_PIN).status)
        }
    }

    @Test
    fun afterUserResetTheOldDeviceIsDeadAndTheUserSetsANewPinAtLogin() = AuthEnv(root).run {
        addUser("staff1")
        api {
            val old = loggedIn(loginRequest("staff1", newPin = TEST_PIN))

            console("user reset staff1")

            assertEquals(HttpStatusCode.Unauthorized, unlock(old, "staff1", TEST_PIN).status)
            val notSet = login(loginRequest("staff1"))
            assertEquals(HttpStatusCode.Unauthorized, notSet.status)
            assertEquals(ErrorReasons.PIN_NOT_SET, notSet.errorReason())
            val device = loggedIn(loginRequest("staff1", newPin = OTHER_PIN))
            assertEquals(HttpStatusCode.OK, unlock(device, "staff1", OTHER_PIN).status)
        }
    }

    @Test
    fun twoParallelFirstLoginsSetOnePin() {
        // Both requests are under way when the first one reaches the hash; with the account lock the second waits, so this times out.
        val bothIn = CountDownLatch(2)
        val hasher = object : Hasher(4, TEST_COST) {
            override fun hash(secret: String): String {
                bothIn.countDown()
                bothIn.await(2, TimeUnit.SECONDS)
                return super.hash(secret)
            }
        }
        val env = AuthEnv(root, hasher = hasher)
        env.addUser("mali")
        val pins = listOf(TEST_PIN, OTHER_PIN)

        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            pins.map { pin ->
                pool.submit<Result<LoginResponse>> { runCatching { env.authService().login(loginRequest("mali", newPin = pin), "127.0.0.1") } }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, results.count { it.isSuccess }, "exactly one login sets the PIN: $results")
        val winner = results.indexOfFirst { it.isSuccess }
        val loser = assertNotNull(results[1 - winner].exceptionOrNull() as? ApiError)
        assertTrue(
            (loser.status == 409 && loser.code == ErrorCode.CREDENTIALS_CHANGED) || (loser.status == 401 && loser.code == ErrorCode.UNAUTHORIZED),
            "${loser.status} ${loser.code}",
        )
        val stored = env.users.find("mali")!!.pin
        assertTrue(hasher.verify(pins[winner], stored))
        assertFalse(hasher.verify(pins[1 - winner], stored))
        env.unlock(results[winner].getOrThrow(), "mali", pins[winner])
    }

    @Test
    fun aWrongPinLocksTheAccount() = AuthEnv(root, "config-version: 1\nauth:\n  backoff:\n    start-seconds: 30\n").run {
        addUser("mali")
        api {
            loggedIn(loginRequest("mali", newPin = TEST_PIN))
            assertEquals(HttpStatusCode.Unauthorized, login(loginRequest("mali", pin = OTHER_PIN)).status)

            val locked = login(loginRequest("mali", pin = TEST_PIN))

            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            assertEquals(ErrorCode.RATE_LIMITED, locked.errorCode())
            assertEquals("30", locked.headers[HttpHeaders.RetryAfter])
        }
    }

    @Test
    fun anOwnerWhosePinTheConsoleSetLogsInWithIt() = AuthEnv(root, "config-version: 1\nauth:\n  password:\n    required-for: []\n  backoff:\n    start-seconds: 0\n").run {
        addUser("owner", op = true)
        // What the console does for the owner: the hash goes straight into the account, no device is involved.
        assertTrue(users.setCredentials(users.find("owner")!!.id, null, Hasher(1, TEST_COST).hash(OTHER_PIN)))
        api {
            val device = loggedIn(loginRequest("owner", pin = OTHER_PIN))

            assertEquals(HttpStatusCode.OK, unlock(device, "owner", OTHER_PIN).status)
        }
    }

    @Test
    fun aSecondUserJoinsASharedDeviceButNotAPersonalOne() = AuthEnv(root).run {
        addUser("mali")
        addUser("dana")
        addUser("noa")
        api {
            val shared = loggedIn(loginRequest("mali", newPin = TEST_PIN, mode = DeviceMode.SHARED))
            val joined = loggedIn(loginRequest("dana", newPin = OTHER_PIN, onto = shared))
            assertEquals(shared.deviceId, joined.deviceId)
            assertEquals(2, auth.devices.get(shared.deviceId)!!.users.size)
            assertEquals(HttpStatusCode.OK, unlock(joined, "dana", OTHER_PIN).status)

            val personal = loggedIn(loginRequest("noa", newPin = TEST_PIN))
            val refused = login(loginRequest("dana", pin = OTHER_PIN, onto = personal))
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals(ErrorReasons.DEVICE_PERSONAL, refused.errorReason())
            assertEquals(1, auth.devices.get(personal.deviceId)!!.users.size)
        }
    }

    @Test
    fun aDeviceCredentialThatIsNotAcceptedIsToldApartAndCostsTheAccountNothing() = AuthEnv(root).run {
        addUser("mali")
        addUser("dana")
        api {
            val shared = loggedIn(loginRequest("mali", newPin = TEST_PIN, mode = DeviceMode.SHARED))
            loggedIn(loginRequest("dana", newPin = OTHER_PIN))

            // The PIN is wrong too, but it is not even looked at.
            val refused = login(loginRequest("dana", pin = TEST_PIN, onto = shared, deviceCredential = "not-the-credential"))

            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertEquals(ErrorCode.DEVICE_NOT_RECOGNIZED, refused.errorCode())
            assertEquals(0, users.find("dana")!!.failedLogins)
            assertEquals(1, auth.devices.get(shared.deviceId)!!.users.size)
        }
    }

    @Test
    fun loginIsRateLimitedPerAddress() = AuthEnv(root).run {
        api(rateLimitPerMinute = 3) {
            repeat(3) { assertEquals(HttpStatusCode.Unauthorized, login(loginRequest("ghost", pin = TEST_PIN)).status, "request ${it + 1}") }

            val limited = login(loginRequest("ghost", pin = TEST_PIN))

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode())
        }
    }

    @Test
    fun loginsAreAuditedWithoutThePin() = AuthEnv(root).run {
        addUser("mali")
        api {
            val device = loggedIn(loginRequest("mali", newPin = TEST_PIN))
            login(loginRequest("mali", pin = OTHER_PIN))
            login(loginRequest("ghost", pin = OTHER_PIN))

            val audit = audit()
            assertContains(audit, "login.ok user=mali device=${device.deviceId}")
            assertContains(audit, "result=ok,mode=personal")
            assertTrue(Regex("login\\.fail user=mali .* result=wrong-secret").containsMatchIn(audit), audit)
            assertTrue(Regex("login\\.fail user=ghost .* result=unknown").containsMatchIn(audit), audit)
            assertFalse(TEST_PIN in audit || OTHER_PIN in audit, audit)
        }
    }
}
