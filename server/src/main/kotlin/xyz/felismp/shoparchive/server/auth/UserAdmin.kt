package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.UserAdminService
import xyz.felismp.shoparchive.server.OP_NODE
import xyz.felismp.shoparchive.server.users.UserException
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.server.users.isUsableCredential
import xyz.felismp.shoparchive.server.users.joinedBranches
import xyz.felismp.shoparchive.shared.AdminRoleDto
import xyz.felismp.shoparchive.shared.AdminUserDto
import xyz.felismp.shoparchive.shared.CreateUserRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.isSlug

/**
 * `/api/v1/admin/users`: the console's `user` command for an op in the app. The same [UserStore] and [AccountConsole] methods do the work, so a change is
 * what the console would have made. Like every command from the app it is for an op who entered the PIN or password recently, and every change is audited.
 */
internal class DefaultUserAdminService(
    private val users: UserStore,
    private val accounts: AccountConsole,
    private val audit: AuditLog,
    private val services: ServiceRegistry,
    /** The keys of the branches not archived (see [xyz.felismp.shoparchive.server.users.registerUserCommands]). */
    private val activeBranches: () -> List<String>,
) : UserAdminService {
    private val lock = Any()

    private val auth: AuthService
        get() = services.get(AuthService::class.java) ?: throw ApiError(500, ErrorCode.INTERNAL, "AuthService is not registered")

    override fun users(principal: Principal): List<AdminUserDto> = admin(principal) { users.userNames().map(::dto) }

    override fun roles(principal: Principal): List<AdminRoleDto> = admin(principal) {
        users.roleNames().map { AdminRoleDto(it, users.usersWithRole(it).size) }
    }

    override fun add(principal: Principal, request: CreateUserRequest, ip: String): AdminUserDto = change(principal, "admin.user.add", request.name, ip) {
        request.branches.forEach(::checkBranch)
        val active = if (request.branches.isEmpty()) activeBranches() else emptyList()
        users.addUser(request.name, request.role, joinedBranches(request.branches, active))
        request.name
    }

    override fun rename(principal: Principal, name: String, newName: String, ip: String): AdminUserDto = change(principal, "admin.user.rename", name, ip) {
        users.rename(name, newName)
        newName
    }

    override fun setRole(principal: Principal, name: String, role: String, ip: String): AdminUserDto = change(principal, "admin.user.role", name, ip) {
        guardAccount(principal, name)
        users.setRole(name, role)
        name
    }

    override fun addBranch(principal: Principal, name: String, branch: String, ip: String): AdminUserDto = change(principal, "admin.user.branch-add", name, ip) {
        checkBranch(branch)
        users.addBranch(name, branch)
        name
    }

    override fun removeBranch(principal: Principal, name: String, branch: String, ip: String): AdminUserDto = change(principal, "admin.user.branch-remove", name, ip) {
        users.removeBranch(name, branch)
        name
    }

    override fun setEnabled(principal: Principal, name: String, enabled: Boolean, ip: String): AdminUserDto =
        change(principal, if (enabled) "admin.user.enable" else "admin.user.disable", name, ip) {
            if (enabled) {
                users.setEnabled(name, true)
            } else {
                guardAccount(principal, name)
                accounts.disableAccount(name)
            }
            name
        }

    override fun unlock(principal: Principal, name: String, ip: String): AdminUserDto = change(principal, "admin.user.unlock", name, ip) {
        users.unlockAccount(name)
        name
    }

    override fun reset(principal: Principal, name: String, ip: String): AdminUserDto = change(principal, "admin.user.reset", name, ip) {
        // From the app the reset would end the very session it is asked from, and the op's own device with it.
        if (users.user(name).id == principal.userId) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "Your own account can only be reset on the server console.", reason = ErrorReasons.USER_SELF_RESET)
        }
        accounts.resetAccount(name, ip)
        name
    }

    /** Runs [work] (which answers the name the account has afterwards), audits it, and answers the account. */
    private fun change(principal: Principal, event: String, target: String, ip: String, work: () -> String): AdminUserDto = admin(principal) {
        val name = work()
        audit.record(event, principal.username, principal.deviceId, ip, "ok,target=$target")
        dto(name)
    }

    /**
     * An op (the PIN or password was asked for by the route); a rule the admin broke ([UserException]) becomes the refusal the app words. The whole change runs under [lock]:
     * the op is checked, the last-op and self guards read the accounts and the write is made with no other admin change in between, so two ops
     * disabling each other at the same moment cannot both pass the guard.
     */
    private fun <T> admin(principal: Principal, block: () -> T): T = synchronized(lock) {
        if (!auth.hasPermission(principal, OP_NODE)) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "This needs an admin (op).", reason = ErrorReasons.PERMISSION_MISSING)
        }
        try {
            block()
        } catch (e: UserException) {
            val code = when (e.status) {
                404 -> ErrorCode.NOT_FOUND
                409 -> ErrorCode.CONFLICT
                else -> ErrorCode.INVALID_REQUEST
            }
            throw ApiError(e.status, code, e.message ?: "Failed", reason = e.reason)
        }
    }

    /** From the app (the console is not held to this): an op cannot lock themselves out, and the last enabled op cannot be taken away. */
    private fun guardAccount(principal: Principal, name: String) {
        val target = users.user(name)
        if (target.id == principal.userId) {
            throw ApiError(409, ErrorCode.CONFLICT, "You cannot disable your own account or change your own role here.", reason = ErrorReasons.USER_SELF_CHANGE)
        }
        if (target.op && target.enabled && users.userNames().count { users.user(it).let { u -> u.op && u.enabled } } == 1) {
            throw ApiError(409, ErrorCode.CONFLICT, "'$name' is the last enabled op.", reason = ErrorReasons.USER_LAST_OP)
        }
    }

    private fun checkBranch(key: String) {
        if (!isSlug(key)) throw UserException("A branch key is 1 to 32 characters from a-z, 0-9 and -.", ErrorReasons.KEY_INVALID)
    }

    private fun dto(name: String): AdminUserDto {
        val user = users.user(name)
        return AdminUserDto(
            id = user.id,
            name = name,
            enabled = user.enabled,
            disabledReason = user.disabledReason.takeIf { !user.enabled },
            op = user.op,
            role = user.role,
            branches = user.branches,
            locale = user.locale,
            failedLogins = user.failedLogins,
            lockedUntil = user.lockedUntil?.toString(),
            hasPin = isUsableCredential(user.pin),
            hasPassword = isUsableCredential(user.password),
        )
    }
}
