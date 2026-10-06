package testplugins.hello

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import xyz.felismp.shoparchive.shared.InfoResponse
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Overrides InfoService at priority 10, adds a command and a permission node, and notes each lifecycle step in its data folder. */
class HelloPlugin : ShopPlugin() {
    private fun note(text: String) {
        Files.writeString(context.dataFolder.resolve("events.txt"), text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    override fun onLoad() {
        note("load")
        context.permissions.register(PermissionNode("hello.use", "Use the hello plugin", default = true))
        context.services.register(InfoService::class.java, object : InfoService {
            override fun info() = InfoResponse("id", "from-plugin", "", "1", 1)
        }, 10, "someone-else")
    }

    override fun onEnable() {
        note("enable")
        context.commands.register(object : Command {
            override val name = "hello"
            override val description = "Says hello"
            override fun execute(sender: CommandSender, args: List<String>) = sender.sendMessage("hello from plugin")
        }, "someone-else")
    }

    override fun onDisable() { note("disable") }
}
