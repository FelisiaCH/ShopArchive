package xyz.felismp.shoparchive.server.notify

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.records.createEntry
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.records.newEntry
import xyz.felismp.shoparchive.server.records.openDay
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `notify` command of the console. */
class NotifyConsoleTest {
    @TempDir
    lateinit var root: Path

    private fun env() = AuthEnv(root, NO_BACKOFF + ONE_ATTEMPT, withNotify = true).also { it.records!!.branches.add("main", "Main") }

    @Test
    fun anEmptyOutboxSaysSo() = env().run {
        assertEquals(listOf("The outbox is empty."), console("notify"))
        assertEquals(listOf("No failed messages."), console("notify list failed"))
    }

    @Test
    fun listShowsTheNewestFirstWithStateCodeAndTheReason() = env().run {
        services.register(Notifier::class.java, ScriptedNotifier(DeliveryResult.Sent, DeliveryResult.Failed("bot was removed")), 5, "plugin:telegram")
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000001"))
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000002"))
            notify!!.outbox.processDue()

            val lines = console("notify list")

            assertEquals(2, lines.size)
            assertTrue(" failed entry.created " in lines[0] && lines[0].endsWith("tries=1 - bot was removed"), lines[0])
            assertTrue(" sent entry.created " in lines[1] && lines[1].endsWith("tries=1"), lines[1])
            assertEquals(1, console("notify list failed").size)
            assertEquals(listOf("Usage: notify [list [queued|unknown|failed|sent]] | notify resend <id>"), console("notify list lost"))
        }
    }

    @Test
    fun resendTakesAFullIdOrItsStartAndRefusesAMessageThatIsStillWaiting() = env().run {
        services.register(Notifier::class.java, ScriptedNotifier(DeliveryResult.Failed("no")), 5, "plugin:telegram")
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry(id = "01990000-0000-7000-8000-000000000001"))
            val id = notify!!.outbox.items().single().id
            assertTrue(console("notify resend $id").single().contains("is still queued"))

            notify.outbox.processDue()
            val said = console("notify resend ${id.take(12)}").single()

            assertTrue(said.startsWith("${id.dropLast(3)}002 queued: a repeat of $id (code "), said)
            assertEquals(2, notify.outbox.items().size)
            assertTrue(console("notify resend nope").single().startsWith("No message 'nope'"))
            assertEquals(listOf("Usage: notify [list [queued|unknown|failed|sent]] | notify resend <id>"), console("notify resend"))
        }
    }

    @Test
    fun completionOffersTheSubcommandsAndTheStates() = env().run {
        val command = commands.find("notify")!!
        assertEquals(listOf("list", "resend"), command.complete(listOf("")))
        assertEquals(listOf("failed"), command.complete(listOf("list", "f")))
    }
}
