package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.UnlockRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The real Ktor/OkHttp socket against a pinned TLS server. */
class WebSocketTest {
    private val unlockReq = UnlockRequest("dev", "alice", "cred", pin = "1234")
    private val unlockOk = """{"accessToken":"TOKEN-1","expiresInSeconds":900}"""

    @Test fun sessionSendsTokenAndHeaderAndDeliversTextFrames() = runBlocking {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"say","message":"hi","from":"bob"}""")
                webSocket.close(1000, "bye")
            }
        }
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else MockResponse.Builder().webSocketUpgrade(listener).build() }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            val text = CompletableDeferred<String>()
            var opened = false
            withTimeout(10_000) { api.webSocketSession({ opened = true }, { text.complete(it) }) }
            assertEquals(true, opened)
            assertEquals("""{"type":"say","message":"hi","from":"bob"}""", text.await())
            val upgrade = s.requests.last()
            assertEquals("/api/v1/ws", upgrade.target)
            assertEquals("Bearer TOKEN-1", upgrade.headers["Authorization"])
            assertEquals("$PROTOCOL_VERSION", upgrade.headers["X-ShopArchive-Protocol"])
            api.close()
        }
    }

    @Test fun rejectedUpgradeWith401ClearsTheTokenAndIsLocked() = runBlocking {
        TlsServer { r -> if (r.target == "/api/v1/unlock") json(unlockOk) else json("""{"code":"UNAUTHORIZED","message":"x","protocol":1}""", 401) }.use { s ->
            val api = ApiClient("sid", s.pin, listOf(s.endpoint))
            api.unlock(unlockReq)
            withTimeout(10_000) { assertFailsWith<ClientError.Locked> { api.webSocketSession({}, {}) } }
            assertFalse(api.isUnlocked)
            api.close()
        }
    }
}
