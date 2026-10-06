package xyz.felismp.shoparchive.server.config

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class RecordingLog : ConfigLog {
    val infos = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun info(msg: String) {
        infos += msg
    }

    override fun warn(msg: String) {
        warnings += msg
    }

    override fun error(msg: String) {
        errors += msg
    }

    /** Warnings that mention every one of [parts]. */
    fun warningsWith(vararg parts: String) = warnings.filter { w -> parts.all { it in w } }
}

/** 2026-10-02 03:04:05 UTC, so backups land in `data/migration/20261002-030405`. */
internal val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-02T03:04:05Z"), ZoneOffset.UTC)
internal const val FIXED_STAMP = "20261002-030405"

/** The directories the launcher creates before the core runs (only the ones config needs). */
internal fun prepareRoot(root: Path) {
    Files.createDirectories(root.resolve("config"))
    Files.createDirectories(root.resolve("data").resolve("migration"))
}

internal fun Path.text(relative: String): String = Files.readString(resolve(relative))

internal fun Path.write(relative: String, text: String) = Files.write(resolve(relative), text.toByteArray())

/** Every regular file below `data/migration`, as root-relative paths with `/`. */
internal fun Path.backups(): List<String> {
    val base = resolve("data").resolve("migration")
    return Files.walk(base).use { paths ->
        paths.filter { Files.isRegularFile(it) }.map<String> { base.relativize(it).toString().replace('\\', '/') }.sorted().toList()
    }
}
