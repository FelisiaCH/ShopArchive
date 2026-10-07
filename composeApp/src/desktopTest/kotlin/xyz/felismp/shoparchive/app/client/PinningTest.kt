package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.runBlocking
import xyz.felismp.shoparchive.shared.RedeemRequest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinningTest {
    private val redeemOk = """{"enrollmentToken":"t","username":"alice","passwordRequired":false,"hasPassword":false,"hasPin":false,"pinLength":4}"""

    @Test fun rightPinConnectsEvenThoughCertificateDoesNotNameTheAddress() = runBlocking {
        TlsServer(san = "server.invalid") { json(redeemOk) }.use { s ->
            // The address is 127.0.0.1 but the SAN says server.invalid: only the pin verifier lets this through.
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            assertEquals("alice", api.redeem(RedeemRequest(secret = "x")).username)
            api.close()
        }
    }

    @Test fun pinInServerConsoleFormatOrLowercaseAlsoMatches() = runBlocking {
        TlsServer { json(redeemOk) }.use { s ->
            val api = ApiClient("sid", formatFingerprint(s.pin).lowercase(), listOf(s.endpoint))
            assertEquals("alice", api.redeem(RedeemRequest(secret = "x")).username)
            api.close()
        }
    }

    @Test fun wrongPinIsAPinMismatchAndNothingIsSent() = runBlocking {
        TlsServer().use { s ->
            val api = ApiClient("sid", "00".repeat(32), listOf(s.endpoint))
            val e = assertFailsWith<ClientError.PinMismatch> { api.redeem(RedeemRequest(secret = "x")) }
            assertEquals(s.endpoint, e.endpoint, "says which address showed the other key")
            assertEquals(0, s.requests.size)
            api.close()
        }
    }

    @Test fun malformedPinNeverMatches() = runBlocking {
        TlsServer().use { s ->
            val api = ApiClient("sid", "nope", listOf(s.endpoint))
            assertFailsWith<ClientError.PinMismatch> { api.redeem(RedeemRequest(secret = "x")) }
            api.close()
        }
    }

    @Test fun pinMismatchDoesNotFallBackToTheNextEndpoint() = runBlocking {
        TlsServer().use { wrong ->
            TlsServer { json(redeemOk) }.use { right ->
                val api = ApiClient("sid", right.pin, listOf(wrong.endpoint, right.endpoint))
                assertFailsWith<ClientError.PinMismatch> { api.redeem(RedeemRequest(secret = "x")) }
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
