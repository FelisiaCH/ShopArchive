package xyz.felismp.shoparchive.plugins.bcel

import xyz.felismp.shoparchive.api.SlipChecker
import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** Above the core, which registers no [SlipChecker]; the highest one is used, so a second bank plugin needs a priority of its own. */
const val BCEL_PRIORITY = 10

/**
 * Checks BCEL OneProof slips with BCEL. At enable it reads config.yml and registers the [BcelChecker]; `reload BCEL` reads the config again.
 * The check works without any setting: `accounts` only adds whether the shop's account was paid.
 */
class BcelPlugin : ShopPlugin() {
    @Volatile
    private var settings = BcelSettings(emptyList(), BcelSettings.DEFAULT_TIMEOUT_SECONDS)

    override fun onEnable() {
        context.saveDefaultConfig()
        settings = read()
        context.services.register(SlipChecker::class.java, BcelChecker({ settings }, JdkHttpTransport(), log = { context.logger.debug(it) }), BCEL_PRIORITY, context.name)
        context.logger.info("checking BCEL OneProof slips with BCEL; ${describe(settings)}")
    }

    override fun onConfigReload() {
        settings = read()
        context.logger.info("settings reloaded; ${describe(settings)}")
    }

    private fun read() = BcelSettings.read(context.config) { context.logger.warn(it) }

    private fun describe(s: BcelSettings) =
        if (s.accounts.isEmpty()) "no shop account is set, so it does not say whether the shop was paid" else "${s.accounts.size} shop account(s)"
}
