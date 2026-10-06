package testplugins.reloader

import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Notes the `value` of its config.yml in reloads.txt every time it is told the config was reloaded; throws instead when `boom` is true. */
class ReloaderPlugin : ShopPlugin() {
    override fun onEnable() {
        context.saveDefaultConfig()
    }

    override fun onConfigReload() {
        if (context.config.getBoolean("boom", false)) error("reload exploded")
        Files.writeString(
            context.dataFolder.resolve("reloads.txt"), context.config.getString("value", "none") + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }
}
