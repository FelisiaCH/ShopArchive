package xyz.felismp.shoparchive.server

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val REPORT_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

internal object CrashReport {
    /**
     * Makes an uncaught exception on any thread leave a trace: logged, written to `crash-reports/`, and the process
     * marked failed so a later `stop` exits 1 (and the service templates' restart-on-failure sees it).
     */
    fun install(root: Path, zone: ZoneId) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.error("Uncaught exception on thread '${thread.name}'", throwable)
            write(root, zone, thread, throwable)
            Shutdown.fail()
        }
    }

    /** Writes `crash-reports/<yyyyMMdd-HHmmss>.txt` (a number is added if that second is taken) and logs where. */
    fun write(root: Path, zone: ZoneId, thread: Thread, throwable: Throwable): Path? {
        val dir = root.resolve("crash-reports")
        val text = StringWriter().also { out ->
            PrintWriter(out).use {
                it.println("ShopArchive crash report")
                it.println("Time: ${LocalDateTime.now(zone)} ($zone)")
                it.println("Thread: ${thread.name}")
                it.println("Version: ${coreVersion()}")
                it.println(runtimeLine())
                it.println()
                throwable.printStackTrace(it)
            }
        }.toString()
        return try {
            Files.createDirectories(dir)
            val stamp = LocalDateTime.now(zone).format(REPORT_STAMP)
            var path = dir.resolve("$stamp.txt")
            var n = 0
            while (Files.exists(path)) path = dir.resolve("$stamp-${++n}.txt")
            writeAtomically(path, text.toByteArray(StandardCharsets.UTF_8))
            Log.error("Crash report written to $path")
            path
        } catch (e: Exception) {
            Log.error("Could not write a crash report: $e")
            null
        }
    }
}
