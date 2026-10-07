package xyz.felismp.shoparchive.app.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

/*
 * The pin is the server key this device saved when it first added the server: SHA-256 of the leaf certificate's SubjectPublicKeyInfo, hex without
 * spaces (the console prints the same value in groups of four). Same approach as the P00 spike (device test T2):
 * the CA chain and the host name are not trusted, only the key is.
 */

/** 32 bytes from [pin] (hex, any case; spaces tolerated), or null when it is not exactly that. */
internal fun decodePin(pin: String): ByteArray? {
    val hex = pin.filterNot { it.isWhitespace() }
    if (hex.length != 64) return null
    return ByteArray(32) { i ->
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        ((hi shl 4) or lo).toByte()
    }
}

/** `publicKey.encoded` is the X.509 SubjectPublicKeyInfo DER, exactly what the server hashes. */
private fun spkiSha256(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

/** Uppercase hex in groups of four, as the server console prints it. */
fun formatFingerprint(pin: String): String = pin.filterNot { it.isWhitespace() }.uppercase().chunked(4).joinToString(" ")

/** The pin to store for a fingerprint the user confirmed. */
fun fingerprintToPin(fingerprint: String): String = fingerprint.filterNot { it.isWhitespace() }.uppercase()

private class PinMismatchException : CertificateException("SPKI pin mismatch")

private class PinTrust(pin: String) : X509TrustManager {
    private val expected = decodePin(pin)

    fun matches(cert: X509Certificate?): Boolean =
        expected != null && cert != null && MessageDigest.isEqual(expected, spkiSha256(cert))

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client certificates are not supported")
    }

    // The CA chain is ignored on purpose: only the leaf's SPKI hash counts.
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (!matches(chain.firstOrNull())) throw PinMismatchException()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** An OkHttp client that talks only to a server whose leaf key hashes to [pin], whatever address it is reached at. */
internal fun pinnedOkHttp(pin: String, connectTimeoutMs: Long): OkHttpClient.Builder {
    val trust = PinTrust(pin)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    // Server certificates are self-signed for LAN IPs and the address used may not be one of them: the pin decides, not the name.
    val verifier = HostnameVerifier { _, session ->
        try {
            trust.matches(session.peerCertificates.firstOrNull() as? X509Certificate)
        } catch (_: SSLPeerUnverifiedException) {
            false
        }
    }
    return OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trust)
        .hostnameVerifier(verifier)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
}

internal fun pinnedHttpClient(pin: String, connectTimeoutMs: Long): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(WebSockets) { pingIntervalMillis = 20_000 }
    engine { preconfigured = pinnedOkHttp(pin, connectTimeoutMs).build() }
}

/** True when [e] or something it wraps is a failed pin check. */
internal fun isPinFailure(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(10).any { it is PinMismatchException || it is SSLPeerUnverifiedException }

private class TofuDone : CertificateException("fingerprint recorded; handshake aborted on purpose")

/**
 * Trust on first use for the manual-code flow: starts a TLS handshake with [endpoint] (`host:port`), records the
 * leaf's SPKI fingerprint and aborts the handshake, so no HTTP request is ever sent. Returns the fingerprint in
 * groups of four hex digits for the user to compare with the server console.
 */
fun probeFingerprint(endpoint: String, timeoutMs: Long = 5_000): String {
    var seen: String? = null
    val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("client certificates are not supported")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            seen = chain.firstOrNull()?.let { formatFingerprint(hex(spkiSha256(it))) }
            throw TofuDone()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    val client = OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trust)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
    try {
        client.newCall(Request.Builder().url("https://$endpoint/").build()).execute().close()
    } catch (_: IOException) {
        // Expected: the handshake was aborted. Anything else is judged by whether a fingerprint was seen.
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    return seen ?: throw ClientError.Unreachable()
}
