package xyz.felismp.shoparchive.plugins.template

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.plugin.PluginRequest
import xyz.felismp.shoparchive.api.plugin.PluginResponse
import xyz.felismp.shoparchive.api.plugin.ShopPlugin

private const val GREET_NODE = "template.greet"

/**
 * The smallest plugin that uses each part of the plugin api once: a config with defaults, a permission node, a console
 * command, an HTTP route for signed-in users and a config reload. Copy it and keep what you need.
 */
class TemplatePlugin : ShopPlugin() {
    private var greeting = "Hello"
    private var shout = false

    /** Before the server reads the user files: a permission node registered now reaches permissions.txt and every user and role file. */
    override fun onLoad() {
        context.permissions.register(PermissionNode(GREET_NODE, "Call the Template plugin's greeting endpoint", default = true))
    }

    /** After the core is built, before the network starts: the place for commands and routes. */
    override fun onEnable() {
        context.saveDefaultConfig()
        readConfig()

        context.commands.register(object : Command {
            override val name = "template"
            override val description = "Template plugin: print its greeting"
            override fun execute(sender: CommandSender, args: List<String>) = sender.sendMessage(message(args.firstOrNull() ?: sender.name))
        }, context.name)

        // GET /api/v1/x/Template/greet?name=Noy - the caller is signed in; whether they may do this is for the plugin to ask.
        context.routes.get("/greet") { request -> greet(request) }
        context.logger.info("enabled")
    }

    /** `reload Template` (or a plain `reload`): the config was just read again. */
    override fun onConfigReload() {
        readConfig()
        context.logger.info("greeting is now '$greeting'")
    }

    private fun readConfig() {
        greeting = context.config.getString("greeting", "Hello")
        shout = context.config.getBoolean("shout", false)
    }

    private fun message(name: String) = "$greeting, $name".let { if (shout) it.uppercase() else it }

    private fun greet(request: PluginRequest): PluginResponse {
        if (!request.hasPermission(GREET_NODE)) return PluginResponse(403, """{"error":"This needs the permission $GREET_NODE."}""")
        return PluginResponse.ok("""{"message":"${json(message(request.queryParam("name") ?: request.userName))}"}""")
    }

    private fun json(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
