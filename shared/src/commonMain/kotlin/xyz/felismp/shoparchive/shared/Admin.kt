package xyz.felismp.shoparchive.shared

import kotlinx.serialization.Serializable

// --- the admin area: `/api/v1/admin/...` does what the console's `user` command does; branches and categories are `/branches` and `/categories` ---

/** The [AdminUserDto.role] of a user without a role: its permissions are its own. */
const val ROLE_NONE = "none"

/**
 * One account as an op sees it. [role] is a role name or [ROLE_NONE]. [disabledReason] is `admin` (an op did it) or `backoff` (too many wrong tries; unlock enables it again),
 * null while enabled. [lockedUntil] is a time with its offset while the account waits after wrong tries. [hasPin] and [hasPassword] say only whether the account can log in
 * with one: a new account has neither, and sets its PIN at its first login. No secret is ever sent.
 */
@Serializable
data class AdminUserDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val op: Boolean = false,
    val role: String = ROLE_NONE,
    val branches: List<String> = emptyList(),
    val locale: String = "en",
    val failedLogins: Int = 0,
    val lockedUntil: String? = null,
    val hasPin: Boolean = false,
    val hasPassword: Boolean = false,
    /** Only in the answer to adding a user or resetting one, once: the code they type with their new PIN. */
    val setupCode: String? = null,
)

/** A role and how many users have it. */
@Serializable
data class AdminRoleDto(val name: String, val users: Int = 0)

/** Body of `POST /api/v1/admin/users`. Without [branches] the user joins the only active branch, if there is exactly one (as `user add` does). */
@Serializable
data class CreateUserRequest(val name: String, val role: String = ROLE_NONE, val branches: List<String> = emptyList())

/** Body of `POST /api/v1/admin/users/{name}/rename`: the account keeps its id. */
@Serializable
data class RenameUserRequest(val name: String)

/** Body of `PUT /api/v1/admin/users/{name}/role`: a role name or [ROLE_NONE]. */
@Serializable
data class SetUserRoleRequest(val role: String)

/** Body of `POST /api/v1/admin/users/{name}/branches`. */
@Serializable
data class AddUserBranchRequest(val branch: String)
