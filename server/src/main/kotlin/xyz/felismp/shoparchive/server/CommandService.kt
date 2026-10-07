package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.CommandService
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.auth.AuditLog
import xyz.felismp.shoparchive.shared.CommandResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.PermissionNodes
import java.util.Locale

/** The commands the core registers that a user may be given in the app; each gets the node `shoparchive.command.<name>`, off for everyone but an op. */
private val APP_COMMANDS = listOf(
    "help", "version", "status", "stop", "reload", "plugins", "backup", "say", "ban-ip", "pardon-ip", "notify",
    "branch", "category", "records", "export", "user", "perm", "role", "devices",
)

/** What changes who is an admin is not something a stolen, still-unlocked phone may do: only the console runs these. */
internal val CONSOLE_ONLY_COMMANDS = setOf("op", "deop")

/** What manages accounts, roles and devices is not for anyone but an op, whatever node they hold: with `user reset` or `perm` a user would become the op. */
internal val OP_ONLY_COMMANDS = setOf("user", "perm", "role", "devices")

/** The word an op alone holds: [xyz.felismp.shoparchive.server.users.UserStore] gives an op every node, registered or not (as `auth.password.required-for` reads it). */
private const val OP_NODE = "op"

/** At most this many characters of a line go into the audit log. */
private const val AUDITED_CHARS = 200

private val WHITESPACE = Regex("\\s+")

/** Registers the node of every core command in [APP_COMMANDS], before the user files are read. A plugin registers the nodes of its own commands in `onLoad`. */
internal fun registerCommandNodes(nodes: PermissionNodeRegistry) {
    for (name in APP_COMMANDS) {
        nodes.register(
            PermissionNode(
                "${PermissionNodes.COMMAND_PREFIX}$name", "Run the '$name' command in the app's console", default = false,
                th = "ใช้คำสั่ง $name ในคอนโซลของแอป",
                lo = "ໃຊ້ຄຳສັ່ງ $name ໃນຄອນໂຊນຂອງແອັບ",
            ),
        )
    }
}

/**
 * A sender who is not at the server: what a command tells them goes back in the answer to the request, so unlike the console it may carry
 * a secret (a pairing link), which then never reaches the log. What it does on someone's behalf is checked as that [principal], from [ip].
 */
internal interface RemoteSender : CommandSender {
    val principal: Principal
    val ip: String
}

/** A user in the app: [hasPermission] asks the [AuthService], so a role changed meanwhile counts at once. */
private class AppSender(override val principal: Principal, override val ip: String, private val auth: AuthService) : RemoteSender {
    val lines = mutableListOf<String>()
    override val name get() = principal.username
    override fun sendMessage(message: String) {
        lines += message.lines()
    }

    override fun hasPermission(node: String) = auth.hasPermission(principal, node)
}

/**
 * `POST /api/v1/command`: a user in the app runs the commands the console runs. Each command is checked against its own node
 * ([Command.permission]); `op` and `deop` are never run here, and [OP_ONLY_COMMANDS] only by an op. Like deleting an entry it needs the PIN or password entered recently, and
 * every run is written to the audit log with the line as typed.
 */
internal class DefaultCommandService(
    private val commands: Commands,
    private val audit: AuditLog,
    private val services: ServiceRegistry,
) : CommandService {
    private val auth: AuthService
        get() = services.get(AuthService::class.java) ?: throw ApiError(500, ErrorCode.INTERNAL, "AuthService is not registered")

    override fun run(principal: Principal, line: String, ip: String): CommandResponse {
        val words = line.trim().split(WHITESPACE)
        if (words[0].isEmpty()) return CommandResponse(emptyList())
        val sender = AppSender(principal, ip, auth)
        val command = commands.find(words[0])
        if (command == null) {
            sender.sendMessage("Unknown command '${words[0]}' (try help)")
            return CommandResponse(sender.lines)
        }
        allow(principal, command)
        audit.record("command.run", principal.username, principal.deviceId, ip, line.trim().take(AUDITED_CHARS))
        try {
            command.execute(sender, words.drop(1))
        } catch (e: Exception) {
            Log.error("Command '${command.name}' failed", e)
            sender.sendMessage("The command failed; the server log says why.")
        }
        return CommandResponse(sender.lines)
    }

    override fun complete(principal: Principal, line: String): List<String> {
        val words = line.trimStart().split(WHITESPACE)
        if (words.size > 1) {
            val command = commands.find(words[0]) ?: return emptyList()
            allow(principal, command)
            return command.complete(words.drop(1))
        }
        auth.requireRecentAuth(principal)
        val typed = words[0].lowercase(Locale.ROOT)
        return commands.all().filter { it.name.startsWith(typed) && it.name !in CONSOLE_ONLY_COMMANDS && mayRun(principal, it) }.map { it.name }
    }

    /** @throws ApiError the command is for the console only, the caller lacks its node (or is no op for an [OP_ONLY_COMMANDS] one), or the PIN or password was not entered recently */
    private fun allow(principal: Principal, command: Command) {
        if (command.name.lowercase(Locale.ROOT) in CONSOLE_ONLY_COMMANDS) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "'${command.name}' can only be run on the server console.", reason = ErrorReasons.COMMAND_CONSOLE_ONLY)
        }
        if (!auth.hasPermission(principal, command.permission)) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "This needs the permission ${command.permission}.", reason = ErrorReasons.PERMISSION_MISSING)
        }
        if (!mayRun(principal, command)) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "'${command.name}' needs an admin (op).", reason = ErrorReasons.PERMISSION_MISSING)
        }
        auth.requireRecentAuth(principal)
    }

    private fun mayRun(principal: Principal, command: Command): Boolean =
        auth.hasPermission(principal, command.permission) &&
            (command.name.lowercase(Locale.ROOT) !in OP_ONLY_COMMANDS || auth.hasPermission(principal, OP_NODE))
}
