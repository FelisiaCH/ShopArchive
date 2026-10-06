package xyz.felismp.shoparchive.launcher

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Root-relative directories created on first run. Config files arrive later (P03) - directories only. */
private val LAYOUT_DIRECTORIES = listOf(
    "config",
    "user",
    "user/role",
    "record",
    "data",
    "data/devices",
    "data/audit",
    "data/outbox",
    "data/datafix",
    // Where a new entry is built before it is moved into record/ (not tmp/, which is emptied at every start).
    "data/staging",
    "data/migration",
    "cache",
    "certs",
    "plugins",
    "plugins/update",
    "exports",
    "backups",
    "logs",
    "crash-reports",
    "downloads",
    "tmp",
    // start.sh/start.bat point -Djava.io.tmpdir here but do not create it (only the launcher creates
    // directories, after its link checks), and we wipe tmp/ during boot - so it has to exist afterwards,
    // or the JVM has no temp dir at all.
    "tmp/jvm",
    "libraries",
    "versions",
)

/** Files below the root that the launcher locks or hands to the core, besides the layout directories. */
private val LAUNCHER_FILES = listOf("data/session.lock", "data/server-id")

/** The first layout directory or launcher file that [linkProblem] reports, or null. */
fun layoutLinkProblem(realRoot: Path): String? =
    (LAYOUT_DIRECTORIES + LAUNCHER_FILES).firstNotNullOfOrNull { linkProblem(realRoot, it) }

/**
 * A problem message if [relative] below [realRoot] is, or lies behind, a link (symlink or junction, dangling
 * or not), else null. [realRoot] must already be a real path. Takes the deepest part of the path that
 * exists (without following links) and compares its real path with itself, so a link anywhere above it shows
 * up as a difference. Must run before anything is written there, so nothing is created through a link.
 */
fun linkProblem(realRoot: Path, relative: String): String? {
    var existing = realRoot.resolve(relative).normalize()
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return null
    val real = try {
        existing.toRealPath()
    } catch (e: IOException) {
        val target = try { Files.readSymbolicLink(existing).toString() } catch (notASymlink: Exception) { e.message }
        return "$relative in $realRoot is a link that cannot be resolved ($target); ShopArchive allows no links below its root."
    }
    if (real != existing) {
        return "$relative in $realRoot is, or lies behind, a link (it resolves to $real); ShopArchive allows no links below its root."
    }
    return null
}

/** Idempotent: createDirectories never overwrites what's already there. */
fun createRootLayout(root: Path) {
    try {
        for (relative in LAYOUT_DIRECTORIES) {
            Files.createDirectories(root.resolve(relative))
        }
    } catch (e: IOException) {
        // Files.createDirectories throws a raw FileAlreadyExistsException (an IOException) when a
        // *file* already occupies a layout directory's name - report it the same way as any other
        // can't-write-the-root failure instead of letting it surface as a stack trace.
        failCannotWriteRoot(root, e)
    }
}
