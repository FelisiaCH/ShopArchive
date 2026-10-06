package testplugins.slow

import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** onDisable takes far longer than any timeout a test sets. */
class SlowPlugin : ShopPlugin() {
    override fun onDisable() {
        Thread.sleep(5_000)
    }
}
