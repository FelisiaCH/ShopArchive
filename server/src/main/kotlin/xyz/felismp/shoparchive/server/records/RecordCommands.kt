package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.wire

/** A console command whose [ApiError]s (a rule the admin broke) are shown as a plain message. */
private class RecordCommand(
    override val name: String,
    override val description: String,
    private val run: (CommandSender, List<String>) -> Unit,
    private val completer: (List<String>) -> List<String>,
) : Command {
    override fun execute(sender: CommandSender, args: List<String>) {
        try {
            run(sender, args)
        } catch (e: ApiError) {
            sender.sendMessage(e.message ?: "Failed")
        }
    }

    override fun complete(args: List<String>) = completer(args).filter { it.startsWith(args.last()) }
}

private fun usage(text: String): Nothing = throw ApiError(400, ErrorCode.INVALID_REQUEST, "Usage: $text")

/** `branch`, `category`, `records` and `export`. */
internal fun registerRecordCommands(commands: CommandRegistry, records: Records) {
    val branches = records.branches
    val categories = records.categories
    val languages = listOf("lo", "th", "en")

    commands.register(RecordCommand("branch", "Manage branches: add, list, archive, unarchive", { sender, args ->
        val rest = args.drop(1)
        when (args.firstOrNull()) {
            "add" -> {
                if (rest.size < 2) usage("branch add <key> <display name>")
                val branch = branches.add(rest[0], rest.drop(1).joinToString(" "))
                sender.sendMessage("Branch '${branch.key}' added: ${branch.displayName}")
            }
            "list" -> {
                if (branches.all().isEmpty()) sender.sendMessage("No branches yet. Add one with: branch add main Main")
                for (b in branches.all()) sender.sendMessage("${b.key}: ${b.displayName}${if (b.archived) " (archived)" else ""}")
            }
            "archive", "unarchive" -> {
                if (rest.size != 1) usage("branch ${args[0]} <key>")
                branches.update(rest[0], null, args[0] == "archive")
                sender.sendMessage("Branch '${rest[0]}' is ${if (args[0] == "archive") "archived" else "in use again"}")
            }
            else -> usage("branch add <key> <display name> | list | archive <key> | unarchive <key>")
        }
    }) { args ->
        when {
            args.size == 1 -> listOf("add", "list", "archive", "unarchive")
            args.size == 2 && args[0] in setOf("archive", "unarchive") -> branches.all().map { it.key }
            else -> emptyList()
        }
    }, "core")

    commands.register(RecordCommand("category", "Manage categories: add, name, list, archive, unarchive", { sender, args ->
        val rest = args.drop(1)
        when (args.firstOrNull()) {
            "add" -> {
                if (rest.size < 3) usage("category add <key> <income|expense|both> <English name>")
                val appliesTo = AppliesTo.entries.firstOrNull { it.wire() == rest[1] } ?: usage("category add <key> <income|expense|both> <English name>")
                // Lao and Thai start as the English name, so nothing shows empty; `category name` sets them.
                val name = rest.drop(2).joinToString(" ")
                val category = categories.add(rest[0], LocalizedName(name, name, name), appliesTo)
                sender.sendMessage("Category '${category.key}' added for ${appliesTo.wire()}; set the Lao and Thai names with: category name ${category.key} <lo|th> <text>")
            }
            "name" -> {
                if (rest.size < 3 || rest[1] !in languages) usage("category name <key> <lo|th|en> <text>")
                val old = categories.find(rest[0])?.name ?: throw notFound("No category '${rest[0]}'.")
                val text = rest.drop(2).joinToString(" ")
                val name = when (rest[1]) {
                    "lo" -> old.copy(lo = text)
                    "th" -> old.copy(th = text)
                    else -> old.copy(en = text)
                }
                categories.update(rest[0], name, null, null)
                sender.sendMessage("Category '${rest[0]}' now has the ${rest[1]} name: $text")
            }
            "list" -> {
                if (categories.all().isEmpty()) sender.sendMessage("No categories yet. Add one with: category add supplies expense Supplies")
                for (c in categories.all()) sender.sendMessage("${c.key}: ${c.name.en} / ${c.name.th} / ${c.name.lo} [${c.appliesTo.wire()}]${if (c.archived) " (archived)" else ""}")
            }
            "archive", "unarchive" -> {
                if (rest.size != 1) usage("category ${args[0]} <key>")
                categories.update(rest[0], null, null, args[0] == "archive")
                sender.sendMessage("Category '${rest[0]}' is ${if (args[0] == "archive") "archived" else "in use again"}")
            }
            else -> usage("category add <key> <income|expense|both> <English name> | name <key> <lo|th|en> <text> | list | archive <key> | unarchive <key>")
        }
    }) { args ->
        when {
            args.size == 1 -> listOf("add", "name", "list", "archive", "unarchive")
            args.size == 2 && args[0] in setOf("name", "archive", "unarchive") -> categories.all().map { it.key }
            args.size == 3 && args[0] == "add" -> AppliesTo.entries.map { it.wire() }
            args.size == 3 && args[0] == "name" -> languages
            else -> emptyList()
        }
    }, "core")

    commands.register(RecordCommand("records", "Show the entries and open days, and the entries whose files are damaged", { sender, _ ->
        records.statusLines().forEach(sender::sendMessage)
        for (session in records.sessions.all().filter { it.closed == null }.sortedBy { it.branch }) {
            sender.sendMessage("Open day: ${session.branch} since ${session.openedAt} (opened by ${session.openedBy.name})")
        }
        for (broken in records.store.brokenEntries()) sender.sendMessage("BROKEN ${broken.id}: ${broken.reason}")
    }) { emptyList() }, "core")

    commands.register(RecordCommand("export", "Write the entries of some dates to exports/<time>.csv: export <from> <to> [branch]", { sender, args ->
        if (args.size !in 2..3) usage("export <from> <to> [branch]")
        val (file, rows) = records.exportCsv(args[0], args[1], args.getOrNull(2))
        sender.sendMessage("Exported $rows rows to $file")
    }) { args -> if (args.size == 3) records.branches.all().map { it.key } else emptyList() }, "core")
}
