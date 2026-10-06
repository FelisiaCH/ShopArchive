package xyz.felismp.shoparchive.server.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.net.HttpServer
import xyz.felismp.shoparchive.server.net.HttpSettings
import xyz.felismp.shoparchive.server.net.WS_PING_SECONDS
import xyz.felismp.shoparchive.server.net.wsPingSeconds
import xyz.felismp.shoparchive.server.tls.CertificateHosts
import xyz.felismp.shoparchive.server.tls.CertificateManager
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PIN = "482915"

/** The WebSocket on the real listener with real TLS, spoken to with the JDK's own WebSocket client. */
class WebSocketTest {
    @TempDir
    lateinit var root: Path

    private var server: HttpServer? = null
    private val openSockets = mutableListOf<WebSocket>()

    @AfterTest
    fun tearDown() {
        openSockets.forEach { it.abort() }
        server?.stop()
    }

    private class Feed : WebSocket.Listener {
        val texts = LinkedBlockingQueue<String>()
        val closes = LinkedBlockingQueue<Int>()
        val pings = AtomicInteger()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            texts.add(data.toString())
            webSocket.request(1)
            return null
        }

        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
            pings.incrementAndGet()
            webSocket.request(1) // the client answers with the pong itself
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closes.add(statusCode)
            return null
        }
    }

    private fun start(env: AuthEnv, timeoutSeconds: Int = 30): Int {
        val certificate = CertificateManager(root, RecordingLog()).loadOrCreate(CertificateHosts(listOf("127.0.0.1"), emptyList()), 825)
        val port = ServerSocket(0).use { it.localPort }
        server = HttpServer(HttpSettings("127.0.0.1", port, 1024 * 1024L, timeoutSeconds), certificate, env.services).also { it.start() }
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("server", certificate.keyStore.getCertificate(certificate.alias) as X509Certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        context = SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        return port
    }

    private lateinit var context: SSLContext

    private fun connect(port: Int, token: String?, protocol: Boolean = true): Pair<WebSocket, Feed> {
        val feed = Feed()
        val socket = HttpClient.newBuilder().sslContext(context).build().newWebSocketBuilder().apply {
            if (protocol) header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
            if (token != null) header("Authorization", "Bearer $token")
        }.buildAsync(URI("wss://127.0.0.1:$port/api/v1/ws"), feed).get(10, TimeUnit.SECONDS)
        openSockets += socket
        return socket to feed
    }

    private fun handshakeStatus(port: Int, token: String?, protocol: Boolean = true): Int {
        val failure = assertFailsWith<Exception> { connect(port, token, protocol) }
        val cause = (failure as? java.util.concurrent.ExecutionException)?.cause ?: (failure as? CompletionException)?.cause ?: failure
        return (cause as WebSocketHandshakeException).response.statusCode()
    }

    // --- people, through the services (the HTTP side of login is AuthApiTest's) ---

    private class Login(val device: EnrollResponse, val token: String)

    private fun AuthEnv.login(name: String, mode: DeviceMode = DeviceMode.PERSONAL, onto: EnrollResponse? = null): Login {
        if (name !in users.userNames()) addUser(name)
        val auth = services.get(AuthService::class.java)!!
        val redeemed = pairingService().redeem(RedeemRequest(secret = pair(name).secret), "127.0.0.1")
        val hasPin = users.find(name)!!.pin != null
        val device = auth.enroll(
            redeemed.enrollmentToken,
            EnrollRequest(
                "Phone of $name", "android", mode, pin = PIN.takeIf { hasPin }, newPin = PIN.takeUnless { hasPin },
                deviceId = onto?.deviceId, deviceCredential = onto?.credential,
            ),
            "127.0.0.1",
        )
        val token = auth.unlock(UnlockRequest(device.deviceId, name, device.credential, pin = PIN), "127.0.0.1").accessToken
        return Login(device, token)
    }

    private fun AuthEnv.pairingService() = services.get(xyz.felismp.shoparchive.api.PairingService::class.java)!!

    private fun Feed.next(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(assertNotNull(texts.poll(10, TimeUnit.SECONDS), "no message arrived")).jsonObject

    // --- tests ---

    @Test
    fun theHandshakeNeedsAnAccessTokenAndTheProtocolHeader() {
        val env = AuthEnv(root)
        val port = start(env)
        val login = env.login("mali")
        val enrollmentToken = env.pairingService().redeem(RedeemRequest(secret = env.pair("mali").secret), "127.0.0.1").enrollmentToken

        assertEquals(401, handshakeStatus(port, null))
        assertEquals(401, handshakeStatus(port, "not-a-token"))
        assertEquals(401, handshakeStatus(port, enrollmentToken))
        assertEquals(400, handshakeStatus(port, login.token, protocol = false))
        connect(port, login.token) // the right one gets in
        assertTrue(waitUntil { env.auth.events.openConnections() == 1 })
    }

    @Test
    fun theConsoleSayReachesEveryOpenSocketAsJson() {
        val env = AuthEnv(root)
        val port = start(env)
        val (_, mali) = connect(port, env.login("mali").token)
        val (_, noy) = connect(port, env.login("noy").token)

        assertTrue(waitUntil { env.auth.events.openConnections() == 2 })

        assertEquals(listOf("Sent to 2 connected apps"), env.console("say shop closes at 6 pm"))

        for (feed in listOf(mali, noy)) {
            val message = feed.next()
            assertEquals("say", message["type"]!!.jsonPrimitive.content)
            assertEquals("shop closes at 6 pm", message["message"]!!.jsonPrimitive.content)
            assertEquals("console", message["from"]!!.jsonPrimitive.content)
        }
        assertEquals(listOf("Pending pairings: 0", "WebSocket connections: 2"), env.auth.statusLines())
    }

    @Test
    fun pairingAnotherDeviceTellsTheOtherOpenSocketsOfThatUserOnly() {
        val env = AuthEnv(root)
        val port = start(env)
        val first = env.login("mali")
        val (_, malisOpen) = connect(port, first.token)
        val (_, noysOpen) = connect(port, env.login("noy").token)
        assertTrue(waitUntil { env.auth.events.openConnections() == 2 })

        val second = env.login("mali")

        val message = malisOpen.next()
        assertEquals("device.paired", message["type"]!!.jsonPrimitive.content)
        assertEquals(second.device.deviceId, message["deviceId"]!!.jsonPrimitive.content)
        assertEquals("Phone of mali", message["label"]!!.jsonPrimitive.content)
        assertNull(noysOpen.texts.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun enrollingAnotherUserOnASharedDeviceTellsNoOneElse() {
        val env = AuthEnv(root)
        val port = start(env)
        val shared = env.login("mali", DeviceMode.SHARED)
        val (_, onShared) = connect(port, shared.token)
        assertTrue(waitUntil { env.auth.events.openConnections() == 1 })

        env.login("kham", DeviceMode.PERSONAL, onto = shared.device) // a second user on the same shared device

        assertNull(onShared.texts.poll(500, TimeUnit.MILLISECONDS), "kham is another user; mali's socket hears nothing")
    }

    @Test
    fun theSocketIsClosedWhenItsUserIsDisabled() {
        val env = AuthEnv(root)
        val port = start(env)
        val (_, feed) = connect(port, env.login("mali").token)

        env.users.setEnabled("mali", false)

        assertEquals(1008, feed.closes.poll(10, TimeUnit.SECONDS))
        assertTrue(waitUntil { env.auth.events.openConnections() == 0 })
    }

    @Test
    fun theSocketIsClosedWhenItsTokenExpires() {
        val env = AuthEnv(root, "config-version: 1\nauth:\n  session:\n    access-token-minutes: 1\n")
        val port = start(env)
        val (_, feed) = connect(port, env.login("mali").token)

        env.clock.advance(Duration.ofMinutes(2))

        assertEquals(1008, feed.closes.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun theSocketIsClosedWhenItsUserIsRemovedFromTheDevice() {
        val env = AuthEnv(root, "$NO_BACKOFF  pin:\n    max-failures: 3\n")
        val port = start(env)
        val login = env.login("mali")
        val (_, feed) = connect(port, login.token)
        val auth = env.services.get(AuthService::class.java)!!

        repeat(3) { runCatching { auth.unlock(UnlockRequest(login.device.deviceId, "mali", login.device.credential, pin = "000000"), "127.0.0.1") } }

        assertEquals(1008, feed.closes.poll(10, TimeUnit.SECONDS))
    }

    @Test
    fun theSocketIsClosedAtOnceWhenTheUserIsDisabledGivenAnotherRoleOrReset() {
        val env = AuthEnv(root)
        val port = start(env)
        env.console("role create cashier")

        for (action in listOf("user disable mali", "user role mali cashier", "user reset mali")) {
            env.console("user enable mali")
            val login = env.login("mali")
            val (_, feed) = connect(port, login.token)
            assertTrue(waitUntil { env.auth.events.openConnections() == 1 }, action)

            env.console(action)

            assertEquals(1008, feed.closes.poll(10, TimeUnit.SECONDS), action)
            assertTrue(waitUntil { env.auth.events.openConnections() == 0 }, action)
        }
    }

    @Test
    fun aUserBlockDeletedFromADeviceFileByHandEndsTheSessionAndIsNotRestoredByAnotherUsersUnlock() {
        val env = AuthEnv(root)
        val port = start(env)
        val mali = env.login("mali", DeviceMode.SHARED)
        val kham = env.login("kham", DeviceMode.PERSONAL, onto = mali.device)
        val (_, feed) = connect(port, mali.token)
        assertTrue(waitUntil { env.auth.events.openConnections() == 1 })
        val auth = env.services.get(AuthService::class.java)!!
        fun unlock(login: Login, name: String) =
            auth.unlock(UnlockRequest(login.device.deviceId, name, login.device.credential, pin = PIN), "127.0.0.1")

        // The admin does what the file's own header says: deletes mali's block, while the server runs.
        val file = root.resolve("data/devices/${mali.device.deviceId}.yml")
        removeUserBlock(file, "mali")

        assertEquals(401, assertFailsWith<ApiError> { unlock(mali, "mali") }.status)
        assertNull(auth.authenticate(mali.token))
        assertEquals(1008, feed.closes.poll(10, TimeUnit.SECONDS))
        // Another user's good unlock writes the device file; it must not bring mali's block back.
        unlock(kham, "kham")
        assertFalse("  mali:" in Files.readString(file), Files.readString(file))
        assertEquals(401, assertFailsWith<ApiError> { unlock(mali, "mali") }.status)
        assertNotNull(auth.authenticate(unlock(kham, "kham").accessToken))
    }

    @Test
    fun theServerPingsOftenEnoughToKeepAQuietSocketAliveBeyondTheIdleTimeout() {
        // request-timeout-seconds 5: a connection silent for 5 s is dropped, so pings come every 2 s.
        val env = AuthEnv(root)
        val port = start(env, timeoutSeconds = 5)
        val (socket, feed) = connect(port, env.login("mali").token)

        Thread.sleep(7_000)

        assertTrue(feed.pings.get() >= 2, "pings: ${feed.pings.get()}")
        assertTrue(!socket.isInputClosed && !socket.isOutputClosed && feed.closes.isEmpty())
    }

    @Test
    fun thePingIsEvery15SecondsAndNeverSlowerThanHalfTheTimeout() {
        assertEquals(15, WS_PING_SECONDS)
        assertEquals(15, wsPingSeconds(30))
        assertEquals(15, wsPingSeconds(300))
        assertEquals(15, wsPingSeconds(16))
        assertEquals(7, wsPingSeconds(15))
        assertEquals(5, wsPingSeconds(10))
        assertEquals(2, wsPingSeconds(5))
        assertEquals(1, wsPingSeconds(1))
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }
}
