package xyz.felismp.shoparchive.server.net

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebTest {
    private var banned = false
    private val services = Services().apply {
        register(InfoService::class.java, object : InfoService {
            override fun info() = InfoResponse("11111111-2222-3333-4444-555555555555", "Test Shop", "hello", "9.9", PROTOCOL_VERSION)
        }, 0, "test")
        register(IpBanService::class.java, object : IpBanService { override fun isBanned(ip: String) = banned }, 0, "test")
    }

    private fun web(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { apiModule(services, 1024) }
        block()
    }

    private fun HttpResponse.assertSecurityHeaders() {
        assertEquals(WEB_CSP, headers["Content-Security-Policy"])
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("no-referrer", headers["Referrer-Policy"])
        assertEquals("DENY", headers["X-Frame-Options"])
    }

    @Test
    fun theRootAndIndexServeThePlaceholderWithoutTheProtocolHeader() = web {
        for (path in listOf("/", "/index.html")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
            assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters(), path)
            assertTrue("not finished" in response.bodyAsText(), path)
            assertEquals("no-cache", response.headers["Cache-Control"], path)
            response.assertSecurityHeaders()
        }
    }

    @Test
    fun aMissingFileOutsideTheApiIs404PlainText() = web {
        val response = client.get("/nope")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
        response.assertSecurityHeaders()
    }

    @Test
    fun aMissingPathUnderTheApiStaysTheJsonEnvelopeWithNoStoreAndNoWebHeaders() = web {
        val response = client.get("/api/v1/nope") { header(PROTOCOL_HEADER, "$PROTOCOL_VERSION") }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCode.NOT_FOUND, Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).code)
        assertEquals("no-store", response.headers["Cache-Control"])
        assertNull(response.headers["Content-Security-Policy"])
    }

    @Test
    fun everyApiAnswerIsNoStoreEvenErrors() = web {
        assertEquals("no-store", client.get("/api/v1/info").headers["Cache-Control"])
        assertEquals("no-store", client.get("/api/v1/config").headers["Cache-Control"]) // protocol mismatch
        assertEquals("no-store", client.get("/api/v1/config") { header(PROTOCOL_HEADER, "$PROTOCOL_VERSION") }.headers["Cache-Control"]) // 401
        assertEquals("no-store", client.post("/api/v1/login") { header(PROTOCOL_HEADER, "$PROTOCOL_VERSION") }.headers["Cache-Control"])
    }

    @Test
    fun theWebClientNeedsNoProtocolHeaderButABannedAddressIsStillRefused() = web {
        assertEquals(HttpStatusCode.OK, client.get("/").status)
        banned = true

        val response = client.get("/")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCode.BANNED, Json.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).code)
    }

    @Test
    fun strictTransportSecurityIsNeverSent() = web {
        for (path in listOf("/", "/nope", "/api/v1/info", "/api/v1/nope")) {
            assertNull(client.get(path).headers["Strict-Transport-Security"], path)
        }
    }

    @Test
    fun theCspKeepsEverythingOnTheServersOwnOrigin() {
        assertTrue("default-src 'self'" in WEB_CSP)
        assertTrue("frame-ancestors 'none'" in WEB_CSP)
        assertFalse("unsafe" in WEB_CSP)
    }

    @Test
    fun onlyFilesWithAContentHashAreCachedForAYear() {
        val immutable = "public, max-age=31536000, immutable"
        assertEquals(immutable, staticCacheControl("/assets/app.3f9a1c7e.js"))
        assertEquals(immutable, staticCacheControl("/main.AbC-_123xyz.css"))
        for (path in listOf("/", "/index.html", "/app.js", "/app.component.js", "/app.1234567.js", "/3f9a1c7e.js", "/assets/3f9a1c7e/app.js", "/logo.png")) {
            assertEquals("no-cache", staticCacheControl(path), path)
        }
    }

    private class ProtocolCase(val header: String?, val versions: List<Int>, val token: String?)

    @Test
    fun theProtocolsOfABrowserAreParsedFromTheCommaSeparatedEntries() {
        val v = PROTOCOL_VERSION
        for (case in listOf(
            ProtocolCase("shoparchive.v$v, shoparchive.bearer.tok123", listOf(v), "tok123"),
            ProtocolCase("shoparchive.bearer.tok123,shoparchive.v$v", listOf(v), "tok123"),
            ProtocolCase("  shoparchive.v$v  ,   shoparchive.bearer.tok  ", listOf(v), "tok"),
            ProtocolCase(null, emptyList(), null),
            ProtocolCase("", emptyList(), null),
            ProtocolCase("shoparchive.v$v", listOf(v), null),
            ProtocolCase("chat, shoparchive.v$v, other, shoparchive.bearer.t", listOf(v), "t"),
            ProtocolCase("shoparchive.v${v + 1}, shoparchive.bearer.t", listOf(v + 1), "t"),
            ProtocolCase("shoparchive.vx, shoparchive.v", emptyList(), null),
            ProtocolCase("shoparchive.v$v, shoparchive.bearer.", listOf(v), null),
            ProtocolCase("shoparchive.bearer.a, shoparchive.bearer.b", emptyList(), "a"),
        )) {
            val parsed = parseWebSocketProtocols(case.header)
            assertEquals(case.versions, parsed.versions, case.header)
            assertEquals(case.token, parsed.token, case.header)
        }
    }

    @Test
    fun anOriginMustBeTheHostTheRequestWasSentTo() {
        for ((origin, host, expected) in listOf(
            Triple("https://shop.lan:25655", "shop.lan:25655", true),
            Triple("https://SHOP.lan:25655", "shop.LAN:25655", true),
            Triple("https://shop.lan", "shop.lan", true),
            Triple("https://192.168.1.5:25655", "192.168.1.5:25655", true),
            Triple("https://[::1]:25655", "[::1]:25655", true),
            Triple("https://shop.lan:25655", "shop.lan:25656", false),
            Triple("https://shop.lan", "shop.lan:25655", false),
            Triple("https://evil.example:25655", "shop.lan:25655", false),
            Triple("https://shop.lan:25655", null, false),
            Triple("null", "shop.lan:25655", false),
            Triple("", "shop.lan:25655", false),
            Triple("not a url", "shop.lan:25655", false),
            Triple("https://", "shop.lan:25655", false),
        )) {
            assertEquals(expected, originMatchesHost(origin, host), "$origin vs $host")
        }
    }

    @Test
    fun theConsoleListsOneWebAddressPerLanAddressAndLocalhostOnlyAlone() {
        assertEquals(
            listOf("Web: https://192.168.1.5:25655/", "Web: https://[fd00::5]:25655/"),
            webLines(listOf("192.168.1.5", "fd00::5", "localhost"), 25655),
        )
        assertEquals(listOf("Web: https://localhost:25655/"), webLines(listOf("localhost"), 25655))
        assertEquals(listOf("Web: https://127.0.0.1:25655/"), webLines(listOf("127.0.0.1"), 25655))
    }
}
