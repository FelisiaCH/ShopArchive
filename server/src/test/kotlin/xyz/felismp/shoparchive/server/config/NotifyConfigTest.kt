package xyz.felismp.shoparchive.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotifyConfigTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun loaded(notify: String? = null): NotifySettings {
        if (notify != null) root.write("config/shoparchive.yml", "config-version: 1\n\nnotify:\n$notify")
        return ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }.notify
    }

    @Test
    fun theDefaultsAreThePlansAndAreWrittenUnderNotify() {
        val notify = loaded()

        assertEquals(listOf("day.closed", "device.new", "entry.created"), notify.events)
        assertEquals(30, notify.timeoutSeconds)
        assertEquals(10, notify.maxAttempts)
        assertEquals(30, notify.backoffBaseSeconds)
        assertEquals(3600, notify.backoffMaxSeconds)
        assertEquals(2, notify.reconcileDays)
        val text = root.text("config/shoparchive.yml")
        val tail = text.substring(text.indexOf("\nnotify:\n"))
        for (line in listOf(
            "notify:\n", "  events: [day.closed, device.new, entry.created]\n", "  timeout-seconds: 30\n", "  max-attempts: 10\n", "  backoff:\n",
            "    base-seconds: 30\n", "    max-seconds: 3600\n", "  reconcile-days: 2\n", "# Allowed: integer from 0 to 31. Default: 2", "# Allowed: integer from 1 to 600. Default: 30", "# Allowed: integer from 1 to 1000. Default: 10",
            "# The events that put a message into the outbox for the channel plugin: entry.created, day.closed, device.new.",
        )) assertTrue(line in tail, "missing ${line.trim()} in:\n$tail")
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun valuesOutsideTheRangeAreClampedWithAWarning() {
        val notify = loaded("  timeout-seconds: 0\n  max-attempts: 99999\n  backoff:\n    base-seconds: 99999\n    max-seconds: 999999\n")

        assertEquals(1, notify.timeoutSeconds)
        assertEquals(1000, notify.maxAttempts)
        assertEquals(3600, notify.backoffBaseSeconds)
        assertEquals(86_400, notify.backoffMaxSeconds)
        assertEquals(4, log.warningsWith("outside the allowed range").size, log.warnings.toString())
    }

    @Test
    fun reconcileDaysIsClampedToZeroTo31() {
        assertEquals(31, loaded("  reconcile-days: 400\n").reconcileDays)
        assertEquals(0, loaded("  reconcile-days: -3\n").reconcileDays)
        assertEquals(2, log.warningsWith("outside the allowed range").size)
    }

    @Test
    fun aValueThatIsNotTheRightKindFallsBackToTheDefaultWithAWarning() {
        val notify = loaded("  timeout-seconds: soon\n  max-attempts: lots\n")

        assertEquals(30, notify.timeoutSeconds)
        assertEquals(10, notify.maxAttempts)
        assertEquals(2, log.warningsWith("invalid value").size, log.warnings.toString())
    }

    @Test
    fun anEventNameTheServerDoesNotPublishIsDroppedWithAWarningAndTheOthersStay() {
        val notify = loaded("  events: [day.closed, entry.deleted]\n")

        assertEquals(listOf("day.closed"), notify.events)
        assertEquals(1, log.warningsWith("notify.events", "entry.deleted").size, log.warnings.toString())
        // the rewritten file no longer carries the name
        assertTrue("  events: [day.closed]\n" in root.text("config/shoparchive.yml"))
    }

    @Test
    fun anEmptyEventListTurnsMessagesOffWithoutAWarning() {
        val notify = loaded("  events: []\n")

        assertEquals(emptyList(), notify.events)
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun theLongestBackoffIsNeverBelowTheBase() {
        val notify = loaded("  backoff:\n    base-seconds: 600\n    max-seconds: 60\n")

        assertEquals(600, notify.backoffBaseSeconds)
        assertEquals(600, notify.backoffMaxSeconds)
        assertEquals(1, log.warningsWith("notify.backoff.max-seconds", "below").size, log.warnings.toString())
    }
}
