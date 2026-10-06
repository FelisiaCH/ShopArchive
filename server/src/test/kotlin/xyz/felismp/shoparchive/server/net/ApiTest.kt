package xyz.felismp.shoparchive.server.net

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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
import kotlin.test.assertTrue

class ApiTest {
    private val info = InfoResponse("11111111-2222-3333-4444-555555555555", "Test Shop", "hello", "9.9", PROTOCOL_VERSION)
    private val banned = mutableSetOf<String>()
    private val seenAddresses = mutableListOf<String>()

    private val services = Services().apply {
        register(InfoService::class.java, object : InfoService { override fun info() = info }, 0, "test")
        register(IpBanService::class.java, object : IpBanService {
            override fun isBanned(ip: String): Boolean {
                seenAddresses += ip
                return ip in banned
            }
        }, 0, "test")
    }

    /** The real module, plus a route that reads its body so the size limit can be exercised. */
    private fun api(maxBodyBytes: Long = 1024, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            apiModule(services, maxBodyBytes)
            routing { post("/api/v1/echo") { call.respondText(call.receiveText()) } }
        }
        block()
    }

    private suspend fun HttpResponse.error() = Json.decodeFromString(ErrorResponse.serializer(), bodyAsText())

    @Test
    fun infoWorksWithoutTheProtocolHeader() = api {
        val response = client.get("/api/v1/info")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(info, Json.decodeFromString(InfoResponse.serializer(), response.bodyAsText()))
    }

    @Test
    fun otherPathsWithoutTheProtocolHeaderGetProtocolMismatchWithTheServersVersion() = api {
        val response = client.get("/api/v1/anything")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.error()
        assertEquals(ErrorCode.PROTOCOL_MISMATCH, error.code)
        assertEquals(PROTOCOL_VERSION, error.protocol)
    }

    @Test
    fun aWrongOrUnreadableProtocolVersionIsAMismatchToo() = api {
        for (value in listOf("${PROTOCOL_VERSION + 1}", "${PROTOCOL_VERSION - 1}", "abc", "")) {
            val response = client.get("/api/v1/anything") { header(PROTOCOL_HEADER, value) }
            assertEquals(HttpStatusCode.BadRequest, response.status, value)
            assertEquals(ErrorCode.PROTOCOL_MISMATCH, response.error().code, value)
        }
    }

    @Test
    fun withTheRightHeaderAnUnknownPathIsNotFoundInTheErrorEnvelope() = api {
        val response = client.get("/api/v1/nothing") { header(PROTOCOL_HEADER, "$PROTOCOL_VERSION") }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCode.NOT_FOUND, response.error().code)
    }

    @Test
    fun onlyGetInfoIsExemptFromTheHeader() = api {
        val response = client.post("/api/v1/info")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCode.PROTOCOL_MISMATCH, response.error().code)
    }

    @Test
    fun aBodyOverTheLimitGets413() = api(maxBodyBytes = 1024) {
        val ok = client.post("/api/v1/echo") {
            header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
            setBody("x".repeat(1024))
        }
        val tooBig = client.post("/api/v1/echo") {
            header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
            setBody("x".repeat(1025))
        }

        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, tooBig.error().code)
    }

    @Test
    fun aBannedAddressGets403OnEveryRouteEvenWithoutTheHeaderAndPardonLiftsIt() = api {
        client.get("/api/v1/info")
        val address = seenAddresses.single()
        banned += address

        for (path in listOf("/api/v1/info", "/api/v1/anything", "/")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
            assertEquals(ErrorCode.BANNED, response.error().code, path)
        }

        banned.clear()
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/info").status)
    }

    @Test
    fun aServiceRegisteredWithAHigherPriorityTakesOver() = api {
        services.register(InfoService::class.java, object : InfoService { override fun info() = info.copy(name = "Plugin Shop") }, 10, "plugin")

        assertTrue("Plugin Shop" in client.get("/api/v1/info").bodyAsText())
    }
}
