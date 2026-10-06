package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.Refusal

/** What the record services ask of login: permissions, branch scope and a recent PIN, through the registry so a plugin's [AuthService] counts. */
internal class Access(private val services: ServiceRegistry) {
    private val auth: AuthService
        get() = services.get(AuthService::class.java) ?: throw ApiError(500, ErrorCode.INTERNAL, "AuthService is not registered")

    fun has(principal: Principal, node: String): Boolean = auth.hasPermission(principal, node)

    fun canBranch(principal: Principal, branch: String): Boolean = auth.canAccessBranch(principal, branch)

    fun require(principal: Principal, node: String) {
        if (!has(principal, node)) throw forbidden("This needs the permission $node.", ErrorReasons.PERMISSION_MISSING)
    }

    fun requireBranch(principal: Principal, branch: String) {
        if (!canBranch(principal, branch)) throw forbidden("This account does not work in that branch.", ErrorReasons.BRANCH_NOT_ALLOWED)
    }

    fun requireRecentAuth(principal: Principal) = auth.requireRecentAuth(principal)

    fun forbidden(message: String, reason: String? = null) = ApiError(403, ErrorCode.FORBIDDEN, message, reason = reason)
}

internal fun badRequest(message: String, reason: String? = null) = ApiError(400, ErrorCode.INVALID_REQUEST, message, reason = reason)

/** The refusal of a shared rule, e.g. [xyz.felismp.shoparchive.shared.checkEntry]. */
internal fun badRequest(refusal: Refusal) = badRequest(refusal.message, refusal.reason)

internal fun notFound(message: String) = ApiError(404, ErrorCode.NOT_FOUND, message)

internal fun conflict(message: String, reason: String? = null) = ApiError(409, ErrorCode.CONFLICT, message, reason = reason)
