package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.users.isValidUserName
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * `data/audit/yyyy/MM/dd.log` (the day is the day in the configured timezone): one line per security event, appended and
 * flushed to disk at once. A line names the event, the user and device if they are known, the caller's address and
 * the result - never a secret, code, token or hash, so [record] takes none.
 */
internal class AuditLog(
    private val root: Path,
    private val zone: () -> ZoneId,
    private val clock: Clock = Clock.systemUTC(),
    private val barrier: DataBarrier = DataBarrier(),
) {
    private val day = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun record(event: String, username: String?, deviceId: String?, ip: String, result: String) = barrier.mutate { recordInLock(event, username, deviceId, ip, result) }

    @Synchronized
    private fun recordInLock(event: String, username: String?, deviceId: String?, ip: String, result: String) {
        val now = ZonedDateTime.now(clock.withZone(zone()))
        val line = "${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.withNano(0))} $event user=${knownUser(username)} " +
            "device=${knownDevice(deviceId)} ip=${plain(ip)} result=${plain(result)}\n"
        try {
            val file = root.resolve("data/audit/${day.format(now)}.log")
            Files.createDirectories(file.parent)
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { channel ->
                val buffer = ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(false)
            }
        } catch (e: IOException) {
            // The request that caused the event must not fail because the log could not be written; the server log says so.
            Log.error("Could not write the audit log: ${e.message}")
        }
    }

    // What a caller sends can hold a newline or spaces: only values that have the shape of a user name or a device id are written as they are.
    private fun knownUser(name: String?) = name?.takeIf { isValidUserName(it) } ?: "-"
    private fun knownDevice(id: String?) = id?.takeIf { isUuid(it) } ?: "-"
    private fun plain(text: String) = text.map { if (it.isLetterOrDigit() || it in ".:_-=,") it else '_' }.joinToString("").ifEmpty { "-" }
}

internal fun isUuid(text: String): Boolean =
    text.length == 36 && try {
        UUID.fromString(text).toString() == text.lowercase()
    } catch (_: IllegalArgumentException) {
        false
    }
