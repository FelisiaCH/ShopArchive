package testplugins.webload

import xyz.felismp.shoparchive.api.plugin.PluginResponse
import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** Registers a route in onLoad, which is not allowed. */
class WebLoadPlugin : ShopPlugin() {
    override fun onLoad() {
        context.routes.get("/hello") { PluginResponse.ok() }
    }
}
