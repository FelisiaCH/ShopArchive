package xyz.felismp.shoparchive.server.net

import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import xyz.felismp.shoparchive.api.CommandService
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.shared.CommandRequest
import xyz.felismp.shoparchive.shared.CompleteRequest
import xyz.felismp.shoparchive.shared.CompleteResponse

/** The app's console. The routes only pass on; the [CommandService] decides who may run what. */
internal fun Route.commandRoutes(services: ServiceRegistry) {
    post("/command") {
        val body = call.receive<CommandRequest>()
        call.respond(blocking { services.require<CommandService>().run(call.attributes[PrincipalKey], body.line, call.ip) })
    }

    post("/command/complete") {
        val body = call.receive<CompleteRequest>()
        call.respond(CompleteResponse(blocking { services.require<CommandService>().complete(call.attributes[PrincipalKey], body.line) }))
    }
}
