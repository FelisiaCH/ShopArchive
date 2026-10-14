package xyz.felismp.shoparchive.app.client

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

/** A self-signed HTTPS server on 127.0.0.1 whose certificate only names [san], like the real server naming its LAN IPs. */
class TlsServer(san: String = "server.invalid", handler: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(200).body("{}").build() }) : AutoCloseable {
    val held: HeldCertificate = HeldCertificate.Builder().addSubjectAlternativeName(san).ecdsa256().build()
    val requests = mutableListOf<RecordedRequest>()
    val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return handler(request)
            }
        }
        start(InetAddress.getByName("127.0.0.1"), 0)
    }
    val endpoint: String get() = "127.0.0.1:${server.port}"
    val pin: String = spkiHex(held)
    override fun close() = server.close()
}

fun spkiHex(held: HeldCertificate): String =
    MessageDigest.getInstance("SHA-256").digest(held.certificate.publicKey.encoded).joinToString("") { "%02X".format(it) }

/** A `host:port` nothing listens on. */
fun closedEndpoint(): String = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { "127.0.0.1:${it.localPort}" }

fun json(body: String, code: Int = 200): MockResponse = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
