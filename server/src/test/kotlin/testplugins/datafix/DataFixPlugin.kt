package testplugins.datafix

import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Does a data fix in onLoad as the system property `testplugins.datafix` says: `ok` appends "-fixed" to the existing files,
 * `boom` does that and then throws, `escape=<path>` asks for a fix on that path, `late` asks in onEnable. What happened is written to result.txt.
 */
class DataFixPlugin : ShopPlugin() {
    private val mode get() = System.getProperty("testplugins.datafix").orEmpty()

    private fun result(text: String) = Files.writeString(context.dataFolder.resolve("result.txt"), text)

    private fun attempt(files: List<String>, boom: Boolean = false) {
        try {
            val ran = context.dataFix("fix1", files) { paths ->
                Files.writeString(context.dataFolder.resolve("runs.txt"), "run\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                paths.filter { Files.exists(it) }.forEach { Files.writeString(it, Files.readString(it) + "-fixed") }
                if (boom) error("fix exploded")
            }
            result("ran=$ran")
        } catch (e: IllegalArgumentException) {
            result("refused: ${e.message}")
        } catch (e: IllegalStateException) {
            result("state: ${e.message}")
            if (boom || mode == "boom") throw e
        }
    }

    override fun onLoad() {
        when {
            mode == "ok" -> attempt(listOf("data/a.txt", "record/b.txt", "data/missing.txt"))
            mode == "boom" -> attempt(listOf("data/a.txt"), boom = true)
            mode.startsWith("escape=") -> attempt(listOf(mode.removePrefix("escape=")))
        }
    }

    override fun onEnable() {
        if (mode == "late") attempt(listOf("data/a.txt"))
    }
}
