package xyz.felismp.shoparchive.spike.app

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

// Compiled into BOTH androidMain and desktopMain (see app/build.gradle.kts).

/** `publicKey.encoded` is the X.509 SubjectPublicKeyInfo DER, exactly what the server hashes. */
private fun spkiSha256(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

actual fun pinnedHttpClient(pin: String, block: HttpClientConfig<*>.() -> Unit): HttpClient {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("client certificates are not supported")
        }

        // The CA chain is ignored on purpose: only the leaf's SPKI hash counts.
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
            if (!pinMatches(spkiSha256(leaf), pin)) throw CertificateException("SPKI pin mismatch")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }

    // Host names/IPs are not what we trust; the pin of the peer certificate is.
    val hostnameVerifier = HostnameVerifier { _, session ->
        try {
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
            leaf != null && pinMatches(spkiSha256(leaf), pin)
        } catch (_: SSLPeerUnverifiedException) {
            false
        }
    }

    return HttpClient(OkHttp) {
        block()
        engine {
            config {
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier(hostnameVerifier)
            }
        }
    }
}
