package xyz.felismp.shoparchive.server.net

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.BranchService
import xyz.felismp.shoparchive.api.CategoryService
import xyz.felismp.shoparchive.api.DashboardService
import xyz.felismp.shoparchive.api.EntryQuery
import xyz.felismp.shoparchive.api.EntryService
import xyz.felismp.shoparchive.api.ExportService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.SessionService
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.CreateBranchRequest
import xyz.felismp.shoparchive.shared.CreateCategoryRequest
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.MoveEntryRequest
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.UpdateBranchRequest
import xyz.felismp.shoparchive.shared.UpdateCategoryRequest
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import xyz.felismp.shoparchive.shared.wire

private val requestJson = Json { ignoreUnknownKeys = true }

/** The JSON of an entry and the slips sent with it, in the order of their numbers. */
private class Upload(val json: String, val slips: List<ByteArray>)

private fun invalid(message: String) = ApiError(400, ErrorCode.INVALID_REQUEST, message)

/**
 * The body of a request that makes or changes an entry: multipart/form-data with the part `entry` (JSON) and the parts `slip-1`, `slip-2`, ...
 * (the images), or, when there are no slips, plain JSON. The size of the whole request is limited before it gets here.
 */
private suspend fun ApplicationCall.receiveUpload(): Upload {
    if (!request.contentType().match(ContentType.MultiPart.FormData)) return Upload(receive<String>(), emptyList())
    var json: String? = null
    val slips = sortedMapOf<Int, ByteArray>()
    receiveMultipart().forEachPart { part ->
        try {
            val name = part.name.orEmpty()
            val bytes = when (part) {
                is PartData.FormItem -> part.value.toByteArray(Charsets.UTF_8)
                is PartData.FileItem -> part.provider().readBuffer().readByteArray()
                else -> throw invalid("The part '$name' is not a form field or a file.")
            }
            val number = Regex("slip-([1-9][0-9]{0,2})").matchEntire(name)?.groupValues?.get(1)?.toInt()
            when {
                name == "entry" && json == null -> json = String(bytes, Charsets.UTF_8)
                number != null && number !in slips -> slips[number] = bytes
                else -> throw invalid("The part '$name' is not expected: send one 'entry' and the slips as 'slip-1', 'slip-2', ...")
            }
        } finally {
            part.release()
        }
    }
    return Upload(json ?: throw invalid("The part 'entry' is missing."), slips.values.toList())
}

private inline fun <reified T> Upload.decode(): T = try {
    requestJson.decodeFromString(json)
} catch (e: kotlinx.serialization.SerializationException) {
    throw invalid("The entry is not the JSON this endpoint takes.")
}

private val ApplicationCall.principal: Principal get() = attributes[PrincipalKey]

private fun ApplicationCall.param(name: String): String = parameters[name].orEmpty()

private fun ApplicationCall.query(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotEmpty() }

