package xyz.felismp.shoparchive.server.users

import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.server.config.ConfigFile
import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import xyz.felismp.shoparchive.server.config.loadConfigFile
import xyz.felismp.shoparchive.server.config.peekConfigFile
import xyz.felismp.shoparchive.server.fsyncDirectory
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The users and roles under `user/`, in memory, and the only code that writes those files. A file the server
 * cannot understand is skipped (logged as an error) and never overwritten. Before the console changes a file the
 * file's modified time is compared with the one seen when it was read: a hand edit made in between is loaded
 * first and the change goes on top of it.
 *
 * Loading is a change too (it creates, migrates and rewrites files), so [load] runs inside the barrier like the rest. Every change takes the [DataBarrier] first and the store's own lock second (a public `x` calls `xInLock`), so a backup that holds
 * the barrier makes a change wait without making a reader wait for the lock that change would be holding.
 */
internal class UserStore(
    private val root: Path,
    private val nodes: PermissionNodeRegistry,
    private val config: ConfigService,
    private val log: ConfigLog = ConsoleConfigLog,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val barrier: DataBarrier = DataBarrier(),
) {
    // One "unchanged" line per file on every start would bury everything else once there are many users.
    private val fileLog = object : ConfigLog by log {
        override fun info(msg: String) {
            if (!msg.endsWith(": unchanged")) log.info(msg)
        }
    }

    private val users = sortedMapOf<String, UserData>()
    private val roles = sortedMapOf<String, RoleData>()
    private val seen = HashMap<Path, FileTime>()
    private val renameListeners = CopyOnWriteArrayList<(id: String, old: String, new: String) -> Unit>()
    private val accessListeners = CopyOnWriteArrayList<(id: String) -> Unit>()

    /** Reads every file again, rewriting each from the template (old copy saved under `data/migration/`), and regenerates `permissions.txt`; then [hintIfEmpty]. */
    fun load() {
        loadWithoutHint()
        hintIfEmpty()
    }

    /** [load] without the hint: a start gives it after the first-run setup, which may make the first user. */
    fun loadWithoutHint() = barrier.mutate { loadInLock() }

    /** With no users: how to make the first one. */
    fun hintIfEmpty() {
        if (userNames().isEmpty()) log.info("No users yet. Create the first one with: user add <name>   then make it an admin with: op <name>   then they open the app, type that name and set their own PIN")
    }

    @Synchronized
    private fun loadInLock() {
        Files.createDirectories(root.resolve("user/role"))
        secure(root.resolve("user"), "rwx------")
        secure(root.resolve("user/role"), "rwx------")
        users.clear()
        roles.clear()
        seen.clear()
        for (name in names("user/role", ::isValidRoleName)) readRole(name)
        for (name in names("user", ::isValidUserName)) readUser(name)
        writePermissionsTxt()
        log.info("user/: ${users.size} users and ${roles.size} roles loaded")
    }

    @Synchronized fun userNames(): List<String> = users.keys.toList()
    @Synchronized fun roleNames(): List<String> = roles.keys.toList()
    @Synchronized fun user(name: String): UserData = users[name] ?: throw UserException("No user '$name'")
    @Synchronized fun find(name: String): UserData? = users[name]

    /** The user with this id and the name it has now. A name can pass to another account after a rename; an id never does. */
    @Synchronized
    fun findById(id: String): Pair<String, UserData>? = users.entries.firstOrNull { it.value.id == id }?.let { it.key to it.value }

    /**
     * Like [findById], but the file is read again first if it changed since it was read, so a PIN set by hand or by another request is seen.
     * Null if the file cannot be read now. This is a read: it never writes and never takes the barrier, so a file that is not in template
     * form is left as it is (it stays "changed" and is read again next time) until the next change, which normalizes it under the barrier.
     */
    @Synchronized
    fun findFreshById(id: String): Pair<String, UserData>? {
        val name = findById(id)?.first ?: return null
        val path = userPath(name)
        return try {
            if (Files.getLastModifiedTime(path) == seen[path]) return name to user(name)
            log.warn("user/$name.yml was changed by hand since it was read; reading it again")
            val modified = Files.getLastModifiedTime(path)
            val peeked = peekConfigFile(root, userFile(name), fileLog)
            users[name] = peeked.value
            if (peeked.clean) seen[path] = modified
            name to peeked.value
        } catch (e: ConfigFileException) {
            log.error("${e.message}; this file is left as it is")
            null
        } catch (_: IOException) {
            null
        }
    }
    @Synchronized fun role(name: String): RoleData = roles[name] ?: throw UserException("No role '$name'")
    @Synchronized fun usersWithRole(role: String): List<String> = users.filterValues { it.role == role }.keys.toList()

    /** op -> every node; a role -> the role's value; `none` -> the user's own; a node without a value -> its default. A role that is gone, or an unknown user -> false. */
    @Synchronized
    fun hasPermission(username: String, node: String): Boolean = users[username]?.let { permits(it, node) } ?: false

    /** [hasPermission] for the account with this id, whatever it is called now; a name that changed hands is not the account. */
    @Synchronized
    fun hasPermissionById(id: String, node: String): Boolean = findById(id)?.let { permits(it.second, node) } ?: false

    private fun permits(user: UserData, node: String): Boolean {
        if (user.op) return true
        val values = if (user.role == NO_ROLE) user.permissions else roles[user.role]?.permissions ?: return false
        return values[node] ?: nodes.all().firstOrNull { it.node == node }?.default ?: false
    }

    fun addUser(name: String, role: String, branches: List<String>) = barrier.mutate { addUserInLock(name, role, branches) }

    @Synchronized
    private fun addUserInLock(name: String, role: String, branches: List<String>) {
        checkUserName(name)
        if (name in users || Files.exists(userPath(name))) throw UserException("User '$name' already exists")
        val roleData = if (role == NO_ROLE) null else role(role)
        val data = UserData(
            id = UUID.randomUUID().toString(),
            displayName = name,
            enabled = true,
            op = false,
            role = role,
            branches = branches.distinct(),
            locale = config.locale.toLanguageTag(),
            password = null,
            pin = null,
            permissions = roleData?.permissions ?: defaults(),
            newNodes = roleData?.newNodes ?: emptySet(),
        )
        users[name] = data
        writeUser(name, data)
    }

    fun addRole(name: String) = barrier.mutate { addRoleInLock(name) }

    @Synchronized
    private fun addRoleInLock(name: String) {
        checkRoleName(name)
        if (name in roles || Files.exists(rolePath(name))) throw UserException("Role '$name' already exists")
        val data = RoleData(name, defaults())
        roles[name] = data
        writeRole(name, data)
    }

    /** Enabling forgets the wrong tries; disabling by hand records that the admin did it, so `user unlock` leaves it alone. */
    fun setEnabled(name: String, enabled: Boolean) = barrier.mutate { setEnabledInLock(name, enabled) }

    @Synchronized
    private fun setEnabledInLock(name: String, enabled: Boolean) {
        val changed = editUser(name) {
            if (enabled) it.copy(enabled = true, disabledReason = null, failedLogins = 0, lockedUntil = null)
            else it.copy(enabled = false, disabledReason = DISABLED_BY_ADMIN)
        }
        if (!enabled) accessChanged(changed.id)
    }

    /**
     * Applies [change] (login counters, or disabling after too many wrong tries) to the account with [id], read again first.
     * Returns the account as it is now; null if it is gone or its file cannot be read or written (the error is logged).
     */
    fun updateLogin(id: String, change: (UserData) -> UserData): UserData? = barrier.mutate { updateLoginInLock(id, change) }

    @Synchronized
    private fun updateLoginInLock(id: String, change: (UserData) -> UserData): UserData? {
        val name = findById(id)?.first ?: return null
        return try {
            val current = freshUser(name)
            val changed = change(current)
            if (changed == current) return current
            editUser(name) { change(it) }.also { if (current.enabled && !it.enabled) accessChanged(id) }
        } catch (e: UserException) {
            log.error("user '$name': ${e.message}")
            null
        } catch (e: IOException) {
            log.error("user '$name': could not save the login state: ${e.message}")
            null
        }
    }

    /** `user unlock`: forgets the wrong tries and the lock; an account the server disabled for them is enabled again. Returns whether it was. */
    fun unlockAccount(name: String): Boolean = barrier.mutate { unlockAccountInLock(name) }

    @Synchronized
    private fun unlockAccountInLock(name: String): Boolean {
        val enable = freshUser(name).let { !it.enabled && it.disabledReason == DISABLED_BY_BACKOFF }
        editUser(name) { it.copy(failedLogins = 0, lockedUntil = null, enabled = it.enabled || enable, disabledReason = if (enable) null else it.disabledReason) }
        return enable
    }

    /** `user reset`: no password and no PIN, so the next login sets new ones; the wrong tries are forgotten too. Access tokens end. */
    fun resetCredentials(name: String) = barrier.mutate { resetCredentialsInLock(name) }

    @Synchronized
    private fun resetCredentialsInLock(name: String) {
        val changed = editUser(name) {
            val enable = !it.enabled && it.disabledReason == DISABLED_BY_BACKOFF
            it.copy(
                password = null, pin = null, failedLogins = 0, lockedUntil = null,
                enabled = it.enabled || enable, disabledReason = if (enable) null else it.disabledReason,
            )
        }
        accessChanged(changed.id)
    }

    fun setOp(name: String, op: Boolean) = barrier.mutate { setOpInLock(name, op) }

    @Synchronized
    private fun setOpInLock(name: String, op: Boolean) = editUser(name) { it.copy(op = op) }

    /**
     * Stores a password and/or PIN hash for the user with [id]; a null one is left as it is. Only a credential the user does
     * not have yet is set: if the file (read again first) already has one of those asked for, nothing is written and the
     * answer is false - someone else was first, and what they chose is not replaced.
     */
    fun setCredentials(id: String, passwordHash: String?, pinHash: String?): Boolean = barrier.mutate { setCredentialsInLock(id, passwordHash, pinHash) }

    @Synchronized
    private fun setCredentialsInLock(id: String, passwordHash: String?, pinHash: String?): Boolean {
        val name = findById(id)?.first ?: throw UserException("No user with id '$id'")
        val current = freshUser(name)
        if ((passwordHash != null && isUsableCredential(current.password)) || (pinHash != null && isUsableCredential(current.pin))) return false
        editUser(name) { it.copy(password = passwordHash ?: it.password, pin = pinHash ?: it.pin) }
        return true
    }

    /**
     * Gives the user with [id] the PIN hash [pinHash], replacing the one it has, and forgets the wrong tries and the lock.
     * For the first-run owner only, while it has no device: every other PIN is chosen by its user and kept ([setCredentials]).
     */
    fun replacePin(id: String, pinHash: String) = barrier.mutate { replacePinInLock(id, pinHash) }

    @Synchronized
    private fun replacePinInLock(id: String, pinHash: String) {
        val name = findById(id)?.first ?: throw UserException("No user with id '$id'")
        // editUser reads the file again first, so a hand edit made meanwhile is kept and only these fields change.
        editUser(name) { it.copy(pin = pinHash, failedLogins = 0, lockedUntil = null) }
    }

    fun addBranch(name: String, branch: String) = barrier.mutate { addBranchInLock(name, branch) }

    @Synchronized
    private fun addBranchInLock(name: String, branch: String) = editUser(name) { it.copy(branches = (it.branches + branch).distinct()) }

    fun removeBranch(name: String, branch: String) = barrier.mutate { removeBranchInLock(name, branch) }

    @Synchronized
    private fun removeBranchInLock(name: String, branch: String) {
        if (branch !in user(name).branches) throw UserException("User '$name' has no branch '$branch'")
        editUser(name) { it.copy(branches = it.branches - branch) }
    }

    /** Sets a node on a user without a role. A user with a role takes everything from the role, so that is refused. */
    fun setUserPermission(name: String, node: String, value: Boolean) = barrier.mutate { setUserPermissionInLock(name, node, value) }

    @Synchronized
    private fun setUserPermissionInLock(name: String, node: String, value: Boolean) {
        checkNode(node)
        val role = user(name).role
        if (role != NO_ROLE) {
            throw UserException("User '$name' takes its permissions from role '$role': change the role (role perm $role $node $value) or run: user role $name none")
        }
        editUser(name) { it.copy(permissions = it.permissions + (node to value), newNodes = it.newNodes - node) }
    }

    /** Sets a node on a role, then rewrites the permission copy of every user in it. Returns those users. */
    fun setRolePermission(roleName: String, node: String, value: Boolean): List<String> = barrier.mutate { setRolePermissionInLock(roleName, node, value) }

    @Synchronized
    private fun setRolePermissionInLock(roleName: String, node: String, value: Boolean): List<String> {
        checkNode(node)
        val role = freshRole(roleName)
        val changed = role.copy(permissions = role.permissions + (node to value), newNodes = role.newNodes - node)
        roles[roleName] = changed
        writeRole(roleName, changed)
        return usersWithRole(roleName).also { members -> members.forEach { member -> editUser(member) { it } } }
    }

    /**
     * Gives the user a role (the copy is rewritten, the values it had are logged) or `none` (the role's
     * values become the user's own). Returns the role it had.
     */
    fun setRole(name: String, role: String): String = barrier.mutate { setRoleInLock(name, role) }

    @Synchronized
    private fun setRoleInLock(name: String, role: String): String {
        val target = if (role == NO_ROLE) null else role(role)
        // The user and both roles are read from disk first: a hand edit made since they were read (the user's
        // role, or the old role's values) decides what is copied and logged, not what memory still holds.
        val before = freshUser(name)
        if (before.role == role) throw UserException("User '$name' already has role '$role'")
        val kept = if (before.role in roles) freshRole(before.role) else null
        if (target != null) freshRole(role)
        // Leaving a role: the values it gave become the user's own. Entering one: the user's old values are only in the log.
        val changed = editUser(name) { it.copy(role = role, permissions = if (target == null && kept != null) kept.permissions else it.permissions) }
        accessChanged(changed.id)
        if (target != null) log.info("user '$name': role ${before.role} -> $role, permissions before: ${describe(kept?.permissions ?: before.permissions)}")
        return before.role
    }

    /** Calls [listener] after every rename, with the lock held, so nothing looks the user up by id between the move and the listener. */
    fun onRename(listener: (id: String, old: String, new: String) -> Unit) {
        renameListeners += listener
    }

    /** Calls [listener] with the account id when its access tokens must end at once: disabled, role changed, or credentials cleared. The lock is held. */
    fun onAccessChanged(listener: (id: String) -> Unit) {
        accessListeners += listener
    }

    private fun accessChanged(id: String) {
        for (listener in accessListeners) listener(id)
    }

    fun rename(old: String, new: String) = barrier.mutate { renameInLock(old, new) }

    @Synchronized
    private fun renameInLock(old: String, new: String) {
        checkUserName(new)
        val data = user(old)
        if (new in users || Files.exists(userPath(new))) throw UserException("User '$new' already exists")
        // The file is moved as it is: its content has no name in it, and a move cannot leave half a file behind.
        Files.move(userPath(old), userPath(new), StandardCopyOption.ATOMIC_MOVE)
        fsyncDirectory(root.resolve("user"))
        users.remove(old)
        users[new] = data
        seen[userPath(new)] = seen.remove(userPath(old)) ?: Files.getLastModifiedTime(userPath(new))
        // Whatever else keeps the name (the device files) follows the rename; the user is renamed even if that fails.
        for (listener in renameListeners) {
            try {
                listener(data.id, old, new)
            } catch (e: Exception) {
                log.error("user '$old' -> '$new': ${e.message}; what was not updated has to be set up again")
            }
        }
    }

    /** Registered nodes that contain [keyword] in their name or description. */
    fun search(keyword: String): List<PermissionNode> =
        nodes.all().filter { it.node.contains(keyword, ignoreCase = true) || it.description.contains(keyword, ignoreCase = true) }

    fun nodeNames(): List<String> = nodes.all().map { it.node }

    // --- files ---

    private fun userPath(name: String) = root.resolve("user/$name.yml")
    private fun rolePath(name: String) = root.resolve("user/role/$name.yml")

    private fun defaults() = nodes.all().associate { it.node to it.default }

    private fun checkNode(node: String) {
        if (nodes.all().none { it.node == node }) throw UserException("Unknown permission node '$node' (find one with: perm search <keyword>)")
    }

    private fun describe(permissions: Map<String, Boolean>) =
        if (permissions.isEmpty()) "(none)" else permissions.entries.joinToString(", ") { "${it.key}=${it.value}" }

    private fun names(dir: String, valid: (String) -> Boolean): List<String> {
        val fileNames = Files.list(root.resolve(dir)).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".yml") }.sorted().toList()
        }
        return fileNames.filter { fileName ->
            valid(fileName.removeSuffix(".yml")).also { if (!it) log.warn("$dir/$fileName: not a valid name; the file is ignored") }
        }.map { it.removeSuffix(".yml") }
    }

    private fun userFile(name: String) = UserFile(name, roles, nodes.all(), config.locale.toLanguageTag())

    private fun readUser(name: String) {
        readFile(userFile(name), userPath(name))?.let { users[name] = it }
    }

    private fun readRole(name: String) {
        readFile(RoleFile(name, nodes.all()), rolePath(name))?.let { roles[name] = it }
    }

    /** The file's value, or null (after an error in the log) if it cannot be read; such a file is not touched. */
    private fun <V> readFile(file: ConfigFile<V>, path: Path): V? =
        try {
            loadConfigFile(root, file, fileLog, clock).value.also { remember(path) }
        } catch (e: ConfigFileException) {
            log.error("${e.message}; this file is skipped and left as it is")
            null
        }

    private fun remember(path: Path) {
        seen[path] = Files.getLastModifiedTime(path)
        secure(path, "rw-------")
    }

    /** The user as it is on disk now: if the file changed since it was read, it is read again first. */
    private fun freshUser(name: String): UserData {
        val current = user(name)
        val path = userPath(name)
        if (Files.getLastModifiedTime(path) == seen[path]) return current
        log.warn("user/$name.yml was changed by hand since it was read; reading it again before changing it")
        return readFile(userFile(name), path)?.also { users[name] = it }
            ?: throw UserException("user/$name.yml cannot be read now; nothing was changed")
    }

    private fun freshRole(name: String): RoleData {
        val current = role(name)
        val path = rolePath(name)
        if (Files.getLastModifiedTime(path) == seen[path]) return current
        log.warn("user/role/$name.yml was changed by hand since it was read; reading it again before changing it")
        return readFile(RoleFile(name, nodes.all()), path)?.also { roles[name] = it }
            ?: throw UserException("user/role/$name.yml cannot be read now; nothing was changed")
    }

    /** Applies [change] to the file's current content, keeps a role-user's permissions equal to its role, and writes it. */
    private fun editUser(name: String, change: (UserData) -> UserData): UserData {
        val current = freshUser(name)
        val changed = change(current).let { data ->
            roles[data.role]?.let { data.copy(permissions = it.permissions, newNodes = it.newNodes) } ?: data
        }
        users[name] = changed
        writeUser(name, changed)
        return changed
    }

    private fun writeUser(name: String, data: UserData) = write(userPath(name), userFile(name).render(data))

    private fun writeRole(name: String, data: RoleData) = write(rolePath(name), RoleFile(name, nodes.all()).render(data))

    private fun write(path: Path, text: String) = barrier.mutate {
        writeAtomically(path, text.toByteArray(Charsets.UTF_8))
        remember(path)
    }

    private fun writePermissionsTxt() {
        val language = config.locale.language
        val lines = nodes.all().map { node ->
            val text = when (language) {
                "lo" -> node.lo
                "th" -> node.th
                else -> null
            } ?: node.description
            "${node.node} | default: ${node.default} | $text"
        }
        val text = listOf(
            "# Permission nodes this server knows, one per line: node | default | description ($language, else English).",
            "# Written again on every start and on 'reload users'. Editing this file has no effect.",
        ).plus(lines).joinToString("\n", postfix = "\n")
        val path = root.resolve("user/permissions.txt")
        writeAtomically(path, text.toByteArray(Charsets.UTF_8)) // only [load] calls this, inside the barrier
        secure(path, "rw-------")
    }

    /** Owner-only access on a filesystem that has POSIX permissions (not Windows); nothing happens elsewhere. */
    private fun secure(path: Path, mode: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        } catch (_: UnsupportedOperationException) {
            // no POSIX permissions here
        }
    }
}
