package xyz.felismp.shoparchive.launcher

import xyz.felismp.shoparchive.launcher.hotfix.HotfixOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Stage 1 entry point. Compiles to Java 8 bytecode so a user on Java 8/11/17 gets a readable error
 * instead of UnsupportedClassVersionError. Validates and prepares the server root using only Java 8
 * APIs, then hands off to [Stage2], which extracts and boots the core under its own classloader.
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        checkJavaVersion()

        val parsed = parseArgs(args)
        val requestedRoot = resolveRoot(parsed.root)
        checkUnsupportedRootPath(requestedRoot)

        createRootDirectory(requestedRoot)
        // Everything below uses the real path, so "inside the root" means inside it on disk, not just by name.
        val root = realRootPath(requestedRoot)
        // A mapped drive letter can resolve to a UNC path here, which the lexical check above could not see.
        checkUnsupportedRootPath(root)
        checkScriptTempDir(root)
        checkLayoutLinks(root)
        warnIfNonAsciiRootOnWindows(root)
        checkFileStore(root)
        acquireSessionLock(root)
        wipeTempDir(root.resolve("tmp"))
        createRootLayout(root)
        configureLibraryTempDirs(root)
        warnIfStartedWithoutScript(root)

        System.setProperty("shoparchive.root", root.toString())
        Stage2.boot(root, parsed.coreArgs, HotfixOptions(parsed.noPatches, parsed.ignoreHotfix))
    }

    private fun checkJavaVersion() {
        val raw = System.getProperty("java.specification.version")
        val major = parseJavaMajorVersion(raw)
        if (!isJavaVersionSupported(major)) {
            // major is null when java.specification.version isn't a format we recognize - quote the raw
            // value instead of printing "null", since that's exactly the unexpected case stage 1 must
            // still report readably rather than fail parsing it.
            val found = major?.toString() ?: "unrecognized (java.specification.version=\"$raw\")"
            System.err.println(
                "[ERROR] ShopArchive requires Java 21 or newer; found Java $found " +
                    "at ${System.getProperty("java.home")}"
            )
            exitProcess(1)
        }
    }

    // No filesystem call needed (or wanted - see unsupportedRootPath's doc) and must run before we ever
    // touch the root, so a refused UNC path leaves nothing behind on the admin's NAS or thumb drive.
    private fun checkUnsupportedRootPath(root: Path) {
        val message = unsupportedRootPath(root.toString()) ?: return
        System.err.println("[ERROR] $message")
        exitProcess(1)
    }

    private fun createRootDirectory(root: Path) {
        try {
            Files.createDirectories(root)
        } catch (e: IOException) {
            failCannotWriteRoot(root, e)
        }
    }

    private fun realRootPath(root: Path): Path =
        try {
            root.toRealPath()
        } catch (e: IOException) {
            failCannotWriteRoot(root, e)
        }

    // Runs before acquireSessionLock, the first write below the root, so nothing is written through a link
    // (the lock and server-id files included).
    private fun checkLayoutLinks(root: Path) {
        val message = layoutLinkProblem(root) ?: return
        System.err.println("[ERROR] $message")
        exitProcess(1)
    }

    private fun checkFileStore(root: Path) {
        val store = try {
            Files.getFileStore(root)
        } catch (e: IOException) {
            // Unknown filesystem type shouldn't block startup - only a *known-bad* one does.
            System.err.println("[WARN] could not determine filesystem type for $root (${e.message}); continuing")
            return
        }
        when (val problem = fileStoreProblem(store.type(), store.name(), root.toString())) {
            is Problem.Refuse -> {
                System.err.println("[ERROR] ${problem.message}")
                exitProcess(1)
            }
            is Problem.Warn -> System.err.println("[WARN] ${problem.message}")
            null -> Unit
        }
    }

    private fun configureLibraryTempDirs(root: Path) {
        val tmp = root.resolve("tmp")
        // Always set, even if the user already exported these - root containment is an invariant.
        val subdirs = mapOf(
            "jna.tmpdir" to "jna",
            "jline.tmpdir" to "jline",
            "jansi.tmpdir" to "jansi",
            "io.netty.native.workdir" to "netty",
            "io.netty.tmpdir" to "netty",
        )
        for ((property, subdir) in subdirs) {
            val dir = tmp.resolve(subdir)
            Files.createDirectories(dir)
            System.setProperty(property, dir.toString())
        }
    }

    // Only the script start is held to "no file outside the root"; read-only, so it can run before the lock.
    private fun checkScriptTempDir(root: Path) {
        if (System.getProperty("shoparchive.start-script") == null) return
        val message = startScriptTempDirProblem(Paths.get(System.getProperty("java.io.tmpdir")), root) ?: return
        System.err.println("[ERROR] $message")
        exitProcess(1)
    }

    private fun warnIfStartedWithoutScript(root: Path) {
        if (System.getProperty("shoparchive.start-script") != null) return
        System.err.println(
            "[WARN] started without start.sh/start.bat; the JVM may write files outside the root " +
                "(java.io.tmpdir is currently ${System.getProperty("java.io.tmpdir")}, root is $root)"
        )
    }
}

internal fun failCannotWriteRoot(root: Path, e: Exception): Nothing {
    System.err.println("[ERROR] cannot write to root: $root (${e.message})")
    exitProcess(1)
}
