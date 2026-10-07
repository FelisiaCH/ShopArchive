package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.RemoteSender
import xyz.felismp.shoparchive.server.users.UserStore

/** `user reset` and `devices`: what the admin does about a lost phone or a forgotten PIN. */
internal class AccountConsole(
    private val users: UserStore,
    private val devices: DeviceStore,
    private val sessions: Sessions,
    private val audit: AuditLog,
    private val barrier: DataBarrier = DataBarrier(),
) {
    /**
     * Takes the user off every device, clears the password and PIN, and ends the access tokens; the user sets a new PIN at their next login.
     * It runs under the account lock, so a login in flight finishes first and is then undone.
     */
    fun reset(sender: CommandSender, name: String) {
        val user = users.user(name)
        // From the app the reset would end the very session it is asked from, and the op's own device with it.
        if (sender is RemoteSender && sender.principal.userId == user.id) {
            sender.sendMessage("Your own account can only be reset on the server console.")
            return
        }
        // The device files and the user file are one change for a backup: it must not hold the devices off and the user still with a PIN.
        val removed = barrier.mutate {
            sessions.withAccount(user.id) {
                val removed = devices.removeAccount(user.id)
                users.resetCredentials(name)
                sessions.revokeAccess(user.id, null)
                removed
            }
        }
        audit.record("account.reset", name, null, "console", "ok,devices=$removed")
        sender.sendMessage("User '$name' reset: off $removed ${if (removed == 1) "device" else "devices"}, password and PIN cleared. They set a new PIN at their next login.")
    }

    /** `user disable`, under the account lock: a login in flight finishes first, and none starts until the account is disabled. */
    fun disable(sender: CommandSender, name: String) {
        val user = users.user(name)
        sessions.withAccount(user.id) {
            users.setEnabled(name, false)
        }
        sender.sendMessage("User '$name' disabled")
    }

    private fun list(sender: CommandSender, name: String) {
        val user = users.find(name) ?: return sender.sendMessage("No user '$name'")
        val found = devices.devicesOf(user.id)
        if (found.isEmpty()) sender.sendMessage("'$name' is on no device")
        for ((id, device) in found) {
            val entry = device.users.values.first { it.userId == user.id }
            sender.sendMessage("$id: ${device.label} (${device.platform}, ${device.mode.name.lowercase()}), last used ${entry.lastUsed}")
        }
    }

    fun register(commands: CommandRegistry) {
        commands.register(object : Command {
            override val name = "devices"
            override val description = "List the devices a user is on: devices <name>"

            override fun complete(args: List<String>) = if (args.size == 1) users.userNames().filter { it.startsWith(args[0]) } else emptyList()

            override fun execute(sender: CommandSender, args: List<String>) {
                if (args.size != 1) return sender.sendMessage("Usage: devices <name>")
                list(sender, args[0])
            }
        }, "core")
    }
}
