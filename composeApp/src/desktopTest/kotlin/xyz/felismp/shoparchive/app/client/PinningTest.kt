package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.runBlocking
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.LoginRequest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinningTest {
    private val loginOk = """{"deviceId":"dev-1","credential":"cred-1"}"""
    private val loginReq = LoginRequest("alice", "Till 1", "windows", DeviceMode.SHARED, pin = "1234")

    @Test fun rightPinConnectsEvenThoughCertificateDoesNotNameTheAddress() = runBlocking {
        TlsServer(san = "server.invalid") { json(loginOk) }.use { s ->
            // The address is 127.0.0.1 but the SAN says server.invalid: only the pin verifier lets this through.
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            assertEquals("dev-1", api.login(loginReq).deviceId)
            api.close()
        }
    }

    @Test fun pinInServerConsoleFormatOrLowercaseAlsoMatches() = runBlocking {
        TlsServer { json(loginOk) }.use { s ->
            val api = ApiClient("sid", formatFingerprint(s.pin).lowercase(), listOf(s.endpoint))
            assertEquals("dev-1", api.login(loginReq).deviceId)
            api.close()
        }
    }

    @Test fun wrongPinIsAPinMismatchAndNothingIsSent() = runBlocking {
        TlsServer().use { s ->
            val api = ApiClient("sid", "00".repeat(32), listOf(s.endpoint))
            val e = assertFailsWith<ClientError.PinMismatch> { api.login(loginReq) }
            assertEquals(s.endpoint, e.endpoint, "says which address showed the other key")
            assertEquals(0, s.requests.size)
            api.close()
        }
    }

    @Test fun malformedPinNeverMatches() = runBlocking {
        TlsServer().use { s ->
            val api = ApiClient("sid", "nope", listOf(s.endpoint))
            assertFailsWith<ClientError.PinMismatch> { api.login(loginReq) }
            api.close()
        }
    }

    @Test fun pinMismatchDoesNotFallBackToTheNextEndpoint() = runBlocking {
        TlsServer().use { wrong ->
            TlsServer { json(loginOk) }.use { right ->
                val api = ApiClient("sid", right.pin, listOf(wrong.endpoint, right.endpoint))
                assertFailsWith<ClientError.PinMismatch> { api.login(loginReq) }
                assertEquals(0, right.requests.size)
                api.close()
            }
        }
    }

    @Test fun tofuProbeReturnsFingerprintInGroupsOfFourAndSendsNoRequest() {
        TlsServer().use { s ->
            val fingerprint = probeFingerprint(s.endpoint)
            assertEquals(formatFingerprint(s.pin), fingerprint)
            assertTrue(Regex("([0-9A-F]{4} ){15}[0-9A-F]{4}").matches(fingerprint), fingerprint)
            assertEquals(s.pin, fingerprintToPin(fingerprint))
            assertNull(s.server.takeRequest(300, TimeUnit.MILLISECONDS), "the probe must not send an HTTP request")
            assertEquals(0, s.requests.size)
        }
    }

    @Test fun tofuProbeOfClosedPortIsUnreachable() {
        assertFailsWith<ClientError.Unreachable> { probeFingerprint(closedEndpoint(), timeoutMs = 1_000) }
    }
}
