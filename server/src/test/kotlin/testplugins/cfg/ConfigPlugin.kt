package testplugins.cfg

import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files

/** Reads its config.yml (copied from the jar) and writes what it read to result.txt. */
class ConfigPlugin : ShopPlugin() {
    override fun onEnable() {
        context.saveDefaultConfig()
        val c = context.config
        Files.writeString(
            context.dataFolder.resolve("result.txt"),
            listOf(
                c.getString("greeting", "none"), c.getInt("limits.max", -1), c.getBoolean("flag", false),
                c.getStringList("names"), c.getInt("greeting", -5), c.getString("missing", "dflt"), c.keys("limits"),
            ).joinToString("|"),
        )
    }
}
