package xyz.felismp.shoparchive.server.net

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.UserAdminService
import xyz.felismp.shoparchive.shared.AddUserBranchRequest
import xyz.felismp.shoparchive.shared.CreateUserRequest
import xyz.felismp.shoparchive.shared.RenameUserRequest
import xyz.felismp.shoparchive.shared.SetUserRoleRequest

/** The caller, after the PIN or password was checked to be recent: like the console, no admin call is answered without it, whatever the [UserAdminService] is. */
private fun ApplicationCall.admin(services: ServiceRegistry): Principal = attributes[PrincipalKey].also { services.requireRecentAuth(it) }

private fun ApplicationCall.user(): String = parameters["name"].orEmpty()

/** The accounts, for an op in the app. The routes only pass on; the [UserAdminService] decides who may. Branches and categories are the record routes. */
internal fun Route.adminRoutes(services: ServiceRegistry) {
    get("/admin/users") { call.respond(blocking { services.require<UserAdminService>().users(call.admin(services)) }) }

    get("/admin/roles") { call.respond(blocking { services.require<UserAdminService>().roles(call.admin(services)) }) }

    post("/admin/users") {
        val principal = call.admin(services)
        val body = call.receive<CreateUserRequest>()
        call.respond(HttpStatusCode.Created, blocking { services.require<UserAdminService>().add(principal, body, call.ip) })
    }

    post("/admin/users/{name}/rename") {
        val principal = call.admin(services)
        val body = call.receive<RenameUserRequest>()
        call.respond(blocking { services.require<UserAdminService>().rename(principal, call.user(), body.name, call.ip) })
    }

    put("/admin/users/{name}/role") {
        val principal = call.admin(services)
        val body = call.receive<SetUserRoleRequest>()
        call.respond(blocking { services.require<UserAdminService>().setRole(principal, call.user(), body.role, call.ip) })
    }

    post("/admin/users/{name}/branches") {
        val principal = call.admin(services)
        val body = call.receive<AddUserBranchRequest>()
        val user = blocking { services.require<UserAdminService>().addBranch(principal, call.user(), body.branch, call.ip) }
        services.userConfigChanged(user.id)
        call.respond(user)
    }

    delete("/admin/users/{name}/branches/{branch}") {
        val principal = call.admin(services)
        val user = blocking { services.require<UserAdminService>().removeBranch(principal, call.user(), call.parameters["branch"].orEmpty(), call.ip) }
        services.userConfigChanged(user.id)
        call.respond(user)
    }

    post("/admin/users/{name}/enable") {
        val principal = call.admin(services)
        call.respond(blocking { services.require<UserAdminService>().setEnabled(principal, call.user(), true, call.ip) })
    }

    post("/admin/users/{name}/disable") {
        val principal = call.admin(services)
        call.respond(blocking { services.require<UserAdminService>().setEnabled(principal, call.user(), false, call.ip) })
    }

    post("/admin/users/{name}/unlock") {
        val principal = call.admin(services)
        call.respond(blocking { services.require<UserAdminService>().unlock(principal, call.user(), call.ip) })
    }

    post("/admin/users/{name}/reset") {
        val principal = call.admin(services)
        call.respond(blocking { services.require<UserAdminService>().reset(principal, call.user(), call.ip) })
    }
}
