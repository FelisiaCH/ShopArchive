package xyz.felismp.shoparchive.server.auth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The per-address limit on the endpoints anyone can call (Ktor RateLimit): 429, `Retry-After`, the shared error code, and nothing else limited. */
class RateLimitTest {
    @TempDir
    lateinit var root: Path

    private val redeemWrong = RedeemRequest(secret = "nonsense")

    @Test
    fun theEleventhRedeemInAMinuteFromOneAddressIs429WithRetryAfterAndTheSharedCode() = AuthEnv(root).run {
        api(rateLimitPerMinute = 10) {
            repeat(10) { assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/pair/redeem", RedeemRequest.serializer(), redeemWrong).status, "request ${it + 1}") }

            val limited = postJson("/api/v1/pair/redeem", RedeemRequest.serializer(), redeemWrong)

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode())
            val retryAfter = assertNotNull(limited.headers[HttpHeaders.RetryAfter]).toInt()
            assertTrue(retryAfter in 1..60, "Retry-After: $retryAfter")
        }
    }

    @Test
    fun redeemEnrollAndUnlockShareOneCountPerAddress() = AuthEnv(root).run {
        api(rateLimitPerMinute = 3) {
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/pair/redeem", RedeemRequest.serializer(), redeemWrong).status)
            val enroll = EnrollRequest("Phone", "android", DeviceMode.PERSONAL, newPin = TEST_PIN)
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/enroll", EnrollRequest.serializer(), enroll, "not-a-token").status)
            val unlock = UnlockRequest("11111111-2222-3333-4444-555555555555", "mali", "x", pin = TEST_PIN)
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/unlock", UnlockRequest.serializer(), unlock).status)

            assertEquals(HttpStatusCode.TooManyRequests, postJson("/api/v1/unlock", UnlockRequest.serializer(), unlock).status)
            assertEquals(HttpStatusCode.TooManyRequests, postJson("/api/v1/enroll", EnrollRequest.serializer(), enroll, "not-a-token").status)
        }
    }

    @Test
    fun authenticatedRoutesAndInfoAreNotLimited() = AuthEnv(root).run {
        val device = enroll("mali")
        val token = token(device, "mali")
        api(rateLimitPerMinute = 1) {
            repeat(30) {
                assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(pin = TEST_PIN), token).status, "reauth ${it + 1}")
                assertEquals(HttpStatusCode.OK, getPath("/api/v1/info").status)
            }
        }
    }

    @Test
    fun aLockedAccountIs429WithRetryAfterOverHttpToo() = AuthEnv(root, "config-version: 1\nauth:\n  backoff:\n    start-seconds: 30\n").run {
        val device = enroll("mali")
        api {
            val request = UnlockRequest(device.deviceId, "mali", device.credential, pin = "000000")
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/unlock", UnlockRequest.serializer(), request).status)

            val locked = postJson("/api/v1/unlock", UnlockRequest.serializer(), request.copy(pin = TEST_PIN))

            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            assertEquals(ErrorCode.RATE_LIMITED, locked.errorCode())
            assertEquals("30", locked.headers[HttpHeaders.RetryAfter])
        }
    }
}
