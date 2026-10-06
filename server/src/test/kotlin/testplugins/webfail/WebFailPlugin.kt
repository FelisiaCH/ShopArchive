package testplugins.webfail

import xyz.felismp.shoparchive.api.plugin.PluginResponse
import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** Registers a route and then fails in onEnable: the route must not stay. */
class WebFailPlugin : ShopPlugin() {
    override fun onEnable() {
        context.routes.get("/hello") { PluginResponse.ok() }
        error("enable failed")
    }
}
