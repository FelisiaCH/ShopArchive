package xyz.felismp.shoparchive.spike.app

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout

/** Platform engine + SPKI pinning (OkHttp on Android/desktop, Darwin on iOS). [block] is the shared plugin setup. */
expect fun pinnedHttpClient(pin: String, block: HttpClientConfig<*>.() -> Unit): HttpClient

private fun client(pin: String) = pinnedHttpClient(pin) {
    install(WebSockets)
    install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 10_000
    }
}

private fun httpsBase(url: String): String {
    val u = url.trim().let { if ("://" in it) it else "https://$it" }.trimEnd('/')
    require(u.startsWith("https://")) { "base URL must be https://" }
    return u
}

private fun requireValidPin(pin: String) =
    requireNotNull(decodePin(pin)) { "pin must be base64 of a 32-byte SHA-256 (44 characters)" }

/** Returns "<status> <body>"; throws on pin mismatch, TLS or network failure. */
suspend fun httpsPing(baseUrl: String, pin: String): String {
    requireValidPin(pin)
    val c = client(pin)
    try {
        val r = c.get("${httpsBase(baseUrl)}/ping")
        return "${r.status.value} ${r.bodyAsText()}"
    } finally {
        c.close()
    }
}

/** Sends one Lao+Thai text frame over wss:// and checks the echo; throws on any failure. */
suspend fun wsEcho(baseUrl: String, pin: String): String {
    requireValidPin(pin)
    val url = "wss://" + httpsBase(baseUrl).removePrefix("https://") + "/ws"
    val message = "hello ສະບາຍດີ สวัสดี"
    val c = client(pin)
    try {
        withTimeout(10_000) {
            c.webSocket(url) {
                send(Frame.Text(message))
                val reply = incoming.receive()
                check(reply is Frame.Text && reply.readText() == message) { "echo mismatch: $reply" }
            }
        }
        return "echo OK: $message"
    } finally {
        c.close()
    }
}
