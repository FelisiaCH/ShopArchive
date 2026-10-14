@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, UnsafeNumber::class, ExperimentalUnsignedTypes::class, ExperimentalAtomicApi::class)

package xyz.felismp.shoparchive.app.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateRef
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustRef
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/*
 * The same rule as the JVM's Pin.kt: the pin is SHA-256 of the leaf certificate's SubjectPublicKeyInfo; the CA chain and the host name are not
 * trusted, only the key is. Here it is decided in the Darwin engine's challenge handler, which sees the chain the server sent (nothing is evaluated).
 */

private fun sha256(input: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(inputPinned.addressOf(0), input.size.convert(), digestPinned.addressOf(0))
        }
    }
    return digest.toByteArray()
}

/** SHA-256 of the SPKI DER of [cert]; null unless its key is P-256 (Security returns only the point, [p256Spki] puts the header back). */
internal fun spkiSha256(cert: SecCertificateRef): ByteArray? {
    val key = SecCertificateCopyKey(cert) ?: return null
    try {
        val point = (CFBridgingRelease(SecKeyCopyExternalRepresentation(key, null)) as? NSData)?.toByteArray() ?: return null
        return p256Spki(point)?.let(::sha256)
    } finally {
        CFBridgingRelease(key)
    }
}

/** [spkiSha256] of the first certificate the server sent. */
internal fun leafSpkiSha256(trust: SecTrustRef): ByteArray? = SecTrustGetCertificateAtIndex(trust, 0)?.let(::spkiSha256)

/** True when the leaf of [trust] has the key [expected] (a decoded pin; null when the pin is malformed, which matches nothing). */
internal fun pinAllows(trust: SecTrustRef, expected: ByteArray?): Boolean {
    val actual = leafSpkiSha256(trust)
    return expected != null && actual != null && constantTimeEquals(expected, actual)
}

internal class PinMismatchException : Exception("SPKI pin mismatch")

private class TofuDone : Exception("fingerprint recorded; connection refused on purpose")

/**
 * A client that talks only to a server whose leaf key hashes to [pin], whatever address it is reached at. A wrong key throws from the
 * challenge handler; the engine then cancels the connection and fails the call with that same exception. One delegate serves https:// and wss://.
 */
internal fun pinnedHttpClient(pin: String, connectTimeoutMs: Long): HttpClient {
    val expected = decodePin(pin)
    return HttpClient(Darwin) {
        expectSuccess = false
        install(WebSockets) { pingIntervalMillis = 20_000 }
        engine {
            // NSURLSession has no separate connect timeout: this is how long a request, and the live WebSocket (kept busy by the 20 s ping), may be silent.
            configureSession { timeoutIntervalForRequest = maxOf(connectTimeoutMs, 30_000L) / 1000.0 }
            handleChallenge { _, _, challenge, completionHandler ->
                val space = challenge.protectionSpace
                val trust = space.serverTrust
                when {
                    space.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null ->
                        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge.convert(), null)
                    pinAllows(trust, expected) ->
                        completionHandler(NSURLSessionAuthChallengeUseCredential.convert(), NSURLCredential.credentialForTrust(trust))
                    else -> throw PinMismatchException()
                }
            }
        }
    }
}

/** True when [e] or something it wraps is a failed pin check. */
internal fun isPinFailure(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(10).any { it is PinMismatchException }

/** An [ApiClient] that talks only to a server whose key is [pin], through the Darwin engine. */
fun ApiClient(serverId: String, pin: String, endpoints: List<String>, connectTimeoutMs: Long = 5_000): ApiClient =
    ApiClient(serverId, endpoints, pinnedHttpClient(pin, connectTimeoutMs), ::isPinFailure)

/**
 * Trust on first use for the manual-code flow: starts a TLS handshake with [endpoint] (`host:port`), records the leaf's SPKI
 * fingerprint and refuses the connection, so no HTTP request is ever sent. Returns the fingerprint in groups of four hex digits for the user
 * to compare with the server console.
 */
suspend fun probeFingerprint(endpoint: String, timeoutMs: Long = 5_000): String {
    val seen = AtomicReference<String?>(null)
    val client = HttpClient(Darwin) {
        expectSuccess = false
        engine {
            configureSession { timeoutIntervalForRequest = timeoutMs / 1000.0 }
            handleChallenge { _, _, challenge, _ ->
                challenge.protectionSpace.serverTrust?.let(::leafSpkiSha256)?.let { seen.store(formatFingerprint(it.toHexUpper())) }
                throw TofuDone()
            }
        }
    }
    try {
        client.get("https://$endpoint/")
    } catch (_: Exception) {
        // Expected: the connection was refused. Anything else is judged by whether a fingerprint was seen.
        currentCoroutineContext().ensureActive()
    } finally {
        client.close()
    }
    return seen.load() ?: throw ClientError.Unreachable()
}
