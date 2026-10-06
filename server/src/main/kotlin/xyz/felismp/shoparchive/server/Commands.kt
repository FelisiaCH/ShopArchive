package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.RegistryConflictException
import java.util.Locale

/** The console as a command sender: replies are always shown, and also land in latest.log when the log level allows. */
internal object ConsoleSender : CommandSender {
    override val name = "Console"
    override fun sendMessage(message: String) = message.lines().forEach(Log::output)
}

/** The command registry, plus what the console needs on top of it: running a typed line and completing it. */
internal class Commands : CommandRegistry {
    private class Entry(val command: Command, val owner: String)

    private val byName = sortedMapOf<String, Entry>()

    @Synchronized
    override fun register(command: Command, owner: String) {
        val key = command.name.lowercase(Locale.ROOT)
        byName[key]?.let {
            throw RegistryConflictException("command '$key': '${it.owner}' and '$owner' both register it")
        }
        byName[key] = Entry(command, owner)
    }

    @Synchronized
    override fun all(): List<Command> = byName.values.map { it.command }

    @Synchronized
    override fun find(name: String): Command? = byName[name.lowercase(Locale.ROOT)]?.command

    /** Removes every command [owner] registered (a plugin that failed or was disabled); returns how many went. */
    @Synchronized
    fun unregisterOwner(owner: String): Int {
        val before = byName.size
        byName.values.removeAll { it.owner == owner }
        return before - byName.size
    }

    /** Runs one typed line. A command that throws is logged and the console carries on. */
    fun run(sender: CommandSender, line: String) {
        val words = line.trim().split(Regex("\\s+"))
        if (words[0].isEmpty()) return
        val command = find(words[0])
        if (command == null) {
            sender.sendMessage("Unknown command '${words[0]}' (try help)")
            return
        }
        try {
            command.execute(sender, words.drop(1))
        } catch (e: Exception) {
            Log.error("Command '${command.name}' failed", e)
        }
    }

    /** Candidates for the last word of [words]: command names for the first word, else the command's own. */
    fun complete(words: List<String>): List<String> {
        if (words.size <= 1) {
            val typed = words.firstOrNull().orEmpty().lowercase(Locale.ROOT)
            return all().map { it.name }.filter { it.startsWith(typed) }
        }
        return find(words[0])?.complete(words.drop(1)).orEmpty()
    }
}
