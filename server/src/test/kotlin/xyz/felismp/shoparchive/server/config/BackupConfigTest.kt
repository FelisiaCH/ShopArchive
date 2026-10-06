package xyz.felismp.shoparchive.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupConfigTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun loaded(backup: String? = null): ConfigService {
        if (backup != null) root.write("config/shoparchive.yml", "config-version: 1\n\nbackup:\n$backup")
        return ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }
    }

    @Test
    fun theDefaultsAreOnAtThreeKeepFourteenAndNoCopyAndAreWrittenUnderBackup() {
        val backup = loaded().backup

        assertTrue(backup.enabled)
        assertEquals(LocalTime.of(3, 0), backup.time)
        assertEquals(14, backup.keep)
        assertEquals("", backup.copyTo)
        assertEquals(30, backup.pauseTimeoutSeconds)
        assertEquals(emptyList(), log.warnings)

        val text = root.text("config/shoparchive.yml")
        val tail = text.substring(text.indexOf("\nbackup:\n"))
        for (line in listOf("backup:\n", "  enabled: true\n", "  time: 03:00\n", "  keep: 14\n", "  copy-to: \"\"\n", "# Allowed: integer from 1 to 3650. Default: 14", "# Allowed: a time of day as HH:mm, e.g. 03:00. Default: 03:00")) {
            assertTrue(line in tail, "missing ${line.trim()} in:\n$tail")
        }
    }

    @Test
    fun keepBelowOneIsClampedToOneWithAWarning() {
        assertEquals(1, loaded("  keep: 0\n").backup.keep)
        assertEquals(1, log.warningsWith("backup.keep", "outside the allowed range").size, log.warnings.toString())
    }

    @Test
    fun keepAboveTheLimitIsClamped() {
        assertEquals(3650, loaded("  keep: 99999\n").backup.keep)
        assertEquals(1, log.warningsWith("backup.keep").size)
    }

    @Test
    fun thePauseTimeoutIsClampedFromOneToSixHundredSeconds() {
        assertEquals(1, loaded("  pause-timeout-seconds: 0\n").backup.pauseTimeoutSeconds)
        assertEquals(600, loaded("  pause-timeout-seconds: 5000\n").backup.pauseTimeoutSeconds)
        assertEquals(2, log.warningsWith("backup.pause-timeout-seconds", "outside the allowed range").size, log.warnings.toString())
    }

    @Test
    fun aBadTimeFallsBackToTheDefaultWithAWarning() {
        for (bad in listOf("25:00", "3:00", "12:60", "noon", "03:00:00")) {
            log.warnings.clear()
            assertEquals(LocalTime.of(3, 0), loaded("  time: \"$bad\"\n").backup.time, bad)
            assertEquals(1, log.warningsWith("backup.time", "invalid value").size, "$bad: ${log.warnings}")
        }
    }

    @Test
    fun aGoodTimeAndAWindowsPathSurviveARewrite() {
        val backup = loaded("  enabled: false\n  time: \"22:45\"\n  copy-to: \"E:\\\\Backups # one\"\n").backup
        assertFalse(backup.enabled)
        assertEquals(LocalTime.of(22, 45), backup.time)
        assertEquals("E:\\Backups # one", backup.copyTo)

        // Loading again reads what the first load wrote.
        val again = ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }.backup
        assertFalse(again.enabled)
        assertEquals(LocalTime.of(22, 45), again.time)
        assertEquals("E:\\Backups # one", again.copyTo)
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun anUnknownKeyUnderBackupWarns() {
        loaded("  frequency: 2\n")
        assertEquals(1, log.warningsWith("unknown key 'backup.frequency'").size)
    }
}
