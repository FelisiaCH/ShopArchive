package xyz.felismp.shoparchive.server.notify

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.NotificationService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.net.PrincipalKey
import xyz.felismp.shoparchive.server.net.blocking
import xyz.felismp.shoparchive.server.net.require
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.NotificationState

/** The default number of messages `GET /api/v1/notifications` answers with when `limit` is not given. */
private const val DEFAULT_LIST_LIMIT = 50

private val ApplicationCall.principal: Principal get() = attributes[PrincipalKey]

private fun invalid(message: String) = ApiError(400, ErrorCode.INVALID_REQUEST, message)

/** The outbox for the Reports and Admin screens. The routes only pass on; the [NotificationService] decides who may do what. */
internal fun Route.notifyRoutes(services: ServiceRegistry) {
    get("/notifications") {
        val state = call.request.queryParameters["state"]?.takeIf { it.isNotEmpty() }?.let { word ->
            NotificationState.entries.firstOrNull { it.name.lowercase() == word } ?: throw invalid("state is queued, sent, unknown or failed.")
        }
        val limit = call.request.queryParameters["limit"]?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw invalid("limit is a whole number.") } ?: DEFAULT_LIST_LIMIT
        call.respond(blocking { services.require<NotificationService>().list(call.principal, state, limit) })
    }

    post("/notifications/{id}/resend") {
        val id = call.parameters["id"].orEmpty()
        call.respond(HttpStatusCode.Created, blocking { services.require<NotificationService>().resend(call.principal, id) })
    }

    // Send the summary of a closed day again. The same shape as the other session routes.
    post("/sessions/{branch}/{id}/notify") {
        val branch = call.parameters["branch"].orEmpty()
        val id = call.parameters["id"].orEmpty()
        call.respond(HttpStatusCode.Created, blocking { services.require<NotificationService>().notifyDay(call.principal, branch, id) })
    }
}
