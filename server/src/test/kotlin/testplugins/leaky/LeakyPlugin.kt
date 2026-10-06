package testplugins.leaky

import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** Throws an exception that quotes its own secret config value, in the step the system property `testplugins.leak` names (enable, reload or disable). */
class LeakyPlugin : ShopPlugin() {
    private fun leak(step: String) {
        if (System.getProperty("testplugins.leak") == step) throw IllegalStateException("login failed with ${context.config.getString("api-token", "")}")
    }

    override fun onEnable() {
        context.saveDefaultConfig()
        leak("enable")
    }

    override fun onConfigReload() = leak("reload")

    override fun onDisable() = leak("disable")
}
