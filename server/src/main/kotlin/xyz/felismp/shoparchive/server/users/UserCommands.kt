package xyz.felismp.shoparchive.server.users

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender

/** A console command whose [UserException]s are shown to the admin as a plain message. */
private class UserCommand(
    override val name: String,
    override val description: String,
    private val run: (CommandSender, List<String>) -> Unit,
    private val completer: (List<String>) -> List<String>,
) : Command {
    override fun execute(sender: CommandSender, args: List<String>) {
        try {
            run(sender, args)
        } catch (e: UserException) {
            sender.sendMessage(e.message ?: "Failed")
        }
    }

    override fun complete(args: List<String>) = completer(args).filter { it.startsWith(args.last()) }
}

private fun usage(text: String): Nothing = throw UserException("Usage: $text")

private fun parseBool(word: String): Boolean = when (word) {
    "true" -> true
    "false" -> false
    else -> throw UserException("Expected true or false, not '$word'")
}

/** Starts an account over (no devices, no password, no PIN); the user sets a new PIN at their next login. */
internal typealias ResetUser = (CommandSender, String) -> Unit

/** `user`, `perm`, `role`, `op` and `deop`, registered like any plugin command. Without [reset] there is no `user reset`; [disable] also revokes the grants of the account (same type as [reset]). */
internal fun registerUserCommands(commands: CommandRegistry, users: UserStore, reset: ResetUser? = null, disable: ResetUser? = null) {
    val bools = listOf("true", "false")

    commands.register(UserCommand("user", "Manage users: add, list, info, enable, disable, unlock, reset, rename, role, branch", { sender, args -> userCommand(users, sender, args, reset, disable) }) { args ->
        val sub = args.first()
        when {
            args.size == 1 -> listOf("add", "list", "info", "enable", "disable", "unlock", "reset", "rename", "role", "branch")
            sub == "add" -> addCompletion(users, args)
            args.size == 2 && sub in setOf("info", "enable", "disable", "unlock", "reset", "rename", "role", "branch") -> users.userNames()
            args.size == 3 && sub == "role" -> users.roleNames() + NO_ROLE
            args.size == 3 && sub == "branch" -> listOf("add", "remove")
            args.size == 4 && sub == "branch" && args[2] == "remove" -> runCatching { users.user(args[1]).branches }.getOrDefault(emptyList())
            else -> emptyList()
        }
    }, "core")

    commands.register(UserCommand("perm", "Set a permission of a user without a role; perm search <keyword> finds nodes", { sender, args -> permCommand(users, sender, args) }) { args ->
        when (args.size) {
            1 -> users.userNames() + "search"
            2 -> if (args[0] == "search") emptyList() else users.nodeNames()
            3 -> if (args[0] == "search") emptyList() else bools
            else -> emptyList()
        }
    }, "core")

    commands.register(UserCommand("role", "Manage roles: create, list, perm", { sender, args -> roleCommand(users, sender, args) }) { args ->
        when {
            args.size == 1 -> listOf("create", "list", "perm")
            args.size == 2 && args[0] == "perm" -> users.roleNames()
            args.size == 3 && args[0] == "perm" -> users.nodeNames()
            args.size == 4 && args[0] == "perm" -> bools
            else -> emptyList()
        }
    }, "core")

    for (op in listOf(true, false)) {
        val name = if (op) "op" else "deop"
        commands.register(UserCommand(name, if (op) "Make a user an admin with every permission" else "Take admin away from a user", { sender, args ->
            if (args.size != 1) usage("$name <name>")
            users.setOp(args[0], op)
            sender.sendMessage(if (op) "${args[0]} is an op now: every permission" else "${args[0]} is not an op any more")
        }) { args -> if (args.size == 1) users.userNames() else emptyList() }, "core")
    }
}

private fun addCompletion(users: UserStore, args: List<String>): List<String> {
    if (args.size == 2) return emptyList() // the new name
    return when (args[args.size - 2]) {
        "--role" -> users.roleNames()
        else -> listOf("--role", "--branch")
    }
}

