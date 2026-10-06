package testplugins.spy

import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files

/** Tries to load classes it should and should not see, and writes one line per class to result.txt. */
class SpyPlugin : ShopPlugin() {
    override fun onEnable() {
        val names = listOf(
            "xyz.felismp.shoparchive.server.Log",
            "xyz.felismp.shoparchive.server.plugins.PluginManager",
            "io.ktor.server.application.Application",
            "com.charleskorn.kaml.Yaml",
            "org.apache.logging.log4j.LogManager",
            "xyz.felismp.shoparchive.api.ServiceRegistry",
            "xyz.felismp.shoparchive.shared.InfoResponse",
            "kotlin.collections.CollectionsKt",
            "java.util.ArrayList",
        )
        val lines = names.map { name ->
            val seen = try {
                Class.forName(name, false, javaClass.classLoader)
                "visible"
            } catch (e: ClassNotFoundException) {
                "hidden"
            }
            "$name=$seen"
        }
        Files.write(context.dataFolder.resolve("result.txt"), lines)
    }
}
