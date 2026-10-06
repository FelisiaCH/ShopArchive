package xyz.felismp.shoparchive.server.config

import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.fsyncDirectory
import xyz.felismp.shoparchive.server.writeAtomically
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Where config loading reports to; a seam so tests can read the warnings without parsing stdout. */
internal interface ConfigLog {
    fun info(msg: String)
    fun warn(msg: String)
    fun error(msg: String)
}

internal object ConsoleConfigLog : ConfigLog {
    override fun info(msg: String) = Log.info(msg)
    override fun warn(msg: String) = Log.warn(msg)
    override fun error(msg: String) = Log.error(msg)
}

internal enum class FileState { CREATED, UNCHANGED, REWRITTEN }

/** [backup] is the root-relative copy of the old file, only set when [state] is REWRITTEN. */
internal class Loaded<V>(val value: V, val state: FileState, val backup: String?)

private val BACKUP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * Loads [file] below [root]: reads it (a missing file is created with defaults), and if the template
 * output differs from the bytes on disk, saves the old bytes under `data/migration/<timestamp>/` and then
 * rewrites the file. A file that cannot be understood is never replaced - [ConfigFileException] instead.
 */
internal fun <V> loadConfigFile(root: Path, file: ConfigFile<V>, log: ConfigLog, clock: Clock = Clock.systemDefaultZone()): Loaded<V> {
    val target = root.resolve(file.path)
    try {
        if (!Files.exists(target)) {
            val value = file.defaults()
            writeAtomically(target, file.render(value).toByteArray(StandardCharsets.UTF_8))
            log.info("${file.path}: created with defaults")
            return Loaded(value, FileState.CREATED, null)
        }
        val onDisk = Files.readAllBytes(target)
        val value = file.parse(decodeUtf8(file.path, onDisk)) { log.warn("${file.path}: $it") }
        val rendered = file.render(value).toByteArray(StandardCharsets.UTF_8)
        if (rendered.contentEquals(onDisk)) {
            log.info("${file.path}: unchanged")
            return Loaded(value, FileState.UNCHANGED, null)
        }
        val backup = backUp(root, file.path, onDisk, clock)
        writeAtomically(target, rendered)
        log.info("${file.path}: rewritten (previous copy: $backup)")
        return Loaded(value, FileState.REWRITTEN, backup)
    } catch (e: IOException) {
        throw ConfigFileException("${file.path}: ${e.javaClass.simpleName}: ${e.message}")
    }
}

/** What [peekConfigFile] found: [clean] is true when [loadConfigFile] would have left the file as it is. */
internal class Peeked<V>(val value: V, val clean: Boolean)

/**
 * Reads [file] like [loadConfigFile] but never writes anything: no file is created, no migration copy made, nothing rewritten.
 * For a read of already-loaded state that must not touch business files (and so must not take the data barrier); whoever
 * wants the file normalized calls [loadConfigFile] inside [xyz.felismp.shoparchive.server.DataBarrier.mutate].
 * @throws ConfigFileException the file is missing, cannot be read or cannot be understood
 */
internal fun <V> peekConfigFile(root: Path, file: ConfigFile<V>, log: ConfigLog): Peeked<V> {
    val target = root.resolve(file.path)
    try {
        val onDisk = Files.readAllBytes(target)
        val value = file.parse(decodeUtf8(file.path, onDisk)) { log.warn("${file.path}: $it") }
        return Peeked(value, file.render(value).toByteArray(StandardCharsets.UTF_8).contentEquals(onDisk))
    } catch (e: IOException) {
        throw ConfigFileException("${file.path}: ${e.javaClass.simpleName}: ${e.message}")
    }
}

/** Strict: a byte that is not valid UTF-8 is an error, not a U+FFFD that would be written back. A leading BOM is dropped. */
private fun decodeUtf8(name: String, bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
    } catch (e: CharacterCodingException) {
        throw ConfigFileException("$name cannot be read: it is not valid UTF-8 (${e.message})")
    }

/**
 * Copies [bytes] to `data/migration/<yyyyMMdd-HHmmss>/<relative>` and returns that root-relative path.
 * An existing backup is never overwritten: if the name is taken (same second), `-1`, `-2`, ... is added to
 * the directory name. Files saved in the same second land side by side in one directory.
 */
internal fun backUp(root: Path, relative: String, bytes: ByteArray, clock: Clock): String {
    val stamp = BACKUP_STAMP.format(LocalDateTime.now(clock))
    val migrationRoot = root.resolve("data").resolve("migration")
    var attempt = 0
    while (true) {
        val dirName = if (attempt == 0) stamp else "$stamp-$attempt"
        val destination = migrationRoot.resolve(dirName).resolve(relative)
        if (!Files.exists(destination)) {
            Files.createDirectories(destination.parent)
            writeAtomically(destination, bytes)
            // writeAtomically synced the destination's own directory; the new directories above it need the same.
            var dir: Path? = destination.parent.parent
            while (dir != null && dir.startsWith(migrationRoot)) {
                fsyncDirectory(dir)
                dir = dir.parent
            }
            return "data/migration/$dirName/$relative"
        }
        attempt++
    }
}
