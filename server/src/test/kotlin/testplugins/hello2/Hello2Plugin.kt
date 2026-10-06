package testplugins.hello2

import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import xyz.felismp.shoparchive.shared.InfoResponse

/** Registers InfoService at the same priority (10) as HelloPlugin. */
class Hello2Plugin : ShopPlugin() {
    override fun onLoad() {
        context.services.register(InfoService::class.java, object : InfoService {
            override fun info() = InfoResponse("id", "from-plugin-2", "", "1", 1)
        }, 10, "x")
    }
}
