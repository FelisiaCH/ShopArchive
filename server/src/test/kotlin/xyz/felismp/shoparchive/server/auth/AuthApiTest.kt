package xyz.felismp.shoparchive.server.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OP_PASSWORD = "Correct-Horse-Battery-9"
private const val PIN = "482915"

/** Login end to end through the HTTP API (Ktor `testApplication`), on the real services. */
class AuthApiTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String? = null) = AuthEnv(root, config)

    // --- calls ---

    private fun loginRequest(
        name: String, mode: DeviceMode = DeviceMode.PERSONAL, password: String? = null, newPassword: String? = null, pin: String? = null, newPin: String? = null,
        deviceId: String? = null, deviceCredential: String? = null,
    ) = LoginRequest(name, "Test phone", "android", mode, pin, newPin, password, newPassword, deviceId, deviceCredential)

    private suspend fun ApplicationTestBuilder.login(request: LoginRequest) = postJson("/api/v1/login", LoginRequest.serializer(), request)

    /** [name] (a new user) logs in on a new device: [OP_PASSWORD] is set where the policy asks for a password, and everyone sets a [PIN]. */
    private suspend fun ApplicationTestBuilder.loggedIn(name: String, mode: DeviceMode = DeviceMode.PERSONAL): LoginResponse {
        val response = login(loginRequest(name, mode, newPassword = OP_PASSWORD, newPin = PIN))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.parsed(LoginResponse.serializer())
    }

    private suspend fun ApplicationTestBuilder.unlock(device: LoginResponse, name: String, secret: String, password: Boolean = false) =
        postJson(
            "/api/v1/unlock", UnlockRequest.serializer(),
            UnlockRequest(device.deviceId, name, device.credential, pin = secret.takeUnless { password }, password = secret.takeIf { password }),
        )

    private suspend fun ApplicationTestBuilder.accessToken(device: LoginResponse, name: String, secret: String, password: Boolean = false): String {
        val response = unlock(device, name, secret, password)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.parsed(UnlockResponse.serializer()).accessToken
    }

    /** An unlock with the device credential and neither PIN nor password. */
    private suspend fun ApplicationTestBuilder.credentialOnly(device: LoginResponse, name: String) =
        postJson("/api/v1/unlock", UnlockRequest.serializer(), UnlockRequest(device.deviceId, name, device.credential))

    private suspend fun ApplicationTestBuilder.reauth(token: String?, request: ReauthRequest) =
        postJson("/api/v1/reauth", ReauthRequest.serializer(), request, token)

    // --- the credential gate ---

    @Test
    fun everyProtectedEndpointIs401WithoutAnAccessTokenWhileInfoAndLoginAreOpen() = env().run {
        addUser("noy", op = true)
        api {
            val protectedCalls = listOf<suspend () -> io.ktor.client.statement.HttpResponse>(
                { reauth(null, ReauthRequest(pin = PIN)) },
                { getPath("/api/v1/devices") },
                { reauth("not-a-token", ReauthRequest(pin = PIN)) },
                { getPath("/api/v1/devices", "not-a-token") },
                { reauth("x".repeat(5000), ReauthRequest(pin = PIN)) },
            )
            // GET /ws is the third protected endpoint; it needs a real upgrade, so WebSocketTest covers it.
            for ((index, call) in protectedCalls.withIndex()) {
                val response = call()
                assertEquals(HttpStatusCode.Unauthorized, response.status, "call $index")
                assertEquals(ErrorCode.UNAUTHORIZED, response.errorCode(), "call $index")
            }

            assertEquals(HttpStatusCode.OK, client.get("/api/v1/info").status)
            // The login is reached without a token: it answers about the account.
            val login = login(loginRequest("noy", pin = PIN))
            assertEquals(HttpStatusCode.Unauthorized, login.status)
            assertEquals(ErrorReasons.PIN_NOT_SET, login.errorReason())
        }
    }

    @Test
    fun loginAndUnlockNeedTheirOwnCredential() = env().run {
        api {
            val login = login(loginRequest("noy", newPin = PIN))
            assertEquals(HttpStatusCode.Unauthorized, login.status)
            assertEquals(ErrorCode.UNAUTHORIZED, login.errorCode())
            val unlock = postJson(
                "/api/v1/unlock", UnlockRequest.serializer(),
                UnlockRequest("11111111-2222-3333-4444-555555555555", "noy", "x", pin = PIN),
            )
            assertEquals(HttpStatusCode.Unauthorized, unlock.status)
            assertEquals(ErrorCode.UNAUTHORIZED, unlock.errorCode())
        }
    }

    @Test
    fun aBodyThatIsNotTheEndpointsJsonIs400() = env().run {
        api {
            val broken = postRaw("/api/v1/login", "{not json")
            assertEquals(HttpStatusCode.BadRequest, broken.status)
            assertEquals(ErrorCode.INVALID_REQUEST, broken.errorCode())
            val wrongShape = postRaw("/api/v1/unlock", "{}")
            assertEquals(ErrorCode.INVALID_REQUEST, wrongShape.errorCode())
            val unknownFieldsAreSkipped = postRaw(
                "/api/v1/login", """{"username":"ghost","deviceLabel":"x","platform":"android","mode":"personal","pin":"$PIN","futureField":1}""",
            )
            assertEquals(ErrorCode.UNAUTHORIZED, unknownFieldsAreSkipped.errorCode())
        }
    }

    // --- the whole way in ---

    @Test
    fun anOpLogsInUnlocksAndUsesTheAccessToken() = env().run {
        addUser("noy", op = true)
        api {
            val noPin = login(loginRequest("noy", newPassword = OP_PASSWORD))
            assertEquals(HttpStatusCode.Unauthorized, noPin.status)
            assertEquals(ErrorReasons.PIN_NOT_SET, noPin.errorReason())
            assertEquals(ErrorCode.INVALID_REQUEST, login(loginRequest("noy", newPin = PIN)).errorCode()) // no password

            val device = login(loginRequest("noy", newPassword = OP_PASSWORD, newPin = PIN)).parsed(LoginResponse.serializer())

            val file = root.resolve("data/devices/${device.deviceId}.yml")
            val text = file.toFile().readText()
            assertContains(text, "file-version: 1")
            assertContains(text, "mode: personal")
            assertContains(text, "credential-sha256: ${sha256Hex(device.credential)}")
            assertFalse(device.credential in text)
            val userFile = root.resolve("user/noy.yml").toFile().readText()
            assertTrue(Regex("password: \"\\\$argon2id\\\$v=19\\\$m=1024,t=1,p=1\\$").containsMatchIn(userFile), userFile)
            assertTrue(Regex("pin: \"\\\$argon2id\\\$").containsMatchIn(userFile), userFile)
            assertFalse(OP_PASSWORD in userFile || PIN in userFile)

            // An op signs in with the password; the PIN is not enough.
            assertEquals(HttpStatusCode.Unauthorized, unlock(device, "noy", PIN).status)
            val token = accessToken(device, "noy", OP_PASSWORD, password = true)
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(password = OP_PASSWORD)).status)
            assertEquals(HttpStatusCode.Unauthorized, reauth(token, ReauthRequest(password = "wrong-password-here")).status)
        }
    }

    @Test
    fun aUserWithoutPasswordNodesSignsInWithThePinAlone() = env().run {
        addUser("mali")
        api {
            // A PIN of the wrong length or with other characters than digits is refused.
            for (bad in listOf("12345", "1234567", "48291a")) {
                assertEquals(ErrorCode.INVALID_REQUEST, login(loginRequest("mali", newPin = bad)).errorCode(), bad)
            }
            val device = login(loginRequest("mali", newPin = PIN)).parsed(LoginResponse.serializer())

            val token = accessToken(device, "mali", PIN)
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)
            assertFalse(root.resolve("user/mali.yml").toFile().readText().contains("password: \"\$argon2id"), "no password was asked for")
        }
    }

    @Test
    fun aWeakPasswordAndAWeakPinAreAccepted() = env().run {
        addUser("noy", op = true)
        api {
            val device = login(loginRequest("noy", newPassword = "noy", newPin = "123456")).parsed(LoginResponse.serializer())

            accessToken(device, "noy", "noy", password = true)
        }
    }

    @Test
    fun aPinOfTheWrongLengthComesBackWithAReasonKey() = env().run {
        addUser("mali")
        api {
            val refused = login(loginRequest("mali", newPin = "12345"))

            assertEquals(ErrorCode.INVALID_REQUEST, refused.errorCode())
            assertEquals(ErrorReasons.PIN_LENGTH, refused.errorReason())
        }
    }

    @Test
    fun theRefusalsAboutAPinSayHowManyDigitsItNeeds() = env().run {
        reconfigure("config-version: 1\nauth:\n  pin:\n    length: 8\n  backoff:\n    start-seconds: 0\n")
        addUser("mali")
        api {
            val notSet = login(loginRequest("mali"))
            assertEquals(ErrorReasons.PIN_NOT_SET, notSet.errorReason())
            assertEquals(8, notSet.errorPinLength())

            val tooShort = login(loginRequest("mali", newPin = "123456"))
            assertEquals(ErrorReasons.PIN_LENGTH, tooShort.errorReason())
            assertEquals(8, tooShort.errorPinLength())

            val noUser = login(loginRequest("ghost", pin = "12345678"))
            assertEquals(null, noUser.errorPinLength())
        }
    }

    // --- existing users ---

    @Test
    fun aUserWhoAlreadyHasSecretsCannotLogInWithoutThem() = env().run {
        addUser("noy", op = true)
        api {
            loggedIn("noy")

            val tries = listOf(
                loginRequest("noy", newPassword = "Another-Long-Pass-77", newPin = "135790"), // "setting" them again proves nothing
                loginRequest("noy", password = OP_PASSWORD), // no PIN
                loginRequest("noy", pin = PIN), // no password
                loginRequest("noy", password = OP_PASSWORD, pin = "999111"),
                loginRequest("noy", password = "Wrong-Long-Password-1", pin = PIN),
            )
            for ((index, request) in tries.withIndex()) {
                val response = login(request)
                assertEquals(HttpStatusCode.Unauthorized, response.status, "try $index")
                assertEquals(ErrorCode.UNAUTHORIZED, response.errorCode(), "try $index")
            }
            // Nothing was changed by the refused tries.
            val good = login(loginRequest("noy", password = OP_PASSWORD, pin = PIN))
            assertEquals(HttpStatusCode.OK, good.status, good.bodyAsText())
        }
    }

    // --- devices ---

    @Test
    fun aSharedDeviceTakesASecondUserAndAPersonalOneRefuses() = env().run {
        addUser("mali")
        addUser("kham")
        api {
            val shared = loggedIn("mali", DeviceMode.SHARED)
            val added = login(
                loginRequest("kham", DeviceMode.PERSONAL, newPin = "246801", deviceId = shared.deviceId, deviceCredential = shared.credential),
            ).parsed(LoginResponse.serializer())

            assertEquals(shared.deviceId, added.deviceId)
            assertTrue(added.credential != shared.credential)
            val deviceFile = root.resolve("data/devices/${shared.deviceId}.yml").toFile().readText()
            assertContains(deviceFile, "mode: shared") // the device keeps its own mode
            assertContains(deviceFile, "  mali:")
            assertContains(deviceFile, "  kham:")
            assertEquals(HttpStatusCode.OK, unlock(added, "kham", "246801").status)
            assertEquals(HttpStatusCode.OK, unlock(shared, "mali", PIN).status)
            // A credential belongs to one user: kham's does not open mali.
            assertEquals(HttpStatusCode.Unauthorized, unlock(added, "mali", PIN).status)

            // A personal device holds one user.
            addUser("nok")
            val personal = loggedIn("nok", DeviceMode.PERSONAL)
            addUser("lek")
            val refused = login(loginRequest("lek", newPin = PIN, deviceId = personal.deviceId, deviceCredential = personal.credential))
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals(ErrorCode.FORBIDDEN, refused.errorCode())
            assertEquals(ErrorReasons.DEVICE_PERSONAL, refused.errorReason())
            // A wrong device credential is told apart from a wrong PIN.
            val forged = login(loginRequest("lek", newPin = PIN, deviceId = shared.deviceId, deviceCredential = "forged"))
            assertEquals(HttpStatusCode.Unauthorized, forged.status)
            assertEquals(ErrorCode.DEVICE_NOT_RECOGNIZED, forged.errorCode())
        }
    }

    @Test
    fun aDeviceThatIsNotRecognizedIsRefusedBeforeThePinIsLookedAtAndCostsTheAccountNothing() = env().run {
        addUser("mali")
        addUser("kham")
        api {
            val shared = loggedIn("mali", DeviceMode.SHARED)
            val wrongPin = login(loginRequest("mali", pin = "000000", deviceId = shared.deviceId, deviceCredential = "forged"))
            assertEquals(ErrorCode.DEVICE_NOT_RECOGNIZED, wrongPin.errorCode())
            assertContains(audit(), "login.fail user=mali device=${shared.deviceId} ip=localhost result=device-not-recognized")

            // The PIN was never tried, so the account has no wrong count and no delay; the device credential is the right one now.
            val ok = login(loginRequest("mali", pin = PIN, deviceId = shared.deviceId, deviceCredential = shared.credential))
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertEquals(0, users.find("mali")!!.failedLogins)
        }
    }

    @Test
    fun aWrongPinWithAGoodDeviceCredentialIsUnauthorized() = env().run {
        addUser("mali")
        api {
            val shared = loggedIn("mali", DeviceMode.SHARED)
            val wrong = login(loginRequest("mali", pin = "000000", deviceId = shared.deviceId, deviceCredential = shared.credential))

            assertEquals(ErrorCode.UNAUTHORIZED, wrong.errorCode())
        }
    }

    @Test
    fun wrongPinsRevokeTheUserOnThatDeviceOnly() = env("$NO_BACKOFF  pin:\n    max-failures: 3\n").run {
        addUser("mali")
        addUser("kham")
        api {
            val shared = loggedIn("mali", DeviceMode.SHARED)
            val khamOnShared = login(
                loginRequest("kham", newPin = "246801", deviceId = shared.deviceId, deviceCredential = shared.credential),
            ).parsed(LoginResponse.serializer())
            // Mali has a second device: she is on it with the same PIN.
            val malisPhone = login(loginRequest("mali", pin = PIN)).parsed(LoginResponse.serializer())
            val token = accessToken(shared, "mali", PIN)

            assertEquals(HttpStatusCode.Unauthorized, unlock(shared, "mali", "000000").status)
            assertEquals(HttpStatusCode.Unauthorized, unlock(shared, "mali", "000000").status)
            // A good PIN in between starts the count again.
            assertEquals(HttpStatusCode.OK, unlock(shared, "mali", PIN).status)
            repeat(3) { assertEquals(HttpStatusCode.Unauthorized, unlock(shared, "mali", "000000").status) }

            // Mali is off the shared device: the PIN, the credential and the token she already held are all dead there ...
            assertEquals(HttpStatusCode.Unauthorized, unlock(shared, "mali", PIN).status)
            assertEquals(HttpStatusCode.Unauthorized, reauth(token, ReauthRequest(pin = PIN)).status)
            assertFalse(root.resolve("data/devices/${shared.deviceId}.yml").toFile().readText().contains("  mali:"))
            // ... but kham on the same device, and mali on her phone, are not touched.
            assertEquals(HttpStatusCode.OK, unlock(khamOnShared, "kham", "246801").status)
            assertEquals(HttpStatusCode.OK, unlock(malisPhone, "mali", PIN).status)
            assertContains(audit(), "revoke.pin-failures")
        }
    }

    @Test
    fun aMaxFailuresOfZeroNeverRemovesTheUserButStillCounts() = env("$NO_BACKOFF  pin:\n    max-failures: 0\n").run {
        addUser("mali")
        api {
            val device = loggedIn("mali")
            val file = root.resolve("data/devices/${device.deviceId}.yml").toFile()

            repeat(20) { assertEquals(HttpStatusCode.Unauthorized, unlock(device, "mali", "000000").status) }

            assertContains(file.readText(), "pin-failures: 20")
            // Turned on, the count so far is already past it: the next wrong PIN removes the user.
            reconfigure("$NO_BACKOFF  pin:\n    max-failures: 3\n")
            assertEquals(HttpStatusCode.Unauthorized, unlock(device, "mali", "000000").status)
            assertFalse(file.readText().contains("  mali:"))
        }
    }

    @Test
    fun theCountOfWrongPinsSurvivesARestart() {
        val first = env("$NO_BACKOFF  pin:\n    max-failures: 3\n")
        first.addUser("mali")
        lateinit var device: LoginResponse
        first.api {
            device = loggedIn("mali")
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, unlock(device, "mali", "000000").status) }
        }

        val restarted = AuthEnv(root, null, first.clock)
        restarted.api {
            // The two wrong tries from before the restart are still counted: this one removes the user.
            assertEquals(HttpStatusCode.Unauthorized, unlock(device, "mali", "000000").status)
            assertFalse(root.resolve("data/devices/${device.deviceId}.yml").toFile().readText().contains("  mali:"))
        }
    }

    @Test
    fun aDisabledUserCannotUnlockAndAnExistingTokenStopsWorkingAtOnce() = env().run {
        addUser("mali")
        api {
            val device = loggedIn("mali")
            val token = accessToken(device, "mali", PIN)
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)

            users.setEnabled("mali", false)

            assertEquals(HttpStatusCode.Unauthorized, reauth(token, ReauthRequest(pin = PIN)).status)
            assertEquals(HttpStatusCode.Unauthorized, unlock(device, "mali", PIN).status)

            users.setEnabled("mali", true)
            assertEquals(HttpStatusCode.OK, unlock(device, "mali", PIN).status)
        }
    }

    @Test
    fun anAccessTokenExpiresAfterTheConfiguredTime() = env("config-version: 1\nauth:\n  session:\n    access-token-minutes: 2\n").run {
        addUser("mali")
        api {
            val device = loggedIn("mali")
            val token = accessToken(device, "mali", PIN)
            clock.advance(Duration.ofMinutes(1))
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)

            clock.advance(Duration.ofMinutes(2))

            assertEquals(HttpStatusCode.Unauthorized, reauth(token, ReauthRequest(pin = PIN)).status)
        }
    }

    // --- the re-auth window ---

    @Test
    fun takingADeviceOffNeedsAPinEnteredWithinTheReauthWindow() = env().run {
        addUser("mali")
        api {
            val device = loggedIn("mali")
            val phone = login(loginRequest("mali", pin = PIN)).parsed(LoginResponse.serializer())
            val tablet = login(loginRequest("mali", pin = PIN)).parsed(LoginResponse.serializer())
            val token = accessToken(device, "mali", PIN)
            // Straight after unlocking, the PIN was just entered ...
            assertEquals(HttpStatusCode.NoContent, deletePath("/api/v1/devices/${tablet.deviceId}", token).status)

            clock.advance(Duration.ofMinutes(6)) // the window is 5, the token lives 15
            val path = "/api/v1/devices/${phone.deviceId}"
            val stale = deletePath(path, token)
            assertEquals(HttpStatusCode.Unauthorized, stale.status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, stale.errorCode())

            assertEquals(HttpStatusCode.Unauthorized, reauth(token, ReauthRequest(pin = "000000")).status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, deletePath(path, token).errorCode())
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)
            assertEquals(HttpStatusCode.NoContent, deletePath(path, token).status)
        }
    }

    // --- unlock without the PIN ---

    @Test
    fun aOneUserDeviceUnlocksWithItsCredentialAloneButTheSessionIsNotRecentlyVerified() = env().run {
        addUser("mali")
        api {
            val device = loggedIn("mali")
            val other = login(loginRequest("mali", pin = PIN)).parsed(LoginResponse.serializer())

            val unlocked = credentialOnly(device, "mali")
            assertEquals(HttpStatusCode.OK, unlocked.status, unlocked.bodyAsText())
            val token = unlocked.parsed(UnlockResponse.serializer()).accessToken
            assertContains(audit(), "unlock.ok user=mali device=${device.deviceId} ip=localhost result=ok,credential-only")
            // Taking a device off needs a recent PIN: the credential alone is not one.
            val path = "/api/v1/devices/${other.deviceId}"
            assertEquals(ErrorCode.REAUTH_REQUIRED, deletePath(path, token).errorCode())
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)
            assertEquals(HttpStatusCode.NoContent, deletePath(path, token).status)
        }
    }

    @Test
    fun turnedOffAnUnlockWithoutThePinIsAWrongPin() = env("$NO_BACKOFF  device:\n    unlock-without-pin: false\n").run {
        addUser("mali")
        api {
            val device = loggedIn("mali")

            val wrongPin = unlock(device, "mali", "000000")
            val noPin = credentialOnly(device, "mali")

            assertEquals(HttpStatusCode.Unauthorized, noPin.status)
            assertEquals(wrongPin.bodyAsText(), noPin.bodyAsText())
            assertContains(root.resolve("data/devices/${device.deviceId}.yml").toFile().readText(), "pin-failures: 2")
        }
    }

    @Test
    fun aDeviceWithTwoUsersAlwaysAsksForThePinAndNoPinCountsAsAWrongTry() = env().run {
        addUser("mali")
        addUser("kham")
        api {
            val shared = loggedIn("mali", DeviceMode.SHARED)
            login(loginRequest("kham", newPin = "246801", deviceId = shared.deviceId, deviceCredential = shared.credential))

            val noPin = credentialOnly(shared, "mali")

            assertEquals(HttpStatusCode.Unauthorized, noPin.status)
            assertEquals(ErrorCode.UNAUTHORIZED, noPin.errorCode())
            assertContains(audit(), "unlock.fail user=mali device=${shared.deviceId} ip=localhost result=wrong-secret")
            assertContains(root.resolve("data/devices/${shared.deviceId}.yml").toFile().readText(), "pin-failures: 1")
        }
    }

    @Test
    fun aUserWhoNeedsThePasswordIsAskedForItAsBefore() = env().run {
        addUser("noy", op = true)
        api {
            val device = loggedIn("noy")

            val noSecret = credentialOnly(device, "noy")

            assertEquals(HttpStatusCode.Unauthorized, noSecret.status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, noSecret.errorCode())
            assertFalse("credential-only" in audit(), audit())
        }
    }

    @Test
    fun anIdleExpiredDeviceStaysExpiredWithoutThePin() = env("$NO_BACKOFF  device:\n    idle-expiry-days: 30\n").run {
        addUser("mali")
        api {
            val device = loggedIn("mali")
            clock.advance(Duration.ofDays(31))

            assertEquals(HttpStatusCode.Unauthorized, credentialOnly(device, "mali").status)
            assertContains(audit(), "device.expired user=mali device=${device.deviceId}")
            assertFalse(root.resolve("data/devices/${device.deviceId}.yml").toFile().readText().contains("  mali:"))
        }
    }

    @Test
    fun aLockedAccountStillUnlocksItsOwnOneUserDeviceWithoutThePin() = env("config-version: 1\nauth:\n  backoff:\n    start-seconds: 60\n").run {
        addUser("mali")
        api {
            val device = loggedIn("mali")
            assertEquals(HttpStatusCode.Unauthorized, unlock(device, "mali", "000000").status)
            assertEquals(HttpStatusCode.TooManyRequests, unlock(device, "mali", PIN).status)
            clock.advance(Duration.ofSeconds(10))

            assertEquals(HttpStatusCode.OK, credentialOnly(device, "mali").status)
            // The device was used now, but the wrong PIN still counts: no PIN was entered.
            val file = root.resolve("data/devices/${device.deviceId}.yml").toFile().readText()
            assertContains(file, "last-used: ${clock.now}")
            assertContains(file, "pin-failures: 1")
        }
    }

    // --- audit and secrets ---

    @Test
    fun theAuditLogRecordsTheEventsAndNoSecret() = env().run {
        addUser("noy", op = true)
        api {
            val device = login(loginRequest("noy", newPassword = OP_PASSWORD, newPin = PIN)).parsed(LoginResponse.serializer())
            login(loginRequest("noy", password = "wrong-password-1234", pin = PIN)) // fails
            val token = accessToken(device, "noy", OP_PASSWORD, password = true)
            unlock(device, "noy", "wrong-password-1234", password = true)
            val secrets = listOf(device.credential, token, OP_PASSWORD, PIN, "wrong-password-1234") +
                root.resolve("user/noy.yml").toFile().readLines().filter { "argon2id" in it }.map { it.substringAfter("\"").substringBeforeLast("\"") }

            val audit = audit()
            for (event in listOf("login.ok", "login.fail", "unlock.ok", "unlock.fail")) assertContains(audit, " $event ")
            assertContains(audit, "user=noy device=${device.deviceId} ip=")
            assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+07:00 ", RegexOption.MULTILINE).containsMatchIn(audit), audit)
            assertTrue(audit.lines().all { it.isEmpty() || it.split(" ").size == 6 }, audit)
            val files = filesUnder("data/audit").keys.single()
            assertTrue(Regex("\\d{4}[/\\\\]\\d{2}[/\\\\]\\d{2}\\.log").matches(files), files)
            for (secret in secrets) assertFalse(secret in audit, "audit holds a secret: ${secret.take(6)}...")
            // The log lines of the config and user loading hold none either.
            for (secret in secrets) assertFalse(log.infos.plus(log.warnings).plus(log.errors).any { secret in it })
        }
    }

}