/** Branches, categories, day sessions and entries. Every route needs a signed-in user; what the user may do is the service's to say. */
internal fun Route.recordRoutes(services: ServiceRegistry) {
    get("/branches") { call.respond(blocking { services.require<BranchService>().list(call.principal) }) }

    post("/branches") {
        val body = call.receive<CreateBranchRequest>()
        call.respond(HttpStatusCode.Created, blocking { services.require<BranchService>().create(call.principal, body) })
    }

    put("/branches/{key}") {
        val body = call.receive<UpdateBranchRequest>()
        call.respond(blocking { services.require<BranchService>().update(call.principal, call.param("key"), body) })
    }

    get("/categories") { call.respond(blocking { services.require<CategoryService>().list(call.principal) }) }

    post("/categories") {
        val body = call.receive<CreateCategoryRequest>()
        call.respond(HttpStatusCode.Created, blocking { services.require<CategoryService>().create(call.principal, body) })
    }

    put("/categories/{key}") {
        val body = call.receive<UpdateCategoryRequest>()
        call.respond(blocking { services.require<CategoryService>().update(call.principal, call.param("key"), body) })
    }

    // 201 when this call opened the day; 200 with `alreadyOpenBy` when it was open already.
    post("/sessions/{branch}/open") {
        val body = call.receive<OpenSessionRequest>()
        val opened = blocking { services.require<SessionService>().open(call.principal, call.param("branch"), body) }
        call.respond(if (opened.alreadyOpenBy == null) HttpStatusCode.Created else HttpStatusCode.OK, opened)
    }

    get("/sessions/{branch}/current") { call.respond(blocking { services.require<SessionService>().current(call.principal, call.param("branch")) }) }

    post("/sessions/{branch}/{id}/close") {
        val body = call.receive<CloseSessionRequest>()
        call.respond(blocking { services.require<SessionService>().close(call.principal, call.param("branch"), call.param("id"), body) })
    }

    get("/sessions/{branch}/{id}/preview") {
        call.respond(blocking { services.require<SessionService>().preview(call.principal, call.param("branch"), call.param("id")) })
    }

    get("/sessions/{branch}") {
        call.respond(blocking { services.require<SessionService>().list(call.principal, call.param("branch"), call.query("from"), call.query("to")) })
    }

    post("/entries") {
        val upload = call.receiveUpload()
        val created = blocking { services.require<EntryService>().create(call.principal, upload.decode<CreateEntryRequest>(), upload.slips) }
        call.respond(if (created.created) HttpStatusCode.Created else HttpStatusCode.OK, created.entry)
    }

    get("/entries") {
        val type = call.query("type")?.let { word -> EntryType.entries.firstOrNull { it.wire() == word } ?: throw invalid("type is income or expense.") }
        val query = EntryQuery(
            call.query("from"), call.query("to"), call.query("branch"), type, call.query("currency"), call.query("category"),
            includeDeleted = call.query("includeDeleted") == "true",
        )
        call.respond(blocking { services.require<EntryService>().list(call.principal, query) })
    }

    get("/entries/{date}/{id}") { call.respond(blocking { services.require<EntryService>().get(call.principal, call.param("date"), call.param("id")) }) }

    put("/entries/{date}/{id}") {
        val upload = call.receiveUpload()
        call.respond(blocking { services.require<EntryService>().update(call.principal, call.param("date"), call.param("id"), upload.decode<UpdateEntryRequest>(), upload.slips) })
    }

    delete("/entries/{date}/{id}") { call.respond(blocking { services.require<EntryService>().delete(call.principal, call.param("date"), call.param("id")) }) }

    post("/entries/{date}/{id}/move") {
        val body = call.receive<MoveEntryRequest>()
        call.respond(blocking { services.require<EntryService>().move(call.principal, call.param("date"), call.param("id"), body) })
    }

    get("/entries/{date}/{id}/slips/{n}") {
        val number = call.param("n").toIntOrNull() ?: throw ApiError(404, ErrorCode.NOT_FOUND, "No such slip.")
        val slip = blocking { services.require<EntryService>().slip(call.principal, call.param("date"), call.param("id"), number) }
        call.respondBytes(slip.bytes, ContentType.parse(slip.contentType))
    }

    get("/entries/{date}/{id}/history") { call.respond(blocking { services.require<EntryService>().history(call.principal, call.param("date"), call.param("id")) }) }

    get("/export") {
        val query = EntryQuery(call.query("from"), call.query("to"), call.query("branch"), null, null, null, includeDeleted = call.query("includeDeleted") == "true")
        val file = blocking { services.require<ExportService>().export(call.principal, query, call.query("format") ?: "csv") }
        call.response.headers.append(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.fileName).toString())
        call.respondBytes(file.bytes, ContentType.parse(file.contentType))
    }

    get("/dashboard") {
        call.respond(blocking { services.require<DashboardService>().dashboard(call.principal, call.query("from"), call.query("to"), call.query("branch"), call.query("currency")) })
    }

    get("/items/recent") { call.respond(blocking { services.require<DashboardService>().recentItems(call.principal, call.query("category")) }) }
}
