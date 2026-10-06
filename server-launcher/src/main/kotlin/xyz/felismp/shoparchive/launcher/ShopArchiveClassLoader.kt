package xyz.felismp.shoparchive.launcher

import xyz.felismp.shoparchive.launcher.hotfix.PatchedClass
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.security.CodeSource
import java.security.ProtectionDomain
import java.util.jar.JarFile

/**
 * The classloader the core server runs under. Parented to the platform loader (not the launcher's own
 * app loader) so the core stays isolated from the launcher's classes. Hotfix jars (P13) are among [urls], so
 * their classes live here too, not in a plugin loader.
 *
 * [patched] holds classes whose bytecode a hotfix replaced, already made and checked before the core started;
 * the loader defines those bytes instead of reading the class from its jar. Resources (and `getResourceAsStream`
 * on a class file) still give the original bytes, which is what a patch is pinned to.
 */
class ShopArchiveClassLoader(urls: Array<URL>, parent: ClassLoader, private val patched: Map<String, PatchedClass> = emptyMap()) : URLClassLoader(urls, parent) {
    override fun findClass(name: String): Class<*> {
        val replacement = patched[name] ?: return super.findClass(name)
        val dot = name.lastIndexOf('.')
        if (dot > 0) definePackageOf(name.substring(0, dot), replacement.jar)
        val source = CodeSource(replacement.jar.toURI().toURL(), null as Array<java.security.CodeSigner>?)
        return defineClass(name, replacement.bytes, 0, replacement.bytes.size, ProtectionDomain(source, null))
    }

    // The way URLClassLoader does it for an ordinary class: the package carries the jar's manifest (Implementation-Version ...).
    private fun definePackageOf(pkg: String, jar: File) {
        try {
            val manifest = try { JarFile(jar).use { it.manifest } } catch (e: IOException) { null }
            if (manifest != null) definePackage(pkg, manifest, jar.toURI().toURL())
            else definePackage(pkg, null, null, null, null, null, null, null)
        } catch (e: IllegalArgumentException) {
            // already defined by an earlier class of the package
        }
    }

    companion object {
        init {
            ClassLoader.registerAsParallelCapable()
        }
    }
}
