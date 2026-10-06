package testplugins.consumer

import testplugins.provider.Shared
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files

/** Uses a class that only the provider plugin's jar has. */
class ConsumerPlugin : ShopPlugin() {
    override fun onEnable() {
        Files.writeString(context.dataFolder.resolve("result.txt"), Shared().greeting())
    }
}
