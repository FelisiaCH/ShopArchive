package xyz.felismp.shoparchive.plugins.telegram

import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** Above the core's log-only notifier (0) and below the example Discord channel (20), so a Discord plugin, when installed, takes the messages instead. */
const val TELEGRAM_PRIORITY = 10

/**
 * Sends the server's notifications to Telegram chats. At enable it reads config.yml; if the token or the chats are missing or wrong it warns and
 * registers nothing, so the server keeps using the next channel. `reload Telegram` reads the config again: a channel that was not registered
 * because of the config is registered then; a registered one cannot be taken back by the plugin api, so if the config becomes incomplete it answers "try again"
 * (the messages wait in the outbox) and warns: restart the server, or put the config right, to go on.
 */
class TelegramPlugin : ShopPlugin() {
    @Volatile
    private var settings: TelegramSettings? = null
    private var registered = false

    override fun onEnable() {
        context.saveDefaultConfig()
        refresh(first = true)
    }

    override fun onConfigReload() = refresh(first = false)

    private fun refresh(first: Boolean) {
        settings = TelegramSettings.read(context.config) { context.logger.warn(it) }
        val s = settings
        if (s == null) {
            if (registered) context.logger.warn("the settings are incomplete now; messages wait in the outbox until they are put right (restart the server to hand over to the next channel)")
            else context.logger.warn("Telegram is not used: ${if (first) "the settings are incomplete" else "still incomplete"}. The server sends to the next channel. Fill in plugins/Telegram/config.yml and run: reload Telegram")
            return
        }
        if (!registered) {
            val notifier = TelegramNotifier({ settings }, JdkHttpTransport(), FileSentLedger(context.dataFolder.resolve("pending")), log = { context.logger.debug(it) })
            context.services.register(Notifier::class.java, notifier, TELEGRAM_PRIORITY, context.name)
            registered = true
            context.logger.info("sending ${s.events.joinToString()} to ${s.chatIds.size} chat(s) in ${s.language}")
        } else context.logger.info("settings reloaded")
    }
}
