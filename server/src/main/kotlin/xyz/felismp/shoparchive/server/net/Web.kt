package xyz.felismp.shoparchive.server.net

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.header
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal const val API_PREFIX = "/api/"
internal const val WS_PATH = "/api/v1/ws"

/** Everything under `/api/` is the API; anything else is the web client's files. */
internal fun isApiPath(path: String) = path.startsWith(API_PREFIX)

/** The policy of the web client's pages. One constant so it is easy to change. */
internal const val WEB_CSP =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

/**
 * A file name with a content hash: a dot-separated segment of 8 or more hex / base64url characters with at least one digit
 * (so `app.3f9a1c7e.js` matches but `app.component.js` does not) before the extension.
 */
private val HASHED_NAME = Regex("""^.+\.(?=[A-Za-z0-9_-]*\d)[A-Za-z0-9_-]{8,}\.[A-Za-z0-9]+$""")

/** `Cache-Control` for a static file: a hashed name never changes, everything else (index.html, `/`) is checked again each time. */
internal fun staticCacheControl(path: String): String =
    if (HASHED_NAME.matches(path.substringAfterLast('/'))) "public, max-age=31536000, immutable" else "no-cache"

/** Security headers on the web client's responses and `no-store` on the API's. Never `Strict-Transport-Security`: the certificate is self-signed. */
internal val securityHeaders = createApplicationPlugin("ShopArchiveSecurityHeaders") {
    onCall { call ->
        val path = call.request.path()
        if (isApiPath(path)) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        } else {
            call.response.header("Content-Security-Policy", WEB_CSP)
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("X-Frame-Options", "DENY")
            call.response.header(HttpHeaders.CacheControl, staticCacheControl(path))
        }
    }
}

private const val WS_VERSION_PREFIX = "shoparchive.v"
private const val WS_BEARER_PREFIX = "shoparchive.bearer."

/** What a browser can say in `Sec-WebSocket-Protocol`: the protocol versions it offers and the access token, if any. */
internal class WebSocketProtocols(val versions: List<Int>, val token: String?)

/** Parses the comma separated entries `shoparchive.v<N>` and `shoparchive.bearer.<token>`; anything else is ignored. */
internal fun parseWebSocketProtocols(header: String?): WebSocketProtocols {
    val entries = header.orEmpty().split(',').map { it.trim() }
    val versions = entries.filter { it.startsWith(WS_VERSION_PREFIX) }.mapNotNull { it.removePrefix(WS_VERSION_PREFIX).toIntOrNull() }
    val token = entries.firstOrNull { it.startsWith(WS_BEARER_PREFIX) }?.removePrefix(WS_BEARER_PREFIX)?.takeIf { it.isNotEmpty() }
    return WebSocketProtocols(versions, token)
}

internal fun ApplicationCall.webSocketProtocols() =
    parseWebSocketProtocols(request.headers.getAll(HttpHeaders.SecWebSocketProtocol)?.joinToString(","))

/** True when the `Origin` page is served from the same host[:port] the request was sent to; a malformed Origin (or `null`) never matches. */
internal fun originMatchesHost(origin: String, host: String?): Boolean {
    if (host == null) return false
    val uri = try { URI(origin.trim()) } catch (e: URISyntaxException) { return false }
    val originHost = uri.host ?: return false
    val authority = if (uri.port == -1) originHost else "$originHost:${uri.port}"
    return authority.lowercase(Locale.ROOT) == host.trim().lowercase(Locale.ROOT)
}

/** `[::1]`-style brackets for an IPv6 host in a URL. */
internal fun urlHost(host: String) = if (host.contains(':')) "[$host]" else host

/** One `Web: https://host:port/` line per address; `localhost` only when it is all there is. */
internal fun webLines(hosts: List<String>, port: Int): List<String> =
    hosts.filter { it != "localhost" }.ifEmpty { hosts }.map { "Web: https://${urlHost(it)}:$port/" }
