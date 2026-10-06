package xyz.felismp.shoparchive.server.net

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.tls.CertificateHosts
import xyz.felismp.shoparchive.server.tls.CertificateManager
import xyz.felismp.shoparchive.server.tls.ServerCertificate
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The real listener on a real port with real TLS, no mocking of the transport. */
class HttpServerTest {
    @TempDir
    lateinit var root: Path

    private val info = InfoResponse("11111111-2222-3333-4444-555555555555", "Test Shop", "hello", "9.9", PROTOCOL_VERSION)
    private val bans by lazy { IpBans(root, RecordingLog()).also { it.load() } }
    private val services by lazy {
        Services().apply {
            register(InfoService::class.java, object : InfoService { override fun info() = info }, 0, "test")
            register(IpBanService::class.java, bans, 0, "test")
        }
    }
    private lateinit var certificate: ServerCertificate
    private var server: HttpServer? = null

    @BeforeTest
    fun setUp() {
        prepareRoot(root)
        certificate = CertificateManager(root, RecordingLog()).loadOrCreate(CertificateHosts(listOf("127.0.0.1"), emptyList()), 825)
    }

    @AfterTest
    fun tearDown() {
        server?.stop()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun start(port: Int = freePort(), timeoutSeconds: Int = 30): Int {
        server = HttpServer(HttpSettings("127.0.0.1", port, 1024 * 1024L, timeoutSeconds), certificate, services).also { it.start() }
        return port
    }

    /** A client that trusts this one certificate and nothing else. */
    private fun pinnedClient() = HttpClient.newBuilder().sslContext(pinnedContext()).build()

    private fun pinnedContext(): SSLContext {
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", certificate.keyStore.getCertificate(certificate.alias) as X509Certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
    }

    private fun get(client: HttpClient, port: Int, path: String, protocol: Boolean = true): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("https://127.0.0.1:$port$path")).apply { if (protocol) header(PROTOCOL_HEADER, "$PROTOCOL_VERSION") }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun aClientThatTrustsOnlyTheServersCertificateGetsInfoOverHttps() {
        val port = start()

        val response = get(pinnedClient(), port, "/api/v1/info", protocol = false)

        assertEquals(200, response.statusCode())
        assertEquals(info, Json.decodeFromString(InfoResponse.serializer(), response.body()))
    }

    @Test
    fun aClientThatDoesNotTrustTheCertificateCannotConnect() {
        val port = start()

        assertFailsWith<SSLHandshakeException> { get(HttpClient.newHttpClient(), port, "/api/v1/info", protocol = false) }
    }

    @Test
    fun thereIsNoPlainHttpListener() {
        val port = start()

        val plain = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/info")).build()
        val outcome = runCatching { HttpClient.newHttpClient().send(plain, HttpResponse.BodyHandlers.ofString()) }

        assertTrue(outcome.isFailure || outcome.getOrThrow().statusCode() != 200)
    }

    @Test
    fun theCertificateServedIsTheOneWhoseFingerprintIsShown() {
        val port = start()
        var served: X509Certificate? = null
        val capture = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { served = chain[0] }
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        val params = context.socketFactory.createSocket("127.0.0.1", port) as javax.net.ssl.SSLSocket
        params.use { it.startHandshake() }

        assertEquals(certificate.fingerprint, xyz.felismp.shoparchive.server.tls.fingerprintOf(served!!))
    }

    @Test
    fun aBannedAddressIsRefusedOverTheRealConnectionUntilPardoned() {
        val port = start()
        val client = pinnedClient()
        assertEquals(200, get(client, port, "/api/v1/info", protocol = false).statusCode())

        bans.ban("127.0.0.1", "test", "Console")
        val refused = get(client, port, "/api/v1/info", protocol = false)

        assertEquals(403, refused.statusCode())
        assertEquals(ErrorCode.BANNED, Json.decodeFromString(ErrorResponse.serializer(), refused.body()).code)

        bans.pardon("127.0.0.1")
        assertEquals(200, get(client, port, "/api/v1/info", protocol = false).statusCode())
    }

    @Test
    fun aMissingProtocolHeaderOnAnotherPathIsAMismatchOverTheRealConnection() {
        val port = start()

        val response = get(pinnedClient(), port, "/api/v1/users", protocol = false)

        assertEquals(400, response.statusCode())
        assertEquals(ErrorCode.PROTOCOL_MISMATCH, Json.decodeFromString(ErrorResponse.serializer(), response.body()).code)
    }

    @Test
    fun openConnectionsCountsTheClientsThatAreConnected() {
        val port = start()
        assertEquals(0, server!!.openConnections)

        pinnedClient().let { client ->
            get(client, port, "/api/v1/info", protocol = false)
            assertEquals(1, server!!.openConnections)
        }
    }

    @Test
    fun aPortThatIsAlreadyInUseFailsTheStartWithAClearMessage() {
        ServerSocket(0).use { occupied ->
            val error = assertFailsWith<HttpStartException> { start(occupied.localPort) }

            assertTrue("127.0.0.1:${occupied.localPort}" in error.message.orEmpty(), error.message)
            server = null
        }
    }

    @Test
    fun aClientThatStopsSendingARequestIsDroppedAfterTheTimeout() {
        val port = start(timeoutSeconds = 5)
        val socket = pinnedContext().socketFactory.createSocket("127.0.0.1", port) as javax.net.ssl.SSLSocket
        socket.use {
            it.soTimeout = 15_000
            it.outputStream.write("GET /api/v1/info HTTP/1.1\r\nHost: x\r\n".toByteArray()) // headers never finished
            it.outputStream.flush()
            val started = System.nanoTime()

            val end = runCatching { it.inputStream.read() }

            val seconds = (System.nanoTime() - started) / 1_000_000_000
            assertTrue(end.isSuccess && end.getOrThrow() == -1 || end.isFailure && end.exceptionOrNull() !is java.net.SocketTimeoutException, "the server did not drop the connection: $end")
            assertTrue(seconds in 4..12, "dropped after $seconds s")
        }
    }
}
