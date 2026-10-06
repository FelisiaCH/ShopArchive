package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender

/** `plugins` (list), `plugins info <name>`, `plugins approve <name|file>`, `plugins revoke <name>`, `plugins hotfix-hash <class>`. */
internal class PluginCommand(private val manager: PluginManager) : Command {
    override val name = "plugins"
    override val description = "List plugins; plugins info <name> / plugins approve <name|file> / plugins revoke <name> (applies at the next start); plugins hotfix-hash <class>: the SHA-256 a hotfix pins"

    override fun complete(args: List<String>): List<String> {
        if (args.size <= 1) return listOf("list", "info", "approve", "revoke", "hotfix-hash").filter { it.startsWith(args.firstOrNull().orEmpty()) }
        if (args[0] != "info" && args[0] != "approve" && args[0] != "revoke") return emptyList()
        return manager.entries.map { it.displayName }.filter { it.startsWith(args.last()) }
    }

    override fun execute(sender: CommandSender, args: List<String>) {
        val lines = when (args.firstOrNull()) {
            null, "list" -> manager.describe()
            "info" -> manager.info(args.drop(1).joinToString(" "))
            "approve" -> manager.approve(args.drop(1).joinToString(" "))
            "revoke" -> manager.revoke(args.drop(1).joinToString(" "))
            "hotfix-hash" -> hotfixHash(args.drop(1))
            else -> listOf("Usage: plugins [list | info <name> | approve <name|file> | revoke <name> | hotfix-hash <class>]")
        }
        lines.forEach(sender::sendMessage)
    }

    /** What a hotfix author puts in `@HotfixPatch(classSha256 = ...)`: the class file of this build, as the jar has it. */
    private fun hotfixHash(args: List<String>): List<String> {
        val className = args.singleOrNull()?.trim() ?: return listOf("Usage: plugins hotfix-hash <fully.qualified.ClassName>")
        val sha = classFileSha256(className) ?: return listOf("No class '$className' in this server")
        return listOf("$className SHA-256 $sha")
    }
}
