package testplugins.plain

import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Does nothing, except noting `<name>:<step>` in the file named by the system property testplugins.orderFile (when set). */
class PlainPlugin : ShopPlugin() {
    private fun note(step: String) {
        val file = System.getProperty("testplugins.orderFile") ?: return
        Files.writeString(Path.of(file), "${context.name}:$step\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    override fun onLoad() { note("load") }
    override fun onEnable() { note("enable") }
    override fun onDisable() { note("disable") }
}
