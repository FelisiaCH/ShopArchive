package xyz.felismp.shoparchive.server.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.shared.CreatePairingRequest
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PairingResponse
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.RedeemResponse
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

    private suspend fun ApplicationTestBuilder.redeem(request: RedeemRequest) = postJson("/api/v1/pair/redeem", RedeemRequest.serializer(), request)

    /** A new pairing for [name] redeemed by its secret. */
    private suspend fun ApplicationTestBuilder.redeemed(env: AuthEnv, name: String): RedeemResponse {
        val response = redeem(RedeemRequest(secret = env.pair(name).secret))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.parsed(RedeemResponse.serializer())
    }

    private fun enrollRequest(
        mode: DeviceMode = DeviceMode.PERSONAL, password: String? = null, newPassword: String? = null, pin: String? = null, newPin: String? = null,
        deviceId: String? = null, deviceCredential: String? = null,
    ) = EnrollRequest("Test phone", "android", mode, password, newPassword, pin, newPin, deviceId, deviceCredential)

    private suspend fun ApplicationTestBuilder.enroll(token: String, request: EnrollRequest) =
        postJson("/api/v1/enroll", EnrollRequest.serializer(), request, token)

    /** Pairs [name] (a new user) and enrolls a device: op users get [OP_PASSWORD], everyone a [PIN]. */
    private suspend fun ApplicationTestBuilder.enrolled(env: AuthEnv, name: String, mode: DeviceMode = DeviceMode.PERSONAL): EnrollResponse {
        val redeemed = redeemed(env, name)
        val response = enroll(
            redeemed.enrollmentToken,
            enrollRequest(mode, newPassword = OP_PASSWORD.takeIf { redeemed.passwordRequired }, newPin = PIN),
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.parsed(EnrollResponse.serializer())
    }

    private suspend fun ApplicationTestBuilder.unlock(device: EnrollResponse, name: String, secret: String, password: Boolean = false) =
        postJson(
            "/api/v1/unlock", UnlockRequest.serializer(),
            UnlockRequest(device.deviceId, name, device.credential, pin = secret.takeUnless { password }, password = secret.takeIf { password }),
        )

    private suspend fun ApplicationTestBuilder.accessToken(device: EnrollResponse, name: String, secret: String, password: Boolean = false): String {
        val response = unlock(device, name, secret, password)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.parsed(UnlockResponse.serializer()).accessToken
    }

    /** An unlock with the device credential and neither PIN nor password. */
    private suspend fun ApplicationTestBuilder.credentialOnly(device: EnrollResponse, name: String) =
        postJson("/api/v1/unlock", UnlockRequest.serializer(), UnlockRequest(device.deviceId, name, device.credential))

    private suspend fun ApplicationTestBuilder.reauth(token: String?, request: ReauthRequest) =
        postJson("/api/v1/reauth", ReauthRequest.serializer(), request, token)

    private suspend fun ApplicationTestBuilder.createPairing(token: String?, username: String) =
        postJson("/api/v1/pairings", CreatePairingRequest.serializer(), CreatePairingRequest(username), token)

    // --- the credential gate ---

    @Test
    fun everyProtectedEndpointIs401WithoutAnAccessTokenWhileInfoAndRedeemAreOpen() = env().run {
        addUser("noy", op = true)
        api {
            val enrollmentToken = redeemed(this@run, "noy").enrollmentToken

            val protectedCalls = listOf<suspend () -> io.ktor.client.statement.HttpResponse>(
                { reauth(null, ReauthRequest(pin = PIN)) },
                { createPairing(null, "noy") },
                // An enrollment token is not an access token.
                { reauth(enrollmentToken, ReauthRequest(pin = PIN)) },
                { createPairing(enrollmentToken, "noy") },
                { reauth("not-a-token", ReauthRequest(pin = PIN)) },
                { reauth("x".repeat(5000), ReauthRequest(pin = PIN)) },
            )
            // GET /ws is the third protected endpoint; it needs a real upgrade, so WebSocketTest covers it.
            for ((index, call) in protectedCalls.withIndex()) {
                val response = call()
                assertEquals(HttpStatusCode.Unauthorized, response.status, "call $index")
                assertEquals(ErrorCode.UNAUTHORIZED, response.errorCode(), "call $index")
            }

            assertEquals(HttpStatusCode.OK, client.get("/api/v1/info").status)
            val redeem = redeem(RedeemRequest(secret = "nonsense"))
            assertEquals(HttpStatusCode.Unauthorized, redeem.status)
            assertEquals(ErrorCode.PAIRING_INVALID, redeem.errorCode())
        }
    }

    @Test
    fun enrollAndUnlockNeedTheirOwnCredential() = env().run {
        api {
            assertEquals(ErrorCode.UNAUTHORIZED, enroll("nope", enrollRequest(newPin = PIN)).errorCode())
            val noToken = postJson("/api/v1/enroll", EnrollRequest.serializer(), enrollRequest(newPin = PIN))
            assertEquals(HttpStatusCode.Unauthorized, noToken.status)
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
            val broken = postRaw("/api/v1/pair/redeem", "{not json")
            assertEquals(HttpStatusCode.BadRequest, broken.status)
            assertEquals(ErrorCode.INVALID_REQUEST, broken.errorCode())
            val wrongShape = postRaw("/api/v1/unlock", "{}")
            assertEquals(ErrorCode.INVALID_REQUEST, wrongShape.errorCode())
            val unknownFieldsAreSkipped = postRaw("/api/v1/pair/redeem", """{"secret":"x","futureField":1}""")
            assertEquals(ErrorCode.PAIRING_INVALID, unknownFieldsAreSkipped.errorCode())
        }
    }

    // --- the whole way in ---

    @Test
    fun anOpPairsEnrollsUnlocksAndUsesTheAccessToken() = env().run {
        addUser("noy", op = true)
        api {
            val redeemed = redeemed(this@run, "noy")
            assertTrue(redeemed.passwordRequired && !redeemed.hasPassword && !redeemed.hasPin)
            assertEquals("noy", redeemed.username)
            assertEquals(6, redeemed.pinLength)

            // Everyone, op or not, is told the same suggested length, and the server name for the app's hint.
            assertEquals(8, redeemed.suggestedPasswordMin)
            assertEquals("ShopArchive", redeemed.serverName)
            assertEquals(ErrorCode.INVALID_REQUEST, enroll(redeemed.enrollmentToken, enrollRequest(newPassword = OP_PASSWORD)).errorCode()) // no PIN
            assertEquals(ErrorCode.INVALID_REQUEST, enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN)).errorCode()) // no password

            val device = enroll(redeemed.enrollmentToken, enrollRequest(newPassword = OP_PASSWORD, newPin = PIN)).parsed(EnrollResponse.serializer())

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
            val redeemed = redeemed(this@run, "mali")
            assertFalse(redeemed.passwordRequired)
            assertEquals(8, redeemed.suggestedPasswordMin)
            // A PIN of the wrong length or with other characters than digits is refused.
            for (bad in listOf("12345", "1234567", "48291a")) {
                assertEquals(ErrorCode.INVALID_REQUEST, enroll(redeemed.enrollmentToken, enrollRequest(newPin = bad)).errorCode(), bad)
            }
            val device = enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN)).parsed(EnrollResponse.serializer())

            val token = accessToken(device, "mali", PIN)
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)
            assertFalse(root.resolve("user/mali.yml").toFile().readText().contains("password: \"\$argon2id"), "no password was asked for")
        }
    }

    @Test
    fun aWeakPasswordAndAWeakPinAreAccepted() = env().run {
        addUser("noy", op = true)
        api {
            val redeemed = redeemed(this@run, "noy")
            val device = enroll(redeemed.enrollmentToken, enrollRequest(newPassword = "noy", newPin = "123456")).parsed(EnrollResponse.serializer())

            accessToken(device, "noy", "noy", password = true)
        }
    }

    @Test
    fun theEnrollmentTokenWorksOnceAndNotAfterwards() = env().run {
        addUser("mali")
        api {
            val redeemed = redeemed(this@run, "mali")
            assertEquals(HttpStatusCode.OK, enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN)).status)

            val again = enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN))
            assertEquals(HttpStatusCode.Unauthorized, again.status)
        }
    }

    @Test
    fun anEnrollmentTokenRunsOutWithThePairingTtl() = env().run {
        addUser("mali")
        api {
            val redeemed = redeemed(this@run, "mali")
            clock.advance(Duration.ofMinutes(11))

            val late = enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN))
            assertEquals(HttpStatusCode.Unauthorized, late.status)
            assertEquals(ErrorCode.ENROLLMENT_EXPIRED, late.errorCode())
            assertContains(audit(), "enroll.fail user=mali device=- ip=localhost result=expired")
        }
    }

    @Test
    fun anExpiredEnrollmentIsRememberedForAsLongAgainThenItIsJustUnknown() = env().run {
        addUser("mali")
        api {
            val redeemed = redeemed(this@run, "mali")
            assertEquals(600L, redeemed.expiresInSeconds)
            clock.advance(Duration.ofMinutes(11))
            assertEquals(ErrorCode.ENROLLMENT_EXPIRED, enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN)).errorCode())

            clock.advance(Duration.ofMinutes(10))
            val gone = enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN))
            assertEquals(ErrorCode.UNAUTHORIZED, gone.errorCode())
            assertEquals(EnrollmentLookup.Unknown, auth.sessions.enrollment(redeemed.enrollmentToken))
        }
    }

    @Test
    fun aUsedOrMadeUpTokenIsUnauthorizedNotExpired() = env().run {
        addUser("mali")
        api {
            val redeemed = redeemed(this@run, "mali")
            assertEquals(HttpStatusCode.OK, enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN)).status)
            clock.advance(Duration.ofMinutes(11))

            assertEquals(ErrorCode.UNAUTHORIZED, enroll(redeemed.enrollmentToken, enrollRequest(newPin = PIN)).errorCode())
            assertEquals(ErrorCode.UNAUTHORIZED, enroll("made-up", enrollRequest(newPin = PIN)).errorCode())
        }
    }

    @Test
    fun aPinOfTheWrongLengthComesBackWithAReasonKey() = env().run {
        addUser("mali")
        api {
            val redeemed = redeemed(this@run, "mali")
            val refused = enroll(redeemed.enrollmentToken, enrollRequest(newPin = "12345"))

            assertEquals(ErrorCode.INVALID_REQUEST, refused.errorCode())
            assertEquals(ErrorReasons.PIN_LENGTH, refused.errorReason())
        }
    }

    // --- pairings ---

    @Test
    fun aPairingCanBeUsedOnlyOnce() = env().run {
        addUser("mali")
        api {
            val pairing = pair("mali")
            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(secret = pairing.secret)).status)

            val second = redeem(RedeemRequest(secret = pairing.secret))
            assertEquals(HttpStatusCode.Unauthorized, second.status)
            assertEquals(ErrorCode.PAIRING_INVALID, second.errorCode())
            // The manual code of a used pairing is as dead as its secret.
            assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(username = "mali", code = pairing.manualCode)).errorCode())
        }
    }

    @Test
    fun aPairingExpiresAfterTheConfiguredTime() = env("config-version: 1\nauth:\n  pairing:\n    ttl-minutes: 3\n").run {
        addUser("mali")
        api {
            pair("mali")
            clock.advance(Duration.ofMinutes(2))
            val stillGood = pair("mali") // a new one replaces it and starts its own three minutes
            clock.advance(Duration.ofMinutes(2))
            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(secret = stillGood.secret)).status)

            val late = pair("mali")
            clock.advance(Duration.ofMinutes(4))
            assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(secret = late.secret)).errorCode())
            assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(username = "mali", code = late.manualCode)).errorCode())
            assertEquals(0, auth.pairing.pendingCount())
        }
    }

    @Test
    fun aNewPairingReplacesTheOldOneForTheSameUser() = env().run {
        addUser("mali")
        addUser("noy")
        api {
            val first = pair("mali")
            val other = pair("noy")
            val second = pair("mali")

            assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(secret = first.secret)).errorCode())
            assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(username = "mali", code = first.manualCode)).errorCode())
            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(secret = second.secret)).status)
            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(username = "noy", code = other.manualCode)).status)
        }
    }

    @Test
    fun theManualCodeDiesAfterTheConfiguredWrongTriesButTheQrSecretStillWorks() = env("config-version: 1\nauth:\n  pairing:\n    manual-code-attempts: 3\n").run {
        addUser("mali")
        api {
            val pairing = pair("mali")
            assertTrue(Regex("[BCDFGHJKLMNPQRSTVWXZ]{5}-[BCDFGHJKLMNPQRSTVWXZ]{5}").matches(pairing.manualCode!!), pairing.manualCode)

            repeat(3) {
                assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(username = "mali", code = "BBBBB-BBBBB")).errorCode())
            }
            // The right code, typed any way a person might, is refused now.
            for (typed in listOf(pairing.manualCode, pairing.manualCode.replace("-", "").lowercase())) {
                assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(username = "mali", code = typed)).errorCode())
            }
            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(secret = pairing.secret)).status)
        }
    }

    @Test
    fun theRightManualCodeWorksUntilTheTriesRunOutAndTheUserNameIsNotRevealed() = env().run {
        addUser("mali")
        api {
            val pairing = pair("mali")
            val unknownUser = redeem(RedeemRequest(username = "nobody", code = pairing.manualCode))
            val wrongCode = redeem(RedeemRequest(username = "mali", code = "BBBBB-BBBBB"))
            assertEquals(unknownUser.bodyAsText(), wrongCode.bodyAsText())

            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(username = "mali", code = " ${pairing.manualCode!!.lowercase()} ")).status)
        }
    }

    @Test
    fun manualCodesCanBeTurnedOff() = env("config-version: 1\nauth:\n  pairing:\n    manual-code: false\n").run {
        addUser("mali")
        api {
            val pairing = pair("mali")
            assertEquals(null, pairing.manualCode)
            assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(username = "mali", code = "BCDFG-HJKLM")).errorCode())
            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(secret = pairing.secret)).status)
        }
    }

    @Test
    fun aDisabledUserCannotBePairedOrRedeem() = env().run {
        addUser("mali")
        api {
            val pairing = pair("mali")
            users.setEnabled("mali", false)

            assertEquals(ErrorCode.PAIRING_INVALID, redeem(RedeemRequest(secret = pairing.secret)).errorCode())
            assertTrue(console("user pair mali").single().contains("disabled"))
        }
    }

    // --- existing users ---

    @Test
    fun aUserWhoAlreadyHasSecretsCannotEnrollWithoutThem() = env().run {
        addUser("noy", op = true)
        api {
            enrolled(this@run, "noy")

            val again = redeemed(this@run, "noy")
            assertTrue(again.hasPassword && again.hasPin)
            val tries = listOf(
                enrollRequest(newPassword = "Another-Long-Pass-77", newPin = "135790"), // "setting" them again: refused, and not a wrong try
                enrollRequest(password = OP_PASSWORD), // no PIN
                enrollRequest(pin = PIN), // no password
                enrollRequest(password = OP_PASSWORD, pin = "999111"),
                enrollRequest(password = "Wrong-Long-Password-1", pin = PIN),
            )
            for ((index, request) in tries.withIndex()) {
                val response = enroll(again.enrollmentToken, request)
                assertEquals(if (index == 0) HttpStatusCode.Conflict else HttpStatusCode.Unauthorized, response.status, "try $index")
                if (index == 0) assertEquals(ErrorCode.CREDENTIALS_CHANGED, response.errorCode())
                if (index == 3) break
            }
            // Nothing was changed by the refused tries.
            val good = enroll(again.enrollmentToken, enrollRequest(password = OP_PASSWORD, pin = PIN))
            assertEquals(HttpStatusCode.OK, good.status, good.bodyAsText())
        }
    }

    @Test
    fun aGrantThatOnlyBringsANewPinForAnAccountThatHasOneNowIs409AndCostsNoWrongTry() = env().run {
        addUser("mali")
        api {
            val stale = redeemed(this@run, "mali")
            assertEquals(HttpStatusCode.OK, enroll(redeemed(this@run, "mali").enrollmentToken, enrollRequest(newPin = PIN)).status)

            // The app is told to ask for the existing PIN; however often it is told, the token is not used up.
            repeat(6) {
                val response = enroll(stale.enrollmentToken, enrollRequest(newPin = "905173"))
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(ErrorCode.CREDENTIALS_CHANGED, response.errorCode())
            }
            assertFalse("wrong-secret" in audit(), audit())
            assertContains(audit(), "enroll.fail user=mali device=- ip=localhost result=credentials-changed")

            assertEquals(HttpStatusCode.OK, enroll(stale.enrollmentToken, enrollRequest(pin = PIN)).status)
        }
    }

    @Test
    fun fiveWrongSecretsCancelTheEnrollmentToken() = env().run {
        addUser("mali")
        api {
            enrolled(this@run, "mali")
            val again = redeemed(this@run, "mali")

            repeat(5) { assertEquals(HttpStatusCode.Unauthorized, enroll(again.enrollmentToken, enrollRequest(pin = "000000")).status) }
            // Even the right PIN does not help now; the pairing has to be done again.
            assertEquals(HttpStatusCode.Unauthorized, enroll(again.enrollmentToken, enrollRequest(pin = PIN)).status)
            assertContains(audit(), "wrong-secret,cancelled")
        }
    }

    // --- devices ---

    @Test
    fun aSharedDeviceTakesASecondUserAndAPersonalOneRefuses() = env().run {
        addUser("mali")
        addUser("kham")
        api {
            val shared = enrolled(this@run, "mali", DeviceMode.SHARED)
            val second = redeemed(this@run, "kham")
            val added = enroll(
                second.enrollmentToken,
                enrollRequest(DeviceMode.PERSONAL, newPin = "246801", deviceId = shared.deviceId, deviceCredential = shared.credential),
            ).parsed(EnrollResponse.serializer())

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
            val personal = enrolled(this@run, "nok", DeviceMode.PERSONAL)
            addUser("lek")
            val third = redeemed(this@run, "lek")
            val refused = enroll(third.enrollmentToken, enrollRequest(newPin = PIN, deviceId = personal.deviceId, deviceCredential = personal.credential))
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals(ErrorCode.FORBIDDEN, refused.errorCode())
            assertEquals(ErrorReasons.DEVICE_PERSONAL, refused.errorReason())
            // A wrong device credential is told apart from a wrong PIN.
            val forged = enroll(third.enrollmentToken, enrollRequest(newPin = PIN, deviceId = shared.deviceId, deviceCredential = "forged"))
            assertEquals(HttpStatusCode.Unauthorized, forged.status)
            assertEquals(ErrorCode.DEVICE_NOT_RECOGNIZED, forged.errorCode())
        }
    }

    @Test
    fun aDeviceThatIsNotRecognizedIsRefusedBeforeThePinIsLookedAtAndCostsTheAccountNothing() = env().run {
        addUser("mali")
        addUser("kham")
        api {
            val shared = enrolled(this@run, "mali", DeviceMode.SHARED)
            val again = redeemed(this@run, "mali")
            val wrongPin = enroll(again.enrollmentToken, enrollRequest(pin = "000000", deviceId = shared.deviceId, deviceCredential = "forged"))
            assertEquals(ErrorCode.DEVICE_NOT_RECOGNIZED, wrongPin.errorCode())
            assertContains(audit(), "enroll.fail user=mali device=${shared.deviceId} ip=localhost result=device-not-recognized")

            // The PIN was never tried, so the account has no wrong count and no delay; the device credential is the right one now.
            val ok = enroll(again.enrollmentToken, enrollRequest(pin = PIN, deviceId = shared.deviceId, deviceCredential = shared.credential))
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        }
    }

    @Test
    fun aWrongPinWithAGoodDeviceCredentialIsUnauthorized() = env().run {
        addUser("mali")
        api {
            val shared = enrolled(this@run, "mali", DeviceMode.SHARED)
            val again = redeemed(this@run, "mali")
            val wrong = enroll(again.enrollmentToken, enrollRequest(pin = "000000", deviceId = shared.deviceId, deviceCredential = shared.credential))

            assertEquals(ErrorCode.UNAUTHORIZED, wrong.errorCode())
        }
    }

    @Test
    fun deviceCredentialsThatAreNotRecognizedCancelTheEnrollmentLikeWrongPinsDo() = env().run {
        addUser("mali")
        api {
            val shared = enrolled(this@run, "mali", DeviceMode.SHARED)
            val again = redeemed(this@run, "mali")

            repeat(5) { enroll(again.enrollmentToken, enrollRequest(pin = PIN, deviceId = shared.deviceId, deviceCredential = "forged")) }

            assertEquals(ErrorCode.UNAUTHORIZED, enroll(again.enrollmentToken, enrollRequest(pin = PIN, deviceId = shared.deviceId, deviceCredential = shared.credential)).errorCode())
            assertContains(audit(), "device-not-recognized,cancelled")
        }
    }

    @Test
    fun wrongPinsRevokeTheUserOnThatDeviceOnly() = env("$NO_BACKOFF  pin:\n    max-failures: 3\n").run {
        addUser("mali")
        addUser("kham")
        api {
            val shared = enrolled(this@run, "mali", DeviceMode.SHARED)
            val khamRedeemed = redeemed(this@run, "kham")
            val khamOnShared = enroll(
                khamRedeemed.enrollmentToken,
                enrollRequest(newPin = "246801", deviceId = shared.deviceId, deviceCredential = shared.credential),
            ).parsed(EnrollResponse.serializer())
            // Mali has a second device: she is on it with the same PIN.
            val pairedAgain = redeemed(this@run, "mali")
            val malisPhone = enroll(pairedAgain.enrollmentToken, enrollRequest(pin = PIN)).parsed(EnrollResponse.serializer())
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
            val device = enrolled(this@run, "mali")
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
        lateinit var device: EnrollResponse
        first.api {
            device = enrolled(first, "mali")
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
            val device = enrolled(this@run, "mali")
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
            val device = enrolled(this@run, "mali")
            val token = accessToken(device, "mali", PIN)
            clock.advance(Duration.ofMinutes(1))
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)

            clock.advance(Duration.ofMinutes(2))

            assertEquals(HttpStatusCode.Unauthorized, reauth(token, ReauthRequest(pin = PIN)).status)
        }
    }

    // --- the pairing API ---

    @Test
    fun anAdminPairsAnotherUserWithThePermissionAndARecentPin() = env().run {
        addUser("noy", op = true)
        addUser("boss")
        addUser("mali")
        users.setUserPermission("boss", "shoparchive.devices.pair", true)
        api {
            val noy = enrolled(this@run, "noy")
            val noyToken = accessToken(noy, "noy", OP_PASSWORD, password = true)

            val response = createPairing(noyToken, "mali")
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val created = response.parsed(PairingResponse.serializer())
            assertEquals(FINGERPRINT, created.fingerprint)
            val pairing = Pairing(created.link, created.manualCode)
            assertEquals("mali", pairing.payload.u)
            assertEquals(1, pairing.payload.v)
            assertEquals(FINGERPRINT.replace(" ", ""), pairing.payload.fp)
            assertEquals(SERVER_ID.toString(), pairing.payload.sid)
            assertEquals(listOf("shop.example.com:25655", "192.168.1.20:25655"), pairing.payload.ep)
            // What the API returned is redeemable.
            assertEquals(HttpStatusCode.OK, redeem(RedeemRequest(secret = pairing.secret)).status)

            val boss = enrolled(this@run, "boss")
            val bossToken = accessToken(boss, "boss", PIN)
            assertEquals(HttpStatusCode.Created, createPairing(bossToken, "mali").status)
            // Without the permission it is refused, and nothing is said about whether the user exists.
            val malisDevice = enrolled(this@run, "mali")
            val malisToken = accessToken(malisDevice, "mali", PIN)
            assertEquals(HttpStatusCode.Forbidden, createPairing(malisToken, "boss").status)
            assertEquals(HttpStatusCode.Forbidden, createPairing(malisToken, "nobody").status)
            assertEquals(ErrorCode.FORBIDDEN, createPairing(malisToken, "boss").errorCode())
            assertEquals(ErrorCode.NOT_FOUND, createPairing(bossToken, "nobody").errorCode())
        }
    }

    @Test
    fun creatingAPairingNeedsAPinEnteredWithinTheReauthWindow() = env().run {
        addUser("mali")
        api {
            val device = enrolled(this@run, "mali")
            val token = accessToken(device, "mali", PIN)
            // A user may pair themselves (source "self"), straight after unlocking ...
            assertEquals(HttpStatusCode.Created, createPairing(token, "mali").status)

            clock.advance(Duration.ofMinutes(6)) // the window is 5, the token lives 15
            val stale = createPairing(token, "mali")
            assertEquals(HttpStatusCode.Unauthorized, stale.status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, stale.errorCode())

            assertEquals(HttpStatusCode.Unauthorized, reauth(token, ReauthRequest(pin = "000000")).status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, createPairing(token, "mali").errorCode())
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)
            assertEquals(HttpStatusCode.Created, createPairing(token, "mali").status)
        }
    }

    @Test
    fun theSourcesSettingDecidesWhoMayStartAPairing() = env("config-version: 1\nauth:\n  pairing:\n    sources: [console]\n").run {
        addUser("noy", op = true)
        api {
            val device = enrolled(this@run, "noy")
            val token = accessToken(device, "noy", OP_PASSWORD, password = true)

            assertEquals(HttpStatusCode.Forbidden, createPairing(token, "noy").status) // self
            addUser("mali")
            assertEquals(HttpStatusCode.Forbidden, createPairing(token, "mali").status) // admin
            assertEquals(1, console("user pair mali").size) // the console is still allowed: one line, the rest is terminal-only
            assertTrue(terminal.isNotEmpty())
        }
    }

    // --- unlock without the PIN ---

    @Test
    fun aOneUserDeviceUnlocksWithItsCredentialAloneButTheSessionIsNotRecentlyVerified() = env().run {
        addUser("mali")
        api {
            val device = enrolled(this@run, "mali")

            val unlocked = credentialOnly(device, "mali")
            assertEquals(HttpStatusCode.OK, unlocked.status, unlocked.bodyAsText())
            val token = unlocked.parsed(UnlockResponse.serializer()).accessToken
            assertContains(audit(), "unlock.ok user=mali device=${device.deviceId} ip=localhost result=ok,credential-only")
            // Pairing someone needs a recent PIN: the credential alone is not one.
            assertEquals(ErrorCode.REAUTH_REQUIRED, createPairing(token, "mali").errorCode())
            assertEquals(HttpStatusCode.NoContent, reauth(token, ReauthRequest(pin = PIN)).status)
            assertEquals(HttpStatusCode.Created, createPairing(token, "mali").status)
        }
    }

    @Test
    fun turnedOffAnUnlockWithoutThePinIsAWrongPin() = env("$NO_BACKOFF  device:\n    unlock-without-pin: false\n").run {
        addUser("mali")
        api {
            val device = enrolled(this@run, "mali")

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
            val shared = enrolled(this@run, "mali", DeviceMode.SHARED)
            enroll(redeemed(this@run, "kham").enrollmentToken, enrollRequest(newPin = "246801", deviceId = shared.deviceId, deviceCredential = shared.credential))

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
            val device = enrolled(this@run, "noy")

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
            val device = enrolled(this@run, "mali")
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
            val device = enrolled(this@run, "mali")
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
            val pairing = pair("noy")
            val redeemed = redeem(RedeemRequest(secret = pairing.secret)).parsed(RedeemResponse.serializer())
            redeem(RedeemRequest(secret = pairing.secret)) // fails
            val device = enroll(redeemed.enrollmentToken, enrollRequest(newPassword = OP_PASSWORD, newPin = PIN)).parsed(EnrollResponse.serializer())
            val token = accessToken(device, "noy", OP_PASSWORD, password = true)
            unlock(device, "noy", "wrong-password-1234", password = true)
            val secrets = listOf(
                pairing.secret, pairing.manualCode!!, pairing.manualCode.replace("-", ""), redeemed.enrollmentToken, device.credential, token,
                OP_PASSWORD, PIN, "wrong-password-1234",
            ) + root.resolve("user/noy.yml").toFile().readLines().filter { "argon2id" in it }.map { it.substringAfter("\"").substringBeforeLast("\"") }

            val audit = audit()
            for (event in listOf("pair.created", "redeem.ok", "redeem.fail", "enroll.ok", "unlock.ok", "unlock.fail")) assertContains(audit, " $event ")
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
