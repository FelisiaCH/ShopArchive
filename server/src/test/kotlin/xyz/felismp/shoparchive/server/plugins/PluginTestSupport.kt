package xyz.felismp.shoparchive.server.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.relativeTo

/** plugin.yml text; null values leave the key out. */
fun pluginYml(
    name: String?,
    main: String?,
    version: String? = "1.0",
    apiVersion: String? = "1",
    depend: List<String> = emptyList(),
    softdepend: List<String> = emptyList(),
): String = buildString {
    if (name != null) appendLine("name: $name")
    if (version != null) appendLine("version: \"$version\"")
    if (main != null) appendLine("main: $main")
    if (apiVersion != null) appendLine("api-version: $apiVersion")
    if (depend.isNotEmpty()) appendLine("depend: [${depend.joinToString()}]")
    if (softdepend.isNotEmpty()) appendLine("softdepend: [${softdepend.joinToString()}]")
}

/**
 * Packs the already-compiled classes of one fixture package under src/test/kotlin/testplugins (found on the test
 * classpath, so no compiler runs at test time) and the given plugin.yml into [target], and returns [target].
 */
fun buildPluginJar(target: Path, fixturePackage: String, yml: String?, extra: Map<String, ByteArray> = emptyMap()): Path {
    val dir = Path.of(requireNotNull(Thread.currentThread().contextClassLoader.getResource("testplugins/$fixturePackage")) { "no fixture $fixturePackage" }.toURI())
    Files.createDirectories(target.parent)
    JarOutputStream(Files.newOutputStream(target)).use { jar ->
        fun add(name: String, bytes: ByteArray) {
            jar.putNextEntry(JarEntry(name))
            jar.write(bytes)
            jar.closeEntry()
        }
        if (yml != null) add("plugin.yml", yml.toByteArray())
        Files.walk(dir).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.sorted().forEach {
                add("testplugins/$fixturePackage/" + it.relativeTo(dir).toString().replace('\\', '/'), Files.readAllBytes(it))
            }
        }
        extra.forEach { (name, bytes) -> add(name, bytes) }
    }
    return target
}
