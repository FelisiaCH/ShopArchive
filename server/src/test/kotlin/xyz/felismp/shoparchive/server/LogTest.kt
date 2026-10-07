package xyz.felismp.shoparchive.server

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogTest {
    @TempDir
    lateinit var root: Path

    @AfterTest
    fun tearDown() = Log.close()

    private fun latestLog() = Files.readString(root.resolve("logs/latest.log"))

    /** The text of every file below logs/, `.gz` files decompressed, keyed by file name. */
    private fun allLogText(): Map<String, String> =
        Files.list(root.resolve("logs")).use { files ->
            files.toList().associate { file ->
                val bytes = if (file.fileName.toString().endsWith(".gz")) GZIPInputStream(Files.newInputStream(file)).use { it.readBytes() } else Files.readAllBytes(file)
                file.fileName.toString() to String(bytes, Charsets.UTF_8)
            }
        }

    @Test
    fun aMessageBelowTheConfiguredLevelIsNotWritten() {
        Log.start(root, level = "warn")

        Log.debug("debug-line")
        Log.info("info-line")
        Log.warn("warn-line")
        Log.error("error-line")

        val log = latestLog()
        assertTrue("debug-line" !in log && "info-line" !in log, log)
        assertTrue("WARN] warn-line" in log, log)
        assertTrue("ERROR] error-line" in log, log)
    }

    @Test
    fun outputIsWrittenToTheFileOnceWhenTheLevelAllowsInfoAndNotWhenItDoesNot() {
        Log.start(root)
        Log.output("reply-at-info")
        Log.apply("warn", ZoneId.of("Asia/Vientiane"))
        Log.output("reply-at-warn")
        Log.warn("warn-line")

        val log = latestLog()
        assertEquals(1, Regex("INFO] reply-at-info").findAll(log).count(), log)
        assertTrue("reply-at-warn" !in log && "warn-line" in log, log)
    }

    @Test
    fun aThrowableIsWrittenWithItsStackTrace() {
        Log.start(root)

        Log.error("it failed", IllegalStateException("because-reasons"))

        val log = latestLog()
        assertTrue("it failed" in log && "java.lang.IllegalStateException: because-reasons" in log && "\tat " in log, log)
    }

    @Test
    fun aLineHasTheTimeLevelMessageShape() {
        Log.start(root)

        Log.info("shape-check")

        assertTrue(Regex("\\[\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d INFO] shape-check").containsMatchIn(latestLog()), latestLog())
    }

    /** A latest.log left by an earlier run: older than this JVM, so the next [Log.start] rolls it. */
    private fun leaveAnOldRun() {
        val old = root.resolve("logs/latest.log")
        Files.createDirectories(old.parent)
        Files.writeString(old, "old-run")
        // Log4j goes by the file's creation time where the OS keeps one, so set all three.
        val longAgo = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"))
        Files.getFileAttributeView(old, BasicFileAttributeView::class.java).setTimes(longAgo, longAgo, longAgo)
        // Linux reports the real birth time and ignores the set, so an old run cannot be faked there.
        assumeTrue(Files.readAttributes(old, BasicFileAttributes::class.java).creationTime() == longAgo, "creation time cannot be set on this OS")
    }

    @Test
    fun terminalOnlyTextIsInNoFileUnderLogsNotEvenARolledOne() {
        leaveAnOldRun() // makes a .gz exist, so the search below covers rolled files too
        Log.start(root)
        Log.terminalOnly("secret-code-4711")
        Log.info("ordinary-line")
        Log.close()

        val files = allLogText()
        assertTrue(files.keys.any { it.endsWith(".log.gz") }, files.keys.toString())
        assertTrue(files.values.any { "ordinary-line" in it }, "the search must be able to see ordinary lines: $files")
        assertTrue(files.values.none { "secret" in it }, files.toString())
    }

    @Test
    fun aStartRollsThePreviousRunIntoADatedNumberedGzipFile() {
        leaveAnOldRun()
        Log.start(root)
        Log.info("this-run")
        Log.close()

        val files = allLogText()
        val rolled = files.entries.single { it.key != "latest.log" }
        assertTrue(Regex("\\d{4}-\\d\\d-\\d\\d-1\\.log\\.gz").matches(rolled.key), rolled.key)
        assertTrue("old-run" in rolled.value && "this-run" !in rolled.value, rolled.value)
        assertTrue("this-run" in files.getValue("latest.log") && "old-run" !in files.getValue("latest.log"))
    }

    @Test
    fun theFallbackBeforeStartAndAfterCloseDoesNotThrow() {
        Log.info("before-start")
        Log.start(root)
        Log.close()
        Log.info("after-close")
        Log.terminalOnly("after-close-secret")
        Log.apply("debug", ZoneId.of("UTC")) // no-op when not running

        assertTrue("after-close" !in latestLog(), latestLog())
    }
}
