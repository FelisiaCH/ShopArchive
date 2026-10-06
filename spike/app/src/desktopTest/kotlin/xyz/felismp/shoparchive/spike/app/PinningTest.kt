package xyz.felismp.shoparchive.spike.app

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real TLS + real Ktor server with a generated ECDSA P-256 self-signed cert; exercises the same pinning code the app ships. */
class PinningTest {
    private val password = "changeit"

    // Cert SAN is deliberately NOT 127.0.0.1: a matching pin must succeed anyway (host name is not what is trusted).
    private fun keyStore(domain: String) = buildKeyStore {
        certificate("spike") {
            hash = io.ktor.network.tls.extensions.HashAlgorithm.SHA256
            sign = io.ktor.network.tls.extensions.SignatureAlgorithm.ECDSA
            keySizeInBits = 256
            password = this@PinningTest.password
            domains = listOf(domain)
        }
    }

    private fun pinOf(ks: KeyStore) =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(ks.getCertificate("spike").publicKey.encoded))

    private val serverKeys = keyStore("not-this-host.example")
    private val goodPin = pinOf(serverKeys)
    private val wrongPin = pinOf(keyStore("other.example")) // a real, well-formed pin of a DIFFERENT key
    private val port = ServerSocket(0).use { it.localPort }
    private val baseUrl = "https://127.0.0.1:$port"

    private val server = embeddedServer(Netty, configure = {
        sslConnector(serverKeys, "spike", { password.toCharArray() }, { password.toCharArray() }) { this.port = this@PinningTest.port }
    }) {
        install(WebSockets)
        routing {
            get("/ping") { call.respondText("""{"ok":true}""") }
            webSocket("/ws") { for (f in incoming) if (f is Frame.Text) send(Frame.Text(f.readText())) }
        }
    }

    @BeforeTest fun start() { server.start(wait = false) }
    @AfterTest fun stop() { server.stop(100, 500) }

    private fun causes(t: Throwable) = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" <- ")

    @Test fun httpsCorrectPinSucceeds() = runBlocking {
        val r = httpsPing(baseUrl, goodPin)
        assertEquals("200 {\"ok\":true}", r)
    }

    @Test fun httpsWrongPinFailsBecauseOfPin() = runBlocking {
        val e = assertFailsWith<Throwable> { httpsPing(baseUrl, wrongPin) }
        assertContains(causes(e), "SPKI pin mismatch")
    }

    @Test fun wsCorrectPinSucceeds() = runBlocking {
        assertTrue(wsEcho(baseUrl, goodPin).startsWith("echo OK"))
    }

    @Test fun wsWrongPinFailsBecauseOfPin() = runBlocking {
        val e = assertFailsWith<Throwable> { wsEcho(baseUrl, wrongPin) }
        assertContains(causes(e), "SPKI pin mismatch")
    }

    @Test fun malformedPinIsRejectedBeforeConnecting() = runBlocking {
        assertFailsWith<IllegalArgumentException> { httpsPing(baseUrl, "not-a-pin") }
        assertNull(decodePin(Base64.getEncoder().encodeToString(ByteArray(31))))
    }
}
