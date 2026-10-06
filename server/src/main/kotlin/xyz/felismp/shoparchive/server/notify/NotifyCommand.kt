package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.shared.NotificationState

private const val USAGE = "Usage: notify [list [queued|unknown|failed|sent]] | notify resend <id>"

/** How many messages `notify list` shows. */
private const val LIST_LINES = 20

/** `notify`: the newest messages of the outbox, and sending one again. The console is the admin, so no permission is asked. */
internal fun registerNotifyCommand(commands: CommandRegistry, outbox: Outbox) {
    commands.register(object : Command {
        override val name = "notify"
        override val description = "Show the outbox of messages to channels (notify list [state]) or send one again (notify resend <id>)"

        override fun complete(args: List<String>): List<String> = when {
            args.size == 1 -> listOf("list", "resend")
            args.size == 2 && args[0] == "list" -> NotificationState.entries.map { it.name.lowercase() }
            args.size == 2 && args[0] == "resend" -> outbox.items().filter { it.state == NotificationState.FAILED || it.state == NotificationState.SENT }.map { it.id }.takeLast(10)
            else -> emptyList()
        }.filter { it.startsWith(args.last()) }

        override fun execute(sender: CommandSender, args: List<String>) {
            when (args.firstOrNull() ?: "list") {
                "list" -> list(sender, args.getOrNull(1))
                "resend" -> resend(sender, args.getOrNull(1))
                else -> sender.sendMessage(USAGE)
            }
        }

        private fun list(sender: CommandSender, word: String?) {
            val state = word?.let { w -> NotificationState.entries.firstOrNull { it.name.lowercase() == w } ?: return sender.sendMessage(USAGE) }
            val items = outbox.items().filter { state == null || it.state == state }.takeLast(LIST_LINES).asReversed()
            if (items.isEmpty()) return sender.sendMessage(if (state == null) "The outbox is empty." else "No ${state.name.lowercase()} messages.")
            for (item in items) {
                val error = item.lastError?.let { " - $it" }.orEmpty()
                sender.sendMessage("${item.id} ${item.state.name.lowercase()} ${item.notification.event} ${item.notification.code} tries=${item.attempts}$error")
            }
        }

        private fun resend(sender: CommandSender, id: String?) {
            if (id == null) return sender.sendMessage(USAGE)
            val item = outbox.find(id) ?: return sender.sendMessage("No message '$id' (or it is the start of more than one id).")
            if (item.state == NotificationState.QUEUED || item.state == NotificationState.UNKNOWN) {
                return sender.sendMessage("${item.id} is still ${item.state.name.lowercase()}: it is sent by itself, send it again only once it has failed.")
            }
            val copy = outbox.resend(item)
            sender.sendMessage("${copy.id} queued: a repeat of ${item.id} (code ${copy.notification.code})")
        }
    }, "core")
}
