package testplugins.provider

import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** Offers [Shared] to plugins that depend on it. */
class ProviderPlugin : ShopPlugin()

class Shared {
    fun greeting() = "shared-hello"
}
