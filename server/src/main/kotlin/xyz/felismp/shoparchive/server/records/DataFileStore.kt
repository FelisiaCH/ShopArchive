package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.server.config.ConfigFile
import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import xyz.felismp.shoparchive.server.config.loadConfigFile
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock

/**
 * One small data file (`data/branches.yml`, `data/categories.yml`) in memory, and the only code that writes it. It is read like a
 * config file: rewritten from the template with the old copy saved under `data/migration/`, and a file that cannot be understood
 * is never replaced. A change made by hand since the last read is loaded first, so a console change goes on top of it.
 */
internal class DataFileStore<V>(
    private val root: Path,
    private val file: ConfigFile<V>,
    private val log: ConfigLog = ConsoleConfigLog,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val barrier: DataBarrier = DataBarrier(),
) {
    @Volatile
    var value: V = file.defaults()
        private set

    private var seen: FileTime? = null
    private val path: Path get() = root.resolve(file.path)

    /** @throws xyz.felismp.shoparchive.server.config.ConfigFileException the file cannot be understood */
    fun load() = barrier.mutate { loadInLock() }

    @Synchronized
    private fun loadInLock() {
        value = loadConfigFile(root, file, log, clock).value
        seen = Files.getLastModifiedTime(path)
    }

    /** Replaces the value by [change] of the value the file has now, and writes it. */
    fun update(change: (V) -> V) = barrier.mutate { updateInLock(change) }

    @Synchronized
    private fun updateInLock(change: (V) -> V) {
        if (runCatching { Files.getLastModifiedTime(path) }.getOrNull() != seen) {
            log.warn("${file.path} was changed by hand since it was read; reading it again before changing it")
            loadInLock()
        }
        val changed = change(value)
        writeAtomically(path, file.render(changed).toByteArray(Charsets.UTF_8))
        value = changed
        seen = Files.getLastModifiedTime(path)
    }
}
