package xyz.felismp.shoparchive.launcher

import xyz.felismp.shoparchive.launcher.hotfix.HOTFIX_REPORT_PROPERTY
import xyz.felismp.shoparchive.launcher.hotfix.HotfixFatal
import xyz.felismp.shoparchive.launcher.hotfix.HotfixOptions
import xyz.felismp.shoparchive.launcher.hotfix.planHotfixes
import xyz.felismp.shoparchive.launcher.hotfix.readCoreBuild
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.system.exitProcess

private const val FILES_LIST_RESOURCE = "/META-INF/shoparchive/files.list"
private const val CORE_MAIN_CLASS = "xyz.felismp.shoparchive.server.MainKt"

/** Stage 2: extract the bundled core + libraries, build ShopArchive's own classloader, start the core. */
object Stage2 {
    fun boot(root: Path, coreArgs: Array<String>, hotfixOptions: HotfixOptions = HotfixOptions(false, emptySet())) {
        val entries = readFilesList()
        val written = try {
            extractPayload(root, entries) { resourcePath -> Stage2::class.java.getResourceAsStream(resourcePath) }
        } catch (e: PayloadException) {
            System.err.println("[ERROR] ${e.message}")
            exitProcess(1)
        }
        if (written.isNotEmpty()) {
            // Real information on a first run or an upgrade: confirms something actually landed on disk.
            println("[INFO] extracted ${written.size} file(s) into $root")
        }

        val coreJars = entries.map { root.resolve(it.target).toFile() }
        // The core is loaded only after the hotfix jars are read: what is patched is decided here, before any of its classes exists.
        val plan = try {
            planHotfixes(root, coreJars, readCoreBuild(coreJars), hotfixOptions)
        } catch (e: HotfixFatal) {
            System.err.println("[ERROR] ShopArchive will not start with a hotfix problem:\n${e.message}")
            exitProcess(1)
        }
        System.setProperty(HOTFIX_REPORT_PROPERTY, plan.encodedReport())
        val classpath = (coreJars + plan.extraJars).map { it.toURI().toURL() }.toTypedArray()
        // getPlatformClassLoader() is a Java 9+ API, called reflectively because this module compiles
        // to Java 8 bytecode - but it only ever runs on the Java 21+ JVM stage 1 already required.
        val platformLoader = ClassLoader::class.java.getMethod("getPlatformClassLoader").invoke(null) as ClassLoader
        val loader = ShopArchiveClassLoader(classpath, platformLoader, plan.patched)
        Thread.currentThread().contextClassLoader = loader

        try {
            val mainClass = Class.forName(CORE_MAIN_CLASS, true, loader)
            val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, coreArgs)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            System.err.println("[ERROR] ShopArchive core failed to start: ${cause.message}")
            throw cause
        } catch (e: ReflectiveOperationException) {
            // Class.forName/getMethod failing (ClassNotFoundException, NoSuchMethodException, ...) means
            // the extracted payload itself is broken, not that the core threw - a different message so
            // an admin doesn't chase a core bug that isn't there.
            System.err.println("[ERROR] shoparchive-server.jar's payload is incomplete (${e.message})")
            exitProcess(1)
        }
    }

    private fun readFilesList(): List<PayloadEntry> {
        val stream = Stage2::class.java.getResourceAsStream(FILES_LIST_RESOURCE)
        if (stream == null) {
            System.err.println("[ERROR] missing $FILES_LIST_RESOURCE inside the launcher jar")
            exitProcess(1)
        }
        return stream.use { parseFilesList(it) }
    }
}
