package xyz.felismp.shoparchive.server.notify

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.TestClock
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.deletePath
import xyz.felismp.shoparchive.server.records.closeDay
import xyz.felismp.shoparchive.server.records.createEntry
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.records.newEntry
import xyz.felismp.shoparchive.server.records.openDay
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A record whose message was lost between the save and the queue (a crash, a failed write) gets one when the server starts again. */
class NotifyReconcileTest {
    @TempDir
    lateinit var root: Path

    private fun env(clock: TestClock = TestClock(), config: String = NO_BACKOFF, withNotify: Boolean = true): AuthEnv =
        AuthEnv(root, config, clock = clock, withRecords = true, withNotify = withNotify).also { e ->
            if (e.records!!.branches.all().isEmpty()) e.records.branches.add("main", "Main")
        }

    private fun forgetMessages() = Files.list(root.resolve("data/outbox")).use { l -> l.filter { it.fileName.toString().endsWith(".yml") }.toList() }.forEach(Files::delete)

    @Test
    fun aRecordThatLostItsMessageGetsOneAtTheNextStartExactlyOnce() {
        val first = env()
        val noy = first.login("noy", grant = listOf("shoparchive.day.close"))
        first.api {
            val session = openDay(noy)
            createEntry(noy, newEntry())
            closeDay(noy, session, mapOf("LAK" to "200000"))
        }
        assertEquals(2, first.notify!!.outbox.items().size)
        forgetMessages() // as if the server had stopped before the messages were written

        val second = env()
        val items = second.notify!!.outbox.items()

        assertEquals(setOf("entry.created", "day.closed"), items.map { it.notification.event }.toSet())
        assertEquals(2, items.size)
        assertTrue(items.all { it.subject != null })
        // and a third start finds nothing missing
        assertEquals(0, second.notify.reconcile())
        assertEquals(2, env().notify!!.outbox.items().size)
    }

    @Test
    fun aRecordThatHasAMessageOfAnyStateIsNotQueuedAgain() {
        val first = env()
        val noy = first.login("noy")
        first.api {
            openDay(noy)
            createEntry(noy, newEntry())
        }
        val item = first.notify!!.outbox.items().single()
        first.notify.outbox.processDue()
        first.notify.outbox.resend(first.notify.outbox.find(item.id)!!)

        val restarted = env()

        assertEquals(0, restarted.notify!!.reconcile())
        assertEquals(2, restarted.notify.outbox.items().size, "the message and its repeat")
    }

    @Test
    fun aRecordMadeInTheSameSecondAsTheFirstStartIsStillReconciled() {
        // records keep whole seconds; a watermark of 08:00:00.500 would put an entry stamped 08:00:00 before it
        val clock = TestClock(Instant.parse("2026-10-04T08:00:00.500Z"))
        val first = env(clock)
        assertEquals(Instant.parse("2026-10-04T08:00:00Z"), first.notify!!.outbox.since)
        val noy = first.login("noy")
        first.api {
            openDay(noy)
            createEntry(noy, newEntry())
        }
        forgetMessages()

        assertEquals(1, env(clock).notify!!.outbox.items().size)
    }

    @Test
    fun theFirstStartAfterAnUpgradeDoesNotSendTheHistory() {
        // records made when the server had no notifications
        val before = env(withNotify = false)
        val noy = before.login("noy", grant = listOf("shoparchive.day.close"))
        before.api {
            val session = openDay(noy)
            createEntry(noy, newEntry())
            closeDay(noy, session, mapOf("LAK" to "200000"))
        }

        val upgraded = env(TestClock(Instant.parse("2026-10-04T08:00:00Z")))

        assertEquals(emptyList(), upgraded.notify!!.outbox.items())
        assertEquals(Instant.parse("2026-10-04T08:00:00Z"), upgraded.notify.outbox.since)
        // a record made after that does get its message, and a restart keeps the same watermark
        val noy2 = upgraded.login("noy")
        upgraded.api {
            openDay(noy2)
            createEntry(noy2, newEntry())
        }
        forgetMessages()
        val restarted = env(TestClock(Instant.parse("2026-10-04T09:00:00Z")))
        assertEquals(Instant.parse("2026-10-04T08:00:00Z"), restarted.notify!!.outbox.since)
        assertEquals(1, restarted.notify.outbox.items().size)
    }

    @Test
    fun recordsOlderThanReconcileDaysAreLeftAlone() {
        val first = env()
        val noy = first.login("noy")
        first.api {
            openDay(noy)
            createEntry(noy, newEntry())
        }
        forgetMessages()

        val late = env(TestClock(Instant.parse("2026-10-13T08:00:00Z")))

        assertEquals(emptyList(), late.notify!!.outbox.items(), "ten days later, with the default of two")
    }

    @Test
    fun reconcileDaysZeroTurnsItOff() {
        val first = env()
        val noy = first.login("noy")
        first.api {
            openDay(noy)
            createEntry(noy, newEntry())
        }
        forgetMessages()

        val off = env(config = NO_BACKOFF + "notify:\n  reconcile-days: 0\n")

        assertEquals(emptyList(), off.notify!!.outbox.items())
    }

    @Test
    fun onlyTheEventsListedInNotifyEventsAreReconciledAndDeletedEntriesAreSkipped() {
        val first = env()
        val noy = first.login("noy", grant = listOf("shoparchive.day.close", "shoparchive.entry.delete.all"))
        first.api {
            val session = openDay(noy)
            createEntry(noy, newEntry(item = "coffee"))
            createEntry(noy, newEntry(item = "mistake"))
            val mistake = first.records!!.store.all().first { it.item == "mistake" }
            assertEquals(200, deletePath("/api/v1/entries/${mistake.date}/${mistake.id}", noy).status.value)
            closeDay(noy, session, mapOf("LAK" to "200000"))
        }
        forgetMessages()

        val only = env(config = NO_BACKOFF + "notify:\n  events: [entry.created]\n")

        assertEquals(listOf("coffee"), only.notify!!.outbox.items().map { it.notification.fields["item"] })
    }
}
