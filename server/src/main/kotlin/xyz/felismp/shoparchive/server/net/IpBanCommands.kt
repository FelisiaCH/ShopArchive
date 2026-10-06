package xyz.felismp.shoparchive.server.net

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender

/** `ban-ip` and `pardon-ip`: the bans apply to the next request, no restart. */
internal fun registerIpBanCommands(commands: CommandRegistry, bans: IpBans) {
    commands.register(object : Command {
        override val name = "ban-ip"
        override val description = "Ban an IP address from the server: ban-ip <ip> [reason]"

        override fun execute(sender: CommandSender, args: List<String>) {
            if (args.isEmpty()) return sender.sendMessage("Usage: ban-ip <ip> [reason]")
            val ip = normalizeIp(args[0]) ?: return sender.sendMessage("'${args[0]}' is not an IP address")
            val reason = args.drop(1).joinToString(" ").ifBlank { "Banned by an operator." }
            sender.sendMessage(if (bans.ban(ip, reason, sender.name)) "Banned $ip" else "$ip is already banned")
        }
    }, "core")

    commands.register(object : Command {
        override val name = "pardon-ip"
        override val description = "Lift the ban on an IP address: pardon-ip <ip>"

        override fun complete(args: List<String>) = if (args.size == 1) bans.list().map { it.ip }.filter { it.startsWith(args[0]) } else emptyList()

        override fun execute(sender: CommandSender, args: List<String>) {
            if (args.size != 1) return sender.sendMessage("Usage: pardon-ip <ip>")
            val ip = normalizeIp(args[0]) ?: return sender.sendMessage("'${args[0]}' is not an IP address")
            sender.sendMessage(if (bans.pardon(ip)) "Pardoned $ip" else "$ip is not banned")
        }
    }, "core")
}
