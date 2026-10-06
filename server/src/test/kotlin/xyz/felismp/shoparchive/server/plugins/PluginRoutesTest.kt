package xyz.felismp.shoparchive.server.plugins

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.plugin.PluginResponse
import xyz.felismp.shoparchive.api.plugin.PluginRouteService
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.PluginSettings
import xyz.felismp.shoparchive.server.net.apiModule
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plugin routes end to end: the real API module, the real route service and plugins loaded from jars; only the sign-in is a stand-in. */
class PluginRoutesTest {
    @TempDir
    lateinit var root: Path

    private val plugins get() = root.resolve("plugins")
    private val alice = Principal("u-alice", "alice", "dev-1", "session-1")

    /** Accepts the token `good` (as alice) and gives the permission `web.admin` only to her. */
    private val auth = Proxy.newProxyInstance(AuthService::class.java.classLoader, arrayOf(AuthService::class.java)) { _, method, args ->
        when (method.name) {
            "authenticate" -> alice.takeIf { args[0] == "good" }
            "hasPermission" -> args[1] == "web.admin"
            else -> error("${method.name} is not used by plugin routes")
        }
    } as AuthService

    private val services = Services().apply {
        register(IpBanService::class.java, object : IpBanService { override fun isBanned(ip: String) = false }, 0, "test")
        register(AuthService::class.java, auth, 0, "test")
    }
    private lateinit var manager: PluginManager

    @BeforeTest
    fun setUp() {
        manager = PluginManager(root, PluginSettings(requireApproval = false, disableTimeoutMs = 2_000), services, Commands(), Permissions())
        services.register(PluginRouteService::class.java, DefaultPluginRouteService(manager.routes, services), 0, "core")
    }

    private fun jar(name: String, pkg: String, main: String) = buildPluginJar(plugins.resolve("$name.jar"), pkg, pluginYml(name, main))

    private fun start() = manager.apply { loadAll(); enableAll() }

    private fun api(maxBodyBytes: Long = 1024, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { apiModule(services, maxBodyBytes) }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String? = "good") = client.get("/api/v1/x/$path") {
        header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun HttpResponse.error() = Json.decodeFromString(ErrorResponse.serializer(), bodyAsText())

    @Test
    fun aSignedInUserReachesAPluginRouteAndTheHandlerKnowsWhoCalled() = api {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()

        val response = get("Web/hello")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers[HttpHeaders.ContentType]!!, "application/json")
        assertEquals("""{"hello":"alice","user":"u-alice"}""", response.bodyAsText())
    }

    @Test
    fun withoutAValidTokenTheRouteAnswers401AndTheHandlerDoesNotRun() = api {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()

        for (token in listOf(null, "bad")) {
            val response = get("Web/hello", token)
            assertEquals(HttpStatusCode.Unauthorized, response.status, "token $token")
            assertEquals(ErrorCode.UNAUTHORIZED, response.error().code)
        }
    }

    @Test
    fun pathParametersQueryAndTheRootPathAreGivenToTheHandler() = api {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()

        assertEquals("""{"id":"a b","path":"/items/a b","q":"1+2"}""", get("Web/items/a%20b?q=1&q=2").bodyAsText())
        assertEquals("""{"root":true}""", get("Web").bodyAsText(), "without a slash")
        assertEquals("""{"root":true}""", get("Web/").bodyAsText(), "with a slash")
    }

    @Test
    fun bodyMethodStatusAndContentTypeComeFromTheHandler() = api {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()
        val headers: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
            header(HttpHeaders.Authorization, "Bearer good")
        }

        val post = client.post("/api/v1/x/Web/echo") { headers(); setBody("héllo") }
        val put = client.put("/api/v1/x/Web/echo") { headers(); setBody("again") }
        val delete = client.delete("/api/v1/x/Web/echo") { headers() }

