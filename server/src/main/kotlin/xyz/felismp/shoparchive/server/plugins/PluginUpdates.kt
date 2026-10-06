package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.fsyncDirectory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.jar.JarFile

/**
 * The jars in `plugins/update/` -> `plugins/`, done at start before the scan (a plugin change is a restart). The jar
 * with the same plugin name is not deleted: it is moved to `plugins/update/old/<yyyyMMdd-HHmmss>/`. The new jar is a
 * different file, so it needs approval again when `plugins.require-approval` is on.
 */
internal class PluginUpdates(private val pluginsDir: Path, private val clock: Clock) {
    private val updateDir = pluginsDir.resolve("update")

    fun apply() {
        val incoming = jarsIn(updateDir)
        if (incoming.isEmpty()) return
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock.instant())
        val oldDir = updateDir.resolve("old").resolve(stamp)
        for (jar in incoming) {
            val name = pluginName(jar)
            if (name == null) {
                Log.error("plugins/update/${jar.fileName} is not a plugin jar with a readable plugin.yml; left where it is")
                continue
            }
            val target = pluginsDir.resolve(jar.fileName.toString())
            val replaced = (jarsIn(pluginsDir).filter { pluginName(it) == name } + listOfNotNull(target.takeIf { Files.exists(it) }))
                .distinct()
            val movedAside = mutableListOf<Pair<Path, Path>>()
            try {
                for (old in replaced) moveAside(old, oldDir, movedAside)
                move(jar, target)
                fsyncDirectory(pluginsDir)
                Log.info(
                    "Plugin update: $name <- plugins/update/${jar.fileName}" +
                        if (replaced.isEmpty()) " (new plugin)" else "; the old jar is kept in plugins/update/old/$stamp/",
                )
            } catch (e: IOException) {
                // The plugin must not end up uninstalled because its replacement could not be put in place.
                val notRestored = movedAside.asReversed().filter { (old, aside) ->
                    try { move(aside, old); false } catch (restore: IOException) { Log.error("Could not put ${old.fileName} back from $aside (${restore.message})"); true }
                }
                Log.error(
                    "Plugin update of $name failed (${e.message}); " +
                        if (notRestored.isEmpty()) "the old jar was put back" else "the old jar(s) could not all be put back: look in plugins/update/old/$stamp/",
                    e,
                )
            }
        }
    }

    private fun pluginName(jar: Path): String? = try {
        JarFile(jar.toFile()).use { file ->
            val entry = file.getJarEntry("plugin.yml") ?: return null
            if (entry.size > 64 * 1024) return null
            parsePluginYml(file.getInputStream(entry).use { String(it.readNBytes(64 * 1024), Charsets.UTF_8) }).name
        }
    } catch (_: IOException) {
        null
    } catch (_: PluginYmlException) {
        null
    }

    /** Moves [old] into [dir]; the move is noted in [done] as soon as it happened, before the fsync that may still throw, so a rollback knows about it. */
    private fun moveAside(old: Path, dir: Path, done: MutableList<Pair<Path, Path>>) {
        Files.createDirectories(dir)
        var destination = dir.resolve(old.fileName.toString())
        var n = 1
        while (Files.exists(destination)) destination = dir.resolve("${old.fileName}.${n++}")
        move(old, destination)
        done += old to destination
        fsyncDirectory(dir)
    }

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }
}
