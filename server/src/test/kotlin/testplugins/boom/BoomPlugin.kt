package testplugins.boom

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import xyz.felismp.shoparchive.shared.InfoResponse

/** Registers a service and a command in onEnable, then throws. */
class BoomPlugin : ShopPlugin() {
    override fun onEnable() {
        context.services.register(InfoService::class.java, object : InfoService {
            override fun info() = InfoResponse("id", "from-boom", "", "1", 1)
        }, 30, "x")
        context.commands.register(object : Command {
            override val name = "boom"
            override val description = "never usable"
            override fun execute(sender: CommandSender, args: List<String>) = Unit
        }, "x")
        error("enable failed on purpose")
    }
}
