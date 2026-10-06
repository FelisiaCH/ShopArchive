package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.hotfix.HotfixTarget
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.ConfigService
import java.lang.management.ManagementFactory
import java.util.Locale

/** The names `reload <name>` re-reads in the core. A plugin with one of these names cannot be reloaded by name: the core's target wins. */
internal val CORE_RELOAD_NAMES = setOf("users", "devices", "data")

/** What `reload` can ask of the plugins; the core knows no more about them than this. */
internal interface PluginReloads {
    /** The plugins `reload <name>` accepts: the enabled ones. */
    fun names(): List<String>

    /** Re-reads that plugin's config and tells it; false if there is no enabled plugin of that name. */
    fun reload(name: String): Boolean

    /** Does [reload] for every enabled plugin; returns their names. */
    fun reloadAll(): List<String>

    companion object {
        val NONE = object : PluginReloads {
            override fun names() = emptyList<String>()
            override fun reload(name: String) = false
            override fun reloadAll() = emptyList<String>()
        }
    }
}

private class SimpleCommand(
    override val name: String,
    override val description: String,
    private val run: (CommandSender) -> Unit,
) : Command {
    override fun execute(sender: CommandSender, args: List<String>) = run(sender)
}

/**
 * The console commands, registered the way a plugin registers its own. [stop] is a parameter so tests can run
 * the command without the JVM exiting. [reloads] are the other things `reload <name>` can re-read; those named in
 * [alsoOnReload] are also re-read by a plain `reload`. [plugins] adds `reload <plugin>` and the plugins' configs to a plain `reload`.
 */
internal fun registerCoreCommands(
    commands: CommandRegistry,
    config: ConfigService,
    reloads: Map<String, () -> Unit> = emptyMap(),
    networkStatus: () -> List<String> = { emptyList() },
    alsoOnReload: Set<String> = emptySet(),
    plugins: PluginReloads = PluginReloads.NONE,
    stop: () -> Unit = { Shutdown.stop(0) },
) {
    require(CORE_RELOAD_NAMES.containsAll(reloads.keys)) { "reload targets ${reloads.keys - CORE_RELOAD_NAMES} are missing from CORE_RELOAD_NAMES" }
    fun add(name: String, description: String, run: (CommandSender) -> Unit) =
        commands.register(SimpleCommand(name, description, run), "core")

    add("help", "List the commands") { sender ->
        commands.all().forEach { sender.sendMessage("${it.name} - ${it.description}") }
    }
    add("version", "Show the ShopArchive version") { sender ->
        sender.sendMessage("ShopArchive ${coreVersion()}")
    }
    add("status", "Show uptime, memory use and the network") { sender ->
        val runtime = Runtime.getRuntime()
        val mb = 1024 * 1024
        sender.sendMessage("Uptime: ${formatUptime(ManagementFactory.getRuntimeMXBean().uptime / 1000)}")
        sender.sendMessage("Heap: ${(runtime.totalMemory() - runtime.freeMemory()) / mb} MB used of ${runtime.maxMemory() / mb} MB")
        networkStatus().forEach(sender::sendMessage)
    }
    add("stop", "Stop the server") { stop() }
    commands.register(ReloadCommand(config, reloads, alsoOnReload, plugins), "core")
}

private class ReloadCommand(
    private val config: ConfigService,
    private val reloads: Map<String, () -> Unit>,
    private val alsoOnReload: Set<String>,
    private val plugins: PluginReloads,
) : Command {
    override val name = "reload"
    override val description = "Re-read the config files and apply them (reload " +
        (reloads.keys.toList() + "<plugin>").joinToString("|") + ": only that)"

    override fun complete(args: List<String>) = (reloads.keys + plugins.names().filter { it !in reloads }).filter { it.startsWith(args.last()) }

    override fun execute(sender: CommandSender, args: List<String>) {
        if (args.isNotEmpty()) {
            if (args.size > 1) return usage(sender)
            // A core target wins over a plugin of the same name (PluginManager warned about it when that plugin was enabled).
            val target = reloads[args[0]]
            if (target != null) {
                target()
                return sender.sendMessage("Reload ${args[0]} done")
            }
            if (!plugins.reload(args[0])) return usage(sender)
            return sender.sendMessage("Reload ${args[0]} done")
        }
        // Before the config: a bad config file must not keep an admin's revocation from taking effect.
        alsoOnReload.forEach { reloads[it]?.invoke() }
        try {
            val changed = config.reload()
            applyConfig(config)
            sender.sendMessage(if (changed.isEmpty()) "Reload done: nothing changed" else "Reload done, changed: ${changed.joinToString(", ")}")
        } catch (e: ConfigFileException) {
            // An error for the log (file and console), not a message to the sender: it is not a command result.
            Log.error("Reload failed, keeping the current configuration: ${e.message}")
        }
        // After the core's config, also when that failed: a plugin's own config file has nothing to do with it.
        plugins.reloadAll().takeIf { it.isNotEmpty() }?.let { sender.sendMessage("Plugin configs reloaded: ${it.joinToString(", ")}") }
    }

    private fun usage(sender: CommandSender) =
        sender.sendMessage("Usage: reload" + (reloads.keys + plugins.names().filter { it !in reloads }).joinToString("") { " [$it]" })
}

/** The line `status` prints for the uptime. Marked as a hotfix target (and given a fixed JVM name) so the hotfix machinery has a harmless, real method to be tried on. */
@HotfixTarget
@JvmName("formatUptime")
internal fun formatUptime(totalSeconds: Long): String =
    String.format(Locale.ROOT, "%dh %02dm %02ds", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
