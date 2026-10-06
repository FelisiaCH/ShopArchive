package xyz.felismp.shoparchive.server.plugins

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * The only part of the core a plugin can see: the JDK, the Kotlin libraries a plugin is compiled against, and the
 * two modules written for plugins (`shoparchive-api` and `shared`). Everything else - the core's own classes,
 * Ktor, kaml, BouncyCastle, Log4j - answers ClassNotFoundException, so a plugin cannot reach into the server's
 * internals or depend on a library version the server is free to change.
 */
internal class ApiFilterClassLoader(private val core: ClassLoader) : ClassLoader(null) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = load(name)

    /** The class if the core may show it to plugins, else ClassNotFoundException. */
    fun load(name: String): Class<*> {
        if (ALLOWED.none { name.startsWith(it) }) throw ClassNotFoundException(name)
        return core.loadClass(name)
    }

    // No resources either: they would show the plugin files of the core's jars.
    override fun getResource(name: String): URL? = null
    override fun getResources(name: String): Enumeration<URL> = Collections.emptyEnumeration()

    companion object {
        val ALLOWED = listOf(
            "java.", "javax.", "jdk.", "org.w3c.dom.", "org.xml.sax.",
            "kotlin.", "kotlinx.coroutines.", "kotlinx.serialization.",
            "xyz.felismp.shoparchive.api.", "xyz.felismp.shoparchive.shared.",
        )
    }
}

/**
 * One plugin's classes: its own jar, behind the [ApiFilterClassLoader]. The order is the filtered core first (so a
 * plugin cannot replace an api class), then the plugin's own jar, then the jars of the plugins it declared
 * `depend` or `softdepend` on - and only those, so plugins cannot see each other by accident.
 */
internal class PluginClassLoader(
    jar: URL,
    private val filter: ApiFilterClassLoader,
    private val dependencies: List<PluginClassLoader>,
    val pluginName: String,
) : URLClassLoader(arrayOf(jar), filter) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            try {
                return filter.load(name)
            } catch (_: ClassNotFoundException) {
                // not an api class: look in the plugin's own jar next
            }
            findOwn(name)?.let { return it }
            for (dependency in dependencies) dependency.findInPluginOrItsDependencies(name)?.let { return it }
            throw ClassNotFoundException(name)
        }
    }

    /** [name] if the plugin's own jar has it, else null. */
    private fun findOwn(name: String): Class<*>? = try {
        findClass(name)
    } catch (_: ClassNotFoundException) {
        null
    }

    fun findInPluginOrItsDependencies(name: String): Class<*>? {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            findOwn(name)?.let { return it }
            for (dependency in dependencies) dependency.findInPluginOrItsDependencies(name)?.let { return it }
            return null
        }
    }

    /** A file inside the plugin's own jar only (never the core's). */
    fun ownResource(name: String): URL? = findResource(name)

    companion object {
        init {
            registerAsParallelCapable()
        }
    }
}
