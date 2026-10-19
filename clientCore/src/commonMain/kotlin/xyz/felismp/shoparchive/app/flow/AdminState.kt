package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.shared.AdminRoleDto
import xyz.felismp.shoparchive.shared.AdminUserDto
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CreateBranchRequest
import xyz.felismp.shoparchive.shared.CreateCategoryRequest
import xyz.felismp.shoparchive.shared.CreateUserRequest
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.UpdateBranchRequest
import xyz.felismp.shoparchive.shared.UpdateCategoryRequest

/**
 * [users] and [roles] are read by [AdminState.load] (an op only; [loaded] is true after the first answer). [branches] and [categories] are the shop's lists from the
 * config, archived ones included for whoever may see them. [busy] while a call is with the server (the screen waits; a second action is ignored), [loading] while the users are read.
 * [failure] is why the last read or action did not work, until the next one starts or [AdminState.clearFailure]. [done] counts the actions that worked, so a screen can close its form when it changes.
 * [blocked]: the live connection is down, so nothing can be changed. The three [canManageUsers] flags are what the person may do; the screen leaves out the rest.
 */
data class AdminUi(
    val users: List<AdminUserDto> = emptyList(),
    val roles: List<AdminRoleDto> = emptyList(),
    val branches: List<BranchDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val failure: Failure? = null,
    val done: Int = 0,
    val blocked: Boolean = false,
    val canManageUsers: Boolean = false,
    val canManageBranches: Boolean = false,
    val canManageCategories: Boolean = false,
)

/**
 * The admin area: the users, branches and categories that the server console used to be needed for. The server decides who may and what is valid, and asks for the PIN or
 * password again when it wants it ([Calls] handles that prompt); this keeps the lists, runs one action at a time and shows the server's refusal as a [Failure].
 * After a branch or category change the config is read again, so every screen sees the new list at once.
 */
class AdminState(private val env: Env, private val reloadConfig: suspend () -> Unit) {
    private data class Core(
        val users: List<AdminUserDto> = emptyList(),
        val roles: List<AdminRoleDto> = emptyList(),
        val loaded: Boolean = false,
        val loading: Boolean = false,
        val busy: Boolean = false,
        val failure: Failure? = null,
        val done: Int = 0,
    )

    private val core = MutableStateFlow(Core())

    val ui: StateFlow<AdminUi> = combine(core, env.config, env.canWrite) { c, config, canWrite ->
        val can = config.capabilities()
        val ready = (config as? ConfigState.Ready)?.config
        AdminUi(
            c.users, c.roles, ready?.branches.orEmpty(), ready?.categories.orEmpty(), c.loaded, c.loading, c.busy, c.failure, c.done, !canWrite,
            can.manageUsers, can.manageBranches, can.manageCategories,
        )
    }.stateIn(env.scope, SharingStarted.Eagerly, AdminUi())

    private fun change(next: (Core) -> Core) {
        core.value = next(core.value)
    }

    /** Reads the users and the roles. Does nothing for a person who is no op. */
    fun load() {
        if (!env.can.manageUsers || core.value.loading) return
        change { it.copy(loading = true, failure = null) }
        env.scope.launch {
            try {
                val (users, roles) = env.calls.run { env.api.adminUsers() to env.api.adminRoles() }
                change { it.copy(users = users, roles = roles, loaded = true, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                change { it.copy(loading = false, failure = e.toFailure()) }
            }
        }
    }

    fun clearFailure() = change { it.copy(failure = null) }

    /** Runs [work] when [allowed], the connection is up and no other action is running. */
    private fun act(allowed: Boolean, work: suspend () -> Unit) {
        if (!allowed || !env.canWrite.value || core.value.busy) return
        change { it.copy(busy = true, failure = null) }
        env.scope.launch {
            try {
                work()
                change { it.copy(busy = false, done = it.done + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                change { it.copy(busy = false, failure = e.toFailure()) }
            }
        }
    }

    /** Puts [user] into the list (replacing the account with its id, which a rename keeps), by name. */
    private fun keep(user: AdminUserDto) = change { it.copy(users = (it.users.filter { u -> u.id != user.id } + user).sortedBy { u -> u.name }) }

    private fun userAction(call: suspend () -> AdminUserDto) = act(env.can.manageUsers) { keep(env.calls.run { call() }) }

    /** [role] is a role name or `none`; [branches] empty means the only active branch, if there is exactly one. The new user has no PIN: they set it at their first login. */
    fun addUser(name: String, role: String, branches: List<String>) = userAction { env.api.addUser(CreateUserRequest(name, role, branches)) }

    fun renameUser(name: String, to: String) = userAction { env.api.renameUser(name, to) }

    fun setUserRole(name: String, role: String) = userAction { env.api.setUserRole(name, role) }

    fun addUserBranch(name: String, branch: String) = userAction { env.api.addUserBranch(name, branch) }

    fun removeUserBranch(name: String, branch: String) = userAction { env.api.removeUserBranch(name, branch) }

    fun setUserEnabled(name: String, enabled: Boolean) = userAction { env.api.setUserEnabled(name, enabled) }

    /** Forgets the wrong tries and the lock; an account the server disabled for them is enabled again. */
    fun unlockUser(name: String) = userAction { env.api.unlockUser(name) }

    /** Takes the user off every device and clears the PIN and password. Not the person's own account (the server refuses). */
    fun resetUser(name: String) = userAction { env.api.resetUser(name) }

    fun addBranch(key: String, displayName: String) = act(env.can.manageBranches) {
        env.calls.run { env.api.addBranch(CreateBranchRequest(key, displayName)) }
        reloadConfig()
    }

    /** Renames the branch ([displayName] not null) and/or archives or restores it ([archived] not null). The key never changes. */
    fun updateBranch(key: String, displayName: String?, archived: Boolean?) = act(env.can.manageBranches) {
        env.calls.run { env.api.updateBranch(key, UpdateBranchRequest(displayName, archived)) }
        reloadConfig()
    }

    fun addCategory(key: String, name: LocalizedName, appliesTo: AppliesTo) = act(env.can.manageCategories) {
        env.calls.run { env.api.addCategory(CreateCategoryRequest(key, name, appliesTo)) }
        reloadConfig()
    }

    /** Changes only what is not null: the names, what the category is for, archived or restored. The key never changes. */
    fun updateCategory(key: String, name: LocalizedName?, appliesTo: AppliesTo?, archived: Boolean?) = act(env.can.manageCategories) {
        env.calls.run { env.api.updateCategory(key, UpdateCategoryRequest(name, appliesTo, archived)) }
        reloadConfig()
    }
}
