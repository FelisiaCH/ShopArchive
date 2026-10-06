package testplugins.loadboom

import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import xyz.felismp.shoparchive.shared.InfoResponse

/** Registers a node and a service in onLoad, then throws. */
class LoadBoomPlugin : ShopPlugin() {
    override fun onLoad() {
        context.permissions.register(PermissionNode("loadboom.use", "never kept", default = false))
        context.services.register(InfoService::class.java, object : InfoService {
            override fun info() = InfoResponse("id", "from-loadboom", "", "1", 1)
        }, 30, "x")
        error("load failed on purpose")
    }
}
