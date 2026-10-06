package xyz.felismp.shoparchive.api

import xyz.felismp.shoparchive.shared.CommandResponse

/** Who ran a command; [sendMessage] shows [message] to them (the console prints it, one line at a time). */
interface CommandSender {
    val name: String
    fun sendMessage(message: String)

    /** Whether the sender holds the permission [node]. The console holds every one; a user in the app holds the ones their account does. */
    fun hasPermission(node: String): Boolean = true
}

interface Command {
    /** Lower-case, no spaces; looked up ignoring case. */
    val name: String
    val description: String

    /**
     * The permission node a user in the app needs to run this command (the console needs none). A plugin registers the node
     * in `onLoad` like any other, or only an op can run its command from the app.
     */
    val permission: String get() = "shoparchive.command.$name"

    fun execute(sender: CommandSender, args: List<String>)

    /** Tab-completion candidates for [args]; the last element is the (possibly empty) word being typed. */
    fun complete(args: List<String>): List<String> = emptyList()
}

interface CommandRegistry {
    /** @throws RegistryConflictException if a command with the same name (ignoring case) is already registered. */
    fun register(command: Command, owner: String)

    /** Every registered command, sorted by name. */
    fun all(): List<Command>

    fun find(name: String): Command?
}

/** The console for a user in the app. Routes call it through the [ServiceRegistry], so a plugin can replace it. */
interface CommandService {
    /**
     * Runs [line] as [principal]; the result holds what the command sent to its sender.
     * @throws ApiError 403 for a command the user has no permission node for, or one that only the console may run; 401 [xyz.felismp.shoparchive.shared.ErrorCode.REAUTH_REQUIRED]
     */
    fun run(principal: Principal, line: String, ip: String): CommandResponse

    /** The words that could end [line], from the commands [principal] may run. Same refusals as [run]. */
    fun complete(principal: Principal, line: String): List<String>
}
