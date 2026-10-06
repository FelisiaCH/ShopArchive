package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.api.plugin.PluginLogger
import xyz.felismp.shoparchive.server.fsyncDirectory
import xyz.felismp.shoparchive.server.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * One-time fixes of the server's own data files by a plugin (`PluginContext.dataFix`). The data is the owner's, so a fix
 * never touches a file before the original is safe: every listed file that exists is first copied to
 * `data/datafix/<plugin>-<id>/` (same path below it as below the root, fsynced), then the plugin's fix runs, and only when
 * it returns is the marker `done` written there (atomically). The marker is what stops the next start from running the fix again.
 *
 * If the fix throws there is no marker and the copies stay, so the admin can put the originals back by copying that folder's
 * `data/` and `record/` over the root; the plugin fails to load (the next start tries again, and keeps the copy made
 * by the first try: a half-fixed file must never replace the original in the copy).
 *
 * Files are limited to [ROOTS] (the core's data: `data/` and `record/`), never `data/datafix/` itself; a path that
 * leaves them by `..`, by being absolute or by a symbolic link is refused before anything is copied.
 */
internal class DataFixes(root: Path, private val clock: Clock = Clock.systemUTC()) {
    private val base: Path = root.toAbsolutePath().normalize()

    /** True if the fix ran now, false if its marker said it ran before. */
    fun run(plugin: String, id: String, files: List<String>, logger: PluginLogger, fix: (List<Path>) -> Unit): Boolean {
        require(ID.matches(id)) { "data fix id '$id' must be 1 to 64 characters of letters, digits, . _ - and start with a letter or digit" }
        require(files.isNotEmpty()) { "data fix '$id' lists no files" }
        val paths = files.map(::resolve)
        val folder = base.resolve("$DATAFIX/$plugin-$id")
        val marker = folder.resolve(MARKER)
        if (Files.exists(marker)) {
            logger.debug("data fix '$id' was done before ($marker); skipped")
            return false
        }

        // Everything is checked before anything is written: a refused fix leaves no trace.
        checkedFolder(folder, paths.map { folder.resolve(base.relativize(it).toString()) } + listOf(marker)) // (a Path is an Iterable of its names: `+ marker` would add those)
        Files.createDirectories(folder)
        for (path in paths) {
            if (!Files.exists(path)) continue
            val copy = folder.resolve(base.relativize(path).toString())
            if (Files.exists(copy)) {
                logger.warn("data fix '$id': ${base.relativize(copy)} exists from an earlier try; keeping it, it is the original")
                continue
            }
            Files.createDirectories(copy.parent)
            writeAtomically(copy, Files.readAllBytes(path))
        }
        fsyncDirectory(folder)

        try {
            fix(paths)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            logger.error("data fix '$id' failed: ${e.javaClass.simpleName}: ${e.message}; copies of the original files are in ${base.relativize(folder)}", e)
            throw IllegalStateException("data fix '$id' failed (${e.javaClass.simpleName}: ${e.message}); the original files are copied in ${base.relativize(folder)}", e)
        }

        writeAtomically(marker, buildString {
            appendLine("# Written when the data fix finished. Delete this file to run the fix again at the next start.")
            appendLine("plugin: $plugin")
            appendLine("id: $id")
            appendLine("done-at: ${Instant.now(clock)}")
            appendLine("files:")
            paths.forEach { appendLine("  - ${base.relativize(it).toString().replace('\\', '/')}") }
        }.toByteArray(Charsets.UTF_8))
        logger.info("data fix '$id' done on ${paths.size} file(s); the originals are in ${base.relativize(folder)}")
        return true
    }

    /** The real (link-free) location of [path], also when it does not exist yet: the closest existing parent made real plus the rest. */
    private fun realOf(path: Path): Path {
        var existing: Path = path
        while (!Files.exists(existing)) existing = existing.parent
        return existing.toRealPath().resolve(existing.relativize(path)).normalize()
    }

    /** Where the data folders really are: below the real server root, whatever links `data` or `record` are (a link out of the root makes them unusable, not wider). */
    private val realBase: Path = base.toRealPath()
    private val realRoots = ROOTS.map { realBase.resolve(it) }
    private val realDatafix = realBase.resolve(DATAFIX)

    /** The absolute path of [relative], if it is below one of the [ROOTS] and not in `data/datafix/`, by its name and by where it really is after following links. */
    private fun resolve(relative: String): Path {
        val given = Path.of(relative)
        require(!given.isAbsolute && !relative.startsWith("/") && !relative.startsWith("\\")) { "data fix path '$relative' must be relative to the server root" }
        val path = base.resolve(given).normalize()
        require(ROOTS.any { path.startsWith(base.resolve(it)) }) { "data fix path '$relative' is not inside ${ROOTS.joinToString(" or ") { "$it/" }}" }
        require(!path.startsWith(base.resolve(DATAFIX))) { "data fix path '$relative' is inside $DATAFIX/, where the copies are kept" }
        val real = realOf(path)
        require(realRoots.any { real.startsWith(it) }) { "data fix path '$relative' leads outside ${ROOTS.joinToString(" and ") { "$it/" }} (a symbolic link?)" }
        require(!real.startsWith(realDatafix)) { "data fix path '$relative' leads into $DATAFIX/, where the copies are kept (a symbolic link?)" }
        require(!Files.isDirectory(path)) { "data fix path '$relative' is a folder; list the files in it" }
        return path
    }

    /** The folder for the copies and the marker: it must really be below `data/datafix/` of the real root, not somewhere a link leads. */
    private fun checkedFolder(folder: Path, copies: List<Path>) {
        val realFolder = realOf(folder)
        require(realFolder.startsWith(realDatafix) && realFolder != realDatafix) { "$DATAFIX/ leads outside the server's data folder (a symbolic link?); nothing was copied" }
        copies.forEach { require(realOf(it).startsWith(realFolder)) { "${base.relativize(it)} leads outside ${base.relativize(folder)} (a symbolic link?); nothing was copied" } }
    }

    companion object {
        const val MARKER = "done"
        private const val DATAFIX = "data/datafix"
        val ROOTS = listOf("data", "record")
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