        assertEquals(HttpStatusCode.Created, post.status)
        assertEquals("POST:héllo", post.bodyAsText())
        assertContains(post.headers[HttpHeaders.ContentType]!!, "text/plain")
        assertEquals("PUT:again", put.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, delete.status)
        assertEquals(HttpStatusCode.fromValue(418), get("Web/teapot").status)
    }

    @Test
    fun theHandlerCanAskWhetherTheCallerHoldsAPermission() = api {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()
        assertEquals("""{"admin":true}""", get("Web/perm").bodyAsText())
    }

    @Test
    fun aBodyOverTheNetworkBodyLimitGets413() = api(maxBodyBytes = 16) {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()

        val response = client.post("/api/v1/x/Web/echo") {
            header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
            header(HttpHeaders.Authorization, "Bearer good")
            setBody("x".repeat(17))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, response.error().code)
    }

    @Test
    fun anUnknownPluginPathOrMethodIsNotFoundInTheErrorEnvelope() = api {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()

        for (path in listOf("Nope/hello", "Web/nothing", "Web/hello/extra", "Web/echo")) {
            val response = get(path)
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertEquals(ErrorCode.NOT_FOUND, response.error().code, path)
        }
    }

    @Test
    fun aHandlerThatThrowsGets500WithoutItsDetailsAndTheServerKeepsServing() = api {
        jar("Web", "web", "testplugins.web.WebPlugin"); start()

        val response = get("Web/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val error = response.error()
        assertEquals(ErrorCode.INTERNAL, error.code)
        assertContains(error.message, "Web")
        assertFalse("secret internal detail" in error.message)
        assertEquals(HttpStatusCode.OK, get("Web/hello").status)
    }

    @Test
    fun aPluginThatIsStoppedOrFailedInOnEnableServesNothing() = api {
        jar("Web", "web", "testplugins.web.WebPlugin")
        jar("WebFail", "webfail", "testplugins.webfail.WebFailPlugin")
        start()

        assertEquals(PluginState.FAILED, manager.entries.first { it.displayName == "WebFail" }.state)
        assertEquals(HttpStatusCode.NotFound, get("WebFail/hello").status)
        assertEquals(HttpStatusCode.OK, get("Web/hello").status)

        manager.disableAll()

        assertEquals(HttpStatusCode.NotFound, get("Web/hello").status)
    }

    @Test
    fun registeringARouteOutsideOnEnableFailsThePluginClearly() {
        jar("WebLoad", "webload", "testplugins.webload.WebLoadPlugin")
        start()

        val entry = manager.entries.single()
        assertEquals(PluginState.FAILED, entry.state)
        assertContains(entry.detail, "routes can only be registered in onEnable")
    }

    @Test
    fun badRoutePathsAndDuplicatesAreRefused() {
        jar("Web", "web", "testplugins.web.WebPlugin")
        manager.loadAll()
        val routes = manager.entries.single().context!!.routes.also { manager.entries.single().context!!.routesOpen = true }
        val ok = { _: xyz.felismp.shoparchive.api.plugin.PluginRequest -> PluginResponse.ok() }

        routes.get("/a/{id}", ok)
        assertContains(assertFailsWith<IllegalArgumentException> { routes.get("/a/{other}", ok) }.message!!, "twice")
        routes.post("/a/{id}", ok) // another method is another route
        for (bad in listOf("a", "/a//b", "/a/b c", "/{x}/{x}", "/a/{1x}", "/a/*")) {
            assertFailsWith<IllegalArgumentException>(bad) { routes.get(bad, ok) }
        }
        assertNull(manager.routes.find("Web", "GET", listOf("zzz")))
    }

    @Test
    fun pluginsInfoListsTheRoutesWithTheirFullPath() {
        jar("Web", "web", "testplugins.web.WebPlugin")
        start()

        val text = manager.info("Web").joinToString("\n")

        assertContains(text, "GET /api/v1/x/Web/hello")
        assertContains(text, "GET /api/v1/x/Web/items/{id}")
        assertContains(text, "POST /api/v1/x/Web/echo")
        assertTrue(Regex("GET /api/v1/x/Web(,|\\n|$)").containsMatchIn(text), "the root route has no trailing slash: $text")
    }
}
