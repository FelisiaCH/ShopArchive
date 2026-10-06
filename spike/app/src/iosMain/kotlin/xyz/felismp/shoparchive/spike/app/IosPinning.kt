@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, UnsafeNumber::class, ExperimentalUnsignedTypes::class)

package xyz.felismp.shoparchive.spike.app

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.CFBridgingRelease
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

private fun sha256(input: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { inputPinned ->
        digest.usePinned { digestPinned ->
            CC_SHA256(inputPinned.addressOf(0), input.size.convert(), digestPinned.addressOf(0))
        }
    }
    return digest.toByteArray()
}

/** SPKI DER of a P-256 leaf = fixed header + the 65-byte X9.63 point Security returns; null for any other key type. */
private fun spkiSha256(cert: SecCertificateRef): ByteArray? {
    val key = SecCertificateCopyKey(cert) ?: return null
    try {
        val point = (CFBridgingRelease(SecKeyCopyExternalRepresentation(key, null)) as? platform.Foundation.NSData)?.toByteArray()
        if (point == null || point.size != 65 || point[0] != 0x04.toByte()) return null
        return sha256(P256_SPKI_HEADER + point)
    } finally {
        CFBridgingRelease(key)
    }
}

/**
 * Darwin pinning as in P08: ServerTrust challenge -> leaf certificate -> SPKI SHA-256 == pin.
 * Match: UseCredential(credentialForTrust). Anything else: Cancel. Never PerformDefaultHandling,
 * never a preconfigured session; the same delegate serves https:// and wss://.
 */
actual fun pinnedHttpClient(pin: String, block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) {
    block()
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            val space = challenge.protectionSpace
            val trust = space.serverTrust
            val digest = trust?.let { SecTrustGetCertificateAtIndex(it, 0) }?.let { spkiSha256(it) }
            if (space.authenticationMethod == NSURLAuthenticationMethodServerTrust && trust != null && digest != null && pinMatches(digest, pin)) {
                completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
            } else {
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }
}
