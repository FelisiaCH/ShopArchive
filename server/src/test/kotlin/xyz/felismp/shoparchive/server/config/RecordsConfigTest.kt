package xyz.felismp.shoparchive.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordsConfigTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun loaded(records: String? = null): ConfigService {
        if (records != null) root.write("config/shoparchive.yml", "config-version: 1\n\nrecords:\n$records")
        return ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }
    }

    @Test
    fun theDefaultsAreThePlansAndAreWrittenUnderRecords() {
        val records = loaded().records

        assertTrue(records.requireOpenDay)
        assertFalse(records.requireCategory)
        assertEquals(0, records.editWindowDays)
        assertEquals(5, records.slipMaxCount)
        assertEquals(5120, records.slipMaxSizeKb)

        val text = root.text("config/shoparchive.yml")
        val tail = text.substring(text.indexOf("\nrecords:\n"))
        for (line in listOf(
            "records:\n", "  require-open-day: true\n", "  require-category: false\n", "  edit-window-days: 0\n",
            "  slips:\n", "    max-count: 5\n", "    max-size-kb: 5120\n",
            "# Allowed: integer from 0 to 30. Default: 0", "# Allowed: integer from 64 to 51200. Default: 5120",
        )) assertTrue(line in tail, "missing ${line.trim()} in:\n$tail")
    }

    @Test
    fun valuesOutsideTheRangeAreClampedWithAWarning() {
        val records = loaded("  edit-window-days: 99\n  slips:\n    max-count: 50\n    max-size-kb: 1\n").records

        assertEquals(30, records.editWindowDays)
        assertEquals(20, records.slipMaxCount)
        assertEquals(64, records.slipMaxSizeKb)
        assertEquals(3, log.warningsWith("outside the allowed range").size, log.warnings.toString())
    }

    @Test
    fun aValueThatIsNotTheRightKindFallsBackToTheDefaultWithAWarning() {
        val records = loaded("  require-open-day: sometimes\n  edit-window-days: many\n").records

        assertTrue(records.requireOpenDay)
        assertEquals(0, records.editWindowDays)
        assertEquals(2, log.warningsWith("invalid value").size, log.warnings.toString())
    }

    @Test
    fun theRequestLimitForEntriesIsEverySlipAtItsLargestPlusTheNormalLimit() {
        val records = loaded("  slips:\n    max-count: 2\n    max-size-kb: 100\n").records

        assertEquals(2 * 100 * 1024L + 1024 * 1024L, records.entryBodyBytes(1024 * 1024L))
    }

    @Test
    fun anOldFileGainsTheRecordsKeysWithDefaultsAndKeepsItsOwnValues() {
        root.write("config/shoparchive.yml", "config-version: 1\n\nauth:\n  pin:\n    length: 8\n")

        val config = loaded()

        assertEquals(8, config.auth.pinLength)
        assertEquals(5120, config.records.slipMaxSizeKb)
        assertTrue("  require-open-day: true\n" in root.text("config/shoparchive.yml"))
        assertTrue(root.backups().any { it.endsWith("config/shoparchive.yml") }, "the old copy is saved first")
    }
}
