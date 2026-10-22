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

    private fun loaded(notify: String? = null, version: Int = 2): NotifySettings {
        if (notify != null) root.write("config/shoparchive.yml", "config-version: $version\n\nnotify:\n$notify")
        return ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }.notify
    }

    @Test
    fun theDefaultsAreThePlansAndAreWrittenUnderNotify() {
        val notify = loaded()

        assertEquals(listOf("day.closed", "device.new", "entry.created", "note.created"), notify.events)
        assertEquals(30, notify.timeoutSeconds)
        assertEquals(60, notify.maxAttempts)
        assertEquals(30, notify.backoffBaseSeconds)
        assertEquals(3600, notify.backoffMaxSeconds)
        assertEquals(2, notify.reconcileDays)
        val text = root.text("config/shoparchive.yml")
        val tail = text.substring(text.indexOf("\nnotify:\n"))
        for (line in listOf(
            "notify:\n", "  events: [day.closed, device.new, entry.created, note.created]\n", "  timeout-seconds: 30\n", "  max-attempts: 60\n", "  backoff:\n",
            "    base-seconds: 30\n", "    max-seconds: 3600\n", "  reconcile-days: 2\n", "# Allowed: integer from 0 to 31. Default: 2", "# Allowed: integer from 1 to 600. Default: 30", "# Allowed: integer from 1 to 1000. Default: 60",
            "# The events that put a message into the outbox for the channel plugin: entry.created, day.closed, device.new, note.created.",
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
        assertEquals(60, notify.maxAttempts)
        assertEquals(2, log.warningsWith("invalid value").size, log.warnings.toString())
    }

    // --- the upgrade of a file written before note.created existed (config-version 1) ---

    @Test
    fun aVersionOneListWithEventsGetsNoteCreatedAndTheFileIsRewrittenAsVersionTwoWithTheOldCopySaved() {
        val notify = loaded("  events: [day.closed, entry.created]\n  max-attempts: 7\n", version = 1)

        assertEquals(listOf("day.closed", "entry.created", "note.created"), notify.events)
        assertEquals(7, notify.maxAttempts)
        val text = root.text("config/shoparchive.yml")
        assertTrue("config-version: 2" in text && "  events: [day.closed, entry.created, note.created]\n" in text && "  max-attempts: 7\n" in text, text)
        assertTrue("# Allowed: integer from 1 to 1000. Default: 60" in text, "the comments come back from the template")
        assertEquals(1, root.backups().size)
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun aVersionOneListThatHasItOrIsEmptyOrIsAbsentIsLeftAsItIs() {
        assertEquals(listOf("note.created", "day.closed"), loaded("  events: [note.created, day.closed]\n", version = 1).events)
        assertEquals(emptyList(), loaded("  events: []\n", version = 1).events)
        assertTrue("  events: []\n" in root.text("config/shoparchive.yml"), "messages the admin turned off stay off")
        assertEquals(listOf("day.closed", "device.new", "entry.created", "note.created"), loaded("  timeout-seconds: 20\n", version = 1).events)
    }

    @Test
    fun theDottedFormOfTheKeyIsUpgradedToo() {
        root.write("config/shoparchive.yml", "config-version: 1\nnotify.events: [entry.created]\n")
        assertEquals(listOf("entry.created", "note.created"), loaded().events)
    }

    @Test
    fun aVersionTwoListIsNotTouched() {
        assertEquals(listOf("day.closed"), loaded("  events: [day.closed]\n").events)
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
