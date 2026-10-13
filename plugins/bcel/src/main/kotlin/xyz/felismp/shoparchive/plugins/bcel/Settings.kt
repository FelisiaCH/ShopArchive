package xyz.felismp.shoparchive.plugins.bcel

import xyz.felismp.shoparchive.api.plugin.PluginConfig

/** What the plugin reads from config.yml, after checking. See the comments of the shipped config.yml for each value. */
class BcelSettings(val accounts: List<String>, val timeoutSeconds: Int) {
    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 10
        val TIMEOUT_RANGE = 2..30

        /** Reads the config. A timeout out of range is corrected with a warning; the plugin always works, with no accounts it just does not say whether the shop was paid. */
        fun read(config: PluginConfig, warn: (String) -> Unit): BcelSettings {
            val accounts = config.getStringList("accounts").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val asked = config.getInt("timeout-seconds", DEFAULT_TIMEOUT_SECONDS)
            val timeout = asked.coerceIn(TIMEOUT_RANGE)
            if (timeout != asked) warn("timeout-seconds: $asked is outside ${TIMEOUT_RANGE.first} to ${TIMEOUT_RANGE.last}; using $timeout")
            return BcelSettings(accounts, timeout)
        }
    }
}