private fun userCommand(users: UserStore, sender: CommandSender, args: List<String>, reset: ResetUser?, disable: ResetUser?) {
    val rest = args.drop(1)
    when (args.firstOrNull()) {
        "add" -> {
            if (rest.isEmpty()) usage("user add <name> [--role <role>] [--branch <branch>...]")
            val (role, branches) = parseAddFlags(rest.drop(1))
            users.addUser(rest[0], role, branches)
            sender.sendMessage(
                "User '${rest[0]}' created" + (if (role != NO_ROLE) ", role $role" else "") + (if (branches.isNotEmpty()) ", branches ${branches.joinToString()}" else "") +
                    ". They open the app, type the name '${rest[0]}' and set their own PIN.",
            )
        }
        "list" -> {
            if (users.userNames().isEmpty()) sender.sendMessage("No users yet. Create one with: user add <name>")
            for (name in users.userNames()) {
                val u = users.user(name)
                sender.sendMessage("$name: ${if (u.enabled) "enabled" else "disabled"}${if (u.op) ", op" else ""}, role ${u.role}, branches [${u.branches.joinToString(", ")}]")
            }
        }
        "info" -> {
            if (rest.size != 1) usage("user info <name>")
            info(users, sender, rest[0])
        }
        "enable", "disable" -> {
            if (rest.size != 1) usage("user ${args[0]} <name>")
            if (args[0] == "disable" && disable != null) {
                users.user(rest[0]) // "No user" before anything is revoked
                disable(sender, rest[0])
            } else {
                users.setEnabled(rest[0], args[0] == "enable")
                sender.sendMessage("User '${rest[0]}' ${args[0]}d")
            }
        }
        "unlock" -> {
            if (rest.size != 1) usage("user unlock <name>")
            val enabled = users.unlockAccount(rest[0])
            sender.sendMessage("User '${rest[0]}': wrong tries and lock cleared" + if (enabled) ", enabled again" else "")
        }
        "reset" -> {
            if (reset == null) usage("user add|list|info|enable|disable|unlock|rename|role|branch ...")
            if (rest.size != 1) usage("user reset <name>")
            users.user(rest[0]) // "No user" before anything is removed
            reset(sender, rest[0])
        }
        "rename" -> {
            if (rest.size != 2) usage("user rename <old> <new>")
            users.rename(rest[0], rest[1])
            sender.sendMessage("User '${rest[0]}' is now '${rest[1]}' (same id)")
        }
        "role" -> {
            if (rest.size != 2) usage("user role <name> <role|$NO_ROLE>")
            val before = users.setRole(rest[0], rest[1])
            sender.sendMessage(
                if (rest[1] == NO_ROLE) "User '${rest[0]}' has no role now; the values of role '$before' are its own"
                else "User '${rest[0]}' now has role '${rest[1]}' (the permissions it had before are in the log)"
            )
        }
        "branch" -> {
            if (rest.size != 3 || rest[1] !in listOf("add", "remove")) usage("user branch <name> add|remove <branch>")
            if (rest[1] == "add") users.addBranch(rest[0], rest[2]) else users.removeBranch(rest[0], rest[2])
            sender.sendMessage("User '${rest[0]}': branch '${rest[2]}' ${if (rest[1] == "add") "added" else "removed"}")
        }
        else -> usage("user add|list|info|enable|disable|unlock|reset|rename|role|branch ...")
    }
}

/** `--role <role>` and `--branch <b> [<b>...]` in any order. */
private fun parseAddFlags(flags: List<String>): Pair<String, List<String>> {
    var role = NO_ROLE
    val branches = mutableListOf<String>()
    var i = 0
    while (i < flags.size) {
        when (flags[i]) {
            "--role" -> role = flags.getOrNull(++i)?.takeUnless { it.startsWith("--") } ?: usage("user add <name> [--role <role>] [--branch <branch>...]")
            "--branch" -> {
                val start = branches.size
                while (flags.getOrNull(i + 1)?.startsWith("--") == false) branches += flags[++i]
                if (branches.size == start) usage("user add <name> [--role <role>] [--branch <branch>...]")
            }
            else -> usage("user add <name> [--role <role>] [--branch <branch>...]")
        }
        i++
    }
    return role to branches
}

private fun info(users: UserStore, sender: CommandSender, name: String) {
    val u = users.user(name)
    fun credential(value: String?) = when {
        value == null -> "none"
        isUsableCredential(value) -> "set"
        else -> "unusable (not an Argon2id hash)"
    }
    sender.sendMessage("User $name")
    sender.sendMessage("  id: ${u.id}")
    sender.sendMessage("  display-name: ${u.displayName}")
    sender.sendMessage("  enabled: ${u.enabled}" + (u.disabledReason?.let { " (disabled by $it)" } ?: ""))
    if (u.failedLogins > 0) sender.sendMessage("  wrong tries in a row: ${u.failedLogins}" + (u.lockedUntil?.let { ", locked until $it" } ?: ""))
    sender.sendMessage("  op: ${u.op}")
    sender.sendMessage("  role: ${u.role}")
    sender.sendMessage("  branches: [${u.branches.joinToString(", ")}]")
    sender.sendMessage("  locale: ${u.locale}")
    sender.sendMessage("  password: ${credential(u.password)}")
    sender.sendMessage("  pin: ${credential(u.pin)}")
    val nodes = users.nodeNames()
    sender.sendMessage(if (nodes.isEmpty()) "  permissions: no permission nodes are registered" else "  permissions (effective):")
    for (node in nodes) sender.sendMessage("    $node: ${users.hasPermission(name, node)}")
}

private fun permCommand(users: UserStore, sender: CommandSender, args: List<String>) {
    if (args.size == 2 && args[0] == "search") {
        val found = users.search(args[1])
        if (found.isEmpty()) sender.sendMessage("No permission node matches '${args[1]}'")
        for (node in found) sender.sendMessage("${node.node} (default ${node.default}) - ${node.description}")
        return
    }
    if (args.size != 3) usage("perm <user> <node> true|false   or   perm search <keyword>")
    users.setUserPermission(args[0], args[1], parseBool(args[2]))
    sender.sendMessage("User '${args[0]}': ${args[1]} = ${args[2]}")
}

private fun roleCommand(users: UserStore, sender: CommandSender, args: List<String>) {
    when (args.firstOrNull()) {
        "create" -> {
            if (args.size != 2) usage("role create <name>")
            users.addRole(args[1])
            sender.sendMessage("Role '${args[1]}' created with the default of every permission; change it with: role perm ${args[1]} <node> true|false")
        }
        "list" -> {
            if (users.roleNames().isEmpty()) sender.sendMessage("No roles yet. Create one with: role create <name>")
            for (name in users.roleNames()) sender.sendMessage("$name: ${users.usersWithRole(name).size} users")
        }
        "perm" -> {
            if (args.size != 4) usage("role perm <role> <node> true|false")
            val members = users.setRolePermission(args[1], args[2], parseBool(args[3]))
            sender.sendMessage("Role '${args[1]}': ${args[2]} = ${args[3]} (${members.size} users updated)")
        }
        else -> usage("role create|list|perm ...")
    }
}
