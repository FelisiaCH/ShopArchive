package xyz.felismp.shoparchive.plugins.discord

import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** Above Telegram (10) and the core's log (0): with this plugin installed the messages go to Discord and not to Telegram. */
const val DISCORD_PRIORITY = 20

/** An example channel plugin: reads config.yml, registers nothing (and warns) when the webhook is missing, so the next channel is used. `reload ExampleDiscord` can still register it later; it cannot be taken back without a restart. */
class DiscordPlugin : ShopPlugin() {
    @Volatile
    private var settings: DiscordSettings? = null
    private var registered = false

    override fun onEnable() {
        context.saveDefaultConfig()
        refresh()
    }

    override fun onConfigReload() = refresh()

    private fun refresh() {
        settings = DiscordSettings.read(context.config) { context.logger.warn(it) }
        if (settings == null) {
            context.logger.warn(if (registered) "the settings are incomplete now; messages wait in the outbox (restart the server to hand over to the next channel)" else "Discord is not used: the settings are incomplete. The server sends to the next channel.")
            return
        }
        if (!registered) {
            context.services.register(Notifier::class.java, DiscordNotifier({ settings }, JdkHttpTransport()), DISCORD_PRIORITY, context.name)
            registered = true
            context.logger.info("sending to Discord")
        }
    }
}
