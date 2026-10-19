package xyz.felismp.shoparchive.server.net

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.UserAdminService
import xyz.felismp.shoparchive.shared.AddUserBranchRequest
import xyz.felismp.shoparchive.shared.CreateUserRequest
import xyz.felismp.shoparchive.shared.RenameUserRequest
import xyz.felismp.shoparchive.shared.SetUserRoleRequest

/** The accounts, for an op in the app. The routes only pass on; the [UserAdminService] decides who may. Branches and categories are the record routes. */
internal fun Route.adminRoutes(services: ServiceRegistry) {
    get("/admin/users") { call.respond(blocking { services.require<UserAdminService>().users(call.attributes[PrincipalKey]) }) }

    get("/admin/roles") { call.respond(blocking { services.require<UserAdminService>().roles(call.attributes[PrincipalKey]) }) }

    post("/admin/users") {
        val body = call.receive<CreateUserRequest>()
        call.respond(HttpStatusCode.Created, blocking { services.require<UserAdminService>().add(call.attributes[PrincipalKey], body, call.ip) })
    }

    post("/admin/users/{name}/rename") {
        val body = call.receive<RenameUserRequest>()
        call.respond(blocking { services.require<UserAdminService>().rename(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), body.name, call.ip) })
    }

    put("/admin/users/{name}/role") {
        val body = call.receive<SetUserRoleRequest>()
        call.respond(blocking { services.require<UserAdminService>().setRole(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), body.role, call.ip) })
    }

    post("/admin/users/{name}/branches") {
        val body = call.receive<AddUserBranchRequest>()
        call.respond(blocking { services.require<UserAdminService>().addBranch(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), body.branch, call.ip) })
    }

    delete("/admin/users/{name}/branches/{branch}") {
        val branch = call.parameters["branch"].orEmpty()
        call.respond(blocking { services.require<UserAdminService>().removeBranch(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), branch, call.ip) })
    }

    post("/admin/users/{name}/enable") {
        call.respond(blocking { services.require<UserAdminService>().setEnabled(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), true, call.ip) })
    }

    post("/admin/users/{name}/disable") {
        call.respond(blocking { services.require<UserAdminService>().setEnabled(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), false, call.ip) })
    }

    post("/admin/users/{name}/unlock") {
        call.respond(blocking { services.require<UserAdminService>().unlock(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), call.ip) })
    }

    post("/admin/users/{name}/reset") {
        call.respond(blocking { services.require<UserAdminService>().reset(call.attributes[PrincipalKey], call.parameters["name"].orEmpty(), call.ip) })
    }
}
