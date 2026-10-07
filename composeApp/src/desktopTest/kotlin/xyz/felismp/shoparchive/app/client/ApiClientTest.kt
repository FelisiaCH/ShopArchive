package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.runBlocking
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiClientTest {
    private val unlockOk = """{"accessToken":"TOKEN-1","expiresInSeconds":900}"""
    private val unlockReq = UnlockRequest("dev", "alice", "cred", pin = "1234")
    private fun error(code: ErrorCode, status: Int, extra: String = "") =
        json("""{"code":"${code.name}","message":"nope","protocol":1$extra}""", status)

    @Test fun sendsProtocolHeaderAndBearerToken() = runBlocking {
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else json("""{"ok":true}""") }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            api.get<kotlinx.serialization.json.JsonObject>("/api/v1/entries")
            assertTrue(s.requests.all { it.headers[PROTOCOL_HEADER] == "$PROTOCOL_VERSION" })
            assertEquals(null, s.requests[0].headers["Authorization"])
            assertEquals("Bearer TOKEN-1", s.requests[1].headers["Authorization"])
            api.close()
        }
    }

    @Test fun authorizedCallWithoutTokenIsLockedAndSendsNothing() = runBlocking {
        TlsServer().use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            assertFailsWith<ClientError.Locked> { api.config() }
            assertEquals(0, s.requests.size)
            api.close()
        }
    }

    @Test fun errorJsonBecomesTypedException() = runBlocking {
        TlsServer { error(ErrorCode.REAUTH_REQUIRED, 403, ""","passwordRequired":true""") }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            val e = assertFailsWith<ClientError.Api> { api.unlock(unlockReq) }
            assertEquals(ErrorCode.REAUTH_REQUIRED, e.code)
            assertEquals(403, e.status)
            assertTrue(e.passwordRequired)
            assertFalse(api.isUnlocked)
            api.close()
        }
    }

    @Test fun theReasonKeyOfAnErrorIsKept() = runBlocking {
        TlsServer { error(ErrorCode.INVALID_REQUEST, 400, ""","reason":"pin.sequence"""") }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            val e = assertFailsWith<ClientError.Api> { api.redeem(RedeemRequest(secret = "x")) }
            assertEquals("pin.sequence", e.reason)
            api.close()
        }
    }

    @Test fun loginPostsTheRequestWithoutATokenAndARefusalKeepsItsReason() = runBlocking {
        val alice = LoginRequest("alice", "Till 1", "windows", DeviceMode.SHARED, pin = "483926")
        TlsServer { r ->
            if ("\"bob\"" in r.body?.utf8().orEmpty()) error(ErrorCode.UNAUTHORIZED, 401, ""","reason":"pin.not-set"""")
            else json("""{"deviceId":"dev-9","credential":"cred-9"}""")
        }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            assertEquals(EnrollResponse("dev-9", "cred-9"), api.login(alice))
            assertEquals("POST", s.requests[0].method)
            assertEquals("/api/v1/login", s.requests[0].target)
            assertEquals(null, s.requests[0].headers["Authorization"])
            assertEquals(alice, clientJson.decodeFromString<LoginRequest>(s.requests[0].body!!.utf8()))
            val e = assertFailsWith<ClientError.Api> { api.login(alice.copy(username = "bob", pin = null)) }
            assertEquals(401, e.status)
            assertEquals(ErrorCode.UNAUTHORIZED, e.code)
            assertEquals(ErrorReasons.PIN_NOT_SET, e.reason)
            assertFalse(api.isUnlocked)
            api.close()
        }
    }

    @Test fun nonJsonErrorBodyStillGivesAnApiError() = runBlocking {
        TlsServer { json("<html>", 502) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            val e = assertFailsWith<ClientError.Api> { api.redeem(RedeemRequest(secret = "x")) }
            assertEquals(502, e.status)
            assertEquals(ErrorCode.INTERNAL, e.code)
            api.close()
        }
    }

    @Test fun protocolMismatchHasItsOwnError() = runBlocking {
        TlsServer { json("""{"code":"PROTOCOL_MISMATCH","message":"update","protocol":2}""", 400) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            val e = assertFailsWith<ClientError.ProtocolMismatch> { api.redeem(RedeemRequest(secret = "x")) }
            assertEquals(2, e.serverProtocol)
            api.close()
        }
    }

    @Test fun rateLimitCarriesRetryAfter() = runBlocking {
        TlsServer { error(ErrorCode.RATE_LIMITED, 429).newBuilder().addHeader("Retry-After", "7").build() }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            assertEquals(7, assertFailsWith<ClientError.Api> { api.unlock(unlockReq) }.retryAfterSeconds)
            api.close()
        }
    }

    @Test fun status401ClearsTheTokenAndSurfacesLocked() = runBlocking {
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else error(ErrorCode.UNAUTHORIZED, 401) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            assertTrue(api.isUnlocked)
            assertFailsWith<ClientError.Locked> { api.config() }
            assertFalse(api.isUnlocked)
            api.close()
        }
    }

    @Test fun wrongPinOnReauthIsAnApiErrorAndKeepsTheToken() = runBlocking {
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else error(ErrorCode.UNAUTHORIZED, 401) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            assertEquals(ErrorCode.UNAUTHORIZED, assertFailsWith<ClientError.Api> { api.reauth(ReauthRequest(pin = "0000")) }.code)
            assertTrue(api.isUnlocked)
            api.close()
        }
    }

    @Test fun reauthRequiredOn401KeepsTheTokenAndIsAnApiError() = runBlocking {
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else error(ErrorCode.REAUTH_REQUIRED, 401) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            assertEquals(ErrorCode.REAUTH_REQUIRED, assertFailsWith<ClientError.Api> { api.revokeDevice("dev-2") }.code)
            assertTrue(api.isUnlocked)
            assertEquals("DELETE", s.requests.last().method)
            assertEquals("/api/v1/devices/dev-2", s.requests.last().target)
            api.close()
        }
    }

    @Test fun wrongCredentialOnUnlockIs401ApiErrorNotLocked() = runBlocking {
        TlsServer { error(ErrorCode.UNAUTHORIZED, 401) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            assertEquals(ErrorCode.UNAUTHORIZED, assertFailsWith<ClientError.Api> { api.unlock(unlockReq) }.code)
            api.close()
        }
    }

    @Test fun refusedFirstEndpointFallsBackAndIsRemembered() = runBlocking {
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else json("""{"ok":true}""") }.use { s ->
            val dead = closedEndpoint()
            val api = ApiClient("sid", s.pin, listOf(dead, s.endpoint), connectTimeoutMs = 1_000)
            api.unlock(unlockReq)
            assertEquals(listOf(s.endpoint, dead), api.endpoints)
            // The token is kept across the switch: the next authorized call needs no unlock.
            api.get<kotlinx.serialization.json.JsonObject>("/api/v1/x")
            assertEquals("Bearer TOKEN-1", s.requests.last().headers["Authorization"])
            api.close()
        }
    }

    @Test fun httpErrorDoesNotFallBack() = runBlocking {
        TlsServer { error(ErrorCode.PAIRING_INVALID, 400) }.use { first ->
            TlsServer { json("{}") }.use { second ->
                val api = ApiClient("sid", first.pin, listOf(first.endpoint, second.endpoint))
                assertFailsWith<ClientError.Api> { api.redeem(RedeemRequest(secret = "x")) }
                assertEquals(0, second.requests.size)
                api.close()
            }
        }
    }

    @Test fun allEndpointsDownIsUnreachable() = runBlocking {
        val api = ApiClient("sid", "00".repeat(32), listOf(closedEndpoint(), closedEndpoint()), connectTimeoutMs = 1_000)
        assertFailsWith<ClientError.Unreachable> { api.redeem(RedeemRequest(secret = "x")) }
        api.close()
    }

    @Test fun configUpdatesEndpointsKeepingTheActiveOneFirst() = runBlocking {
        lateinit var s: TlsServer
        s = TlsServer { r ->
            if (r.target == "/api/v1/unlock") json(unlockOk)
            else json("""{"auth":{"pinLength":4,"passwordRequired":false,"autoLockSharedMinutes":1,"autoLockPersonalMinutes":5,"biometricsPersonal":false,"reauthWindowMinutes":5},
                "endpoints":["8.8.8.8:1","10.1.1.1:2","${s.endpoint}"],"currencies":[]}""")
        }
        s.use {
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            val cfg: ConfigResponse = api.config()
            assertEquals(3, cfg.endpoints.size)
            assertEquals(listOf(s.endpoint, "10.1.1.1:2", "8.8.8.8:1"), api.endpoints)
            api.close()
        }
    }

    @Test fun configKeepsTheActiveEndpointFirstEvenWhenTheServerDoesNotListIt() = runBlocking {
        val s = TlsServer { r ->
            if (r.target == "/api/v1/unlock") json(unlockOk)
            else json("""{"auth":{"pinLength":4,"passwordRequired":false,"autoLockSharedMinutes":1,"autoLockPersonalMinutes":5,"biometricsPersonal":false,"reauthWindowMinutes":5},
                "endpoints":["8.8.8.8:1","10.1.1.1:2"],"currencies":[]}""")
        }
        s.use {
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            api.config()
            assertEquals(listOf(s.endpoint, "10.1.1.1:2", "8.8.8.8:1"), api.endpoints)
            api.close()
        }
    }

    @Test fun endpointsAreOrderedLanFirstKeepingGivenOrder() {
        assertEquals(
            listOf("192.168.1.5:1", "10.0.0.2:1", "shop.local:1", "203.0.113.9:1", "example.com:1"),
            orderEndpoints(listOf("203.0.113.9:1", "192.168.1.5:1", "example.com:1", "10.0.0.2:1", "shop.local:1")),
        )
        assertEquals(listOf("172.20.0.1:1", "172.32.0.1:1"), orderEndpoints(listOf("172.32.0.1:1", "172.20.0.1:1")))
        assertEquals(listOf("[fd00::1]:1", "[2001:db8::1]:1"), orderEndpoints(listOf("[2001:db8::1]:1", "[fd00::1]:1")))
    }

    @Test fun downloadStreamsTheUpdateIntoTheTargetWithTheTokenAndNoPartFileLeft() = runBlocking {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        TlsServer { r ->
            if (r.target == "/api/v1/unlock") json(unlockOk)
            else mockwebserver3.MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build()
        }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            val dir = java.nio.file.Files.createTempDirectory("sa-download")
            val target = dir.resolve("ShopArchive-1.2.0.apk")
            var last = 0L
            api.downloadUpdate("ShopArchive-1.2.0.apk", target) { last = it }
            assertTrue(bytes.contentEquals(java.nio.file.Files.readAllBytes(target)))
            assertEquals(bytes.size.toLong(), last)
            assertEquals(listOf("ShopArchive-1.2.0.apk"), java.nio.file.Files.list(dir).use { it.map { p -> p.fileName.toString() }.toList() })
            val request = s.requests.last()
            assertEquals("/api/v1/updates/ShopArchive-1.2.0.apk", request.target)
            assertEquals("Bearer TOKEN-1", request.headers["Authorization"])
            api.close()
        }
    }

    @Test fun aRefusedDownloadIsAnApiErrorAndLeavesNothing() = runBlocking {
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else error(ErrorCode.NOT_FOUND, 404) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            val dir = java.nio.file.Files.createTempDirectory("sa-download")
            val e = assertFailsWith<ClientError.Api> { api.downloadUpdate("ShopArchive-9.9.9.apk", dir.resolve("x.apk")) { } }
            assertEquals(ErrorCode.NOT_FOUND, e.code)
            assertEquals(0, java.nio.file.Files.list(dir).use { it.count() })
            api.close()
        }
    }

    @Test fun aHugeErrorBodyOfADownloadIsReadOnlyToItsStart() = runBlocking {
        val huge = """{"code":"NOT_FOUND","message":"${"x".repeat(200_000)}","protocol":1}"""
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else json(huge, 404) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            val dir = java.nio.file.Files.createTempDirectory("sa-download")
            val e = assertFailsWith<ClientError.Api> { api.downloadUpdate("ShopArchive-9.9.9.apk", dir.resolve("x.apk")) { } }
            // The body was cut at 64 KiB, so it no longer parses: the status still says what happened.
            assertEquals(404, e.status)
            assertEquals(ErrorCode.INTERNAL, e.code)
            api.close()
        }
    }

    @Test fun aCommandIsPostedAsALineAndAnswersItsLines() = runBlocking {
        TlsServer { r ->
            when (r.target) {
                "/api/v1/unlock" -> json(unlockOk)
                "/api/v1/command" -> json("""{"lines":["one","two"]}""")
                else -> json("""{"candidates":["status","stop"]}""")
            }
        }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            assertEquals(listOf("one", "two"), api.runCommand("user pair noy"))
            assertEquals(listOf("status", "stop"), api.completeCommand("st"))
            assertEquals("""{"line":"user pair noy"}""", s.requests[1].body?.utf8())
            assertEquals("POST", s.requests[1].method)
            assertEquals("/api/v1/command/complete", s.requests[2].target)
            assertEquals("Bearer TOKEN-1", s.requests[2].headers["Authorization"])
            api.close()
        }
    }
}
