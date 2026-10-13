package xyz.felismp.shoparchive.server.notify

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.NotificationImages
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.records.createEntry
import xyz.felismp.shoparchive.server.records.entries
import xyz.felismp.shoparchive.server.records.entryDir
import xyz.felismp.shoparchive.server.records.closeDay
import xyz.felismp.shoparchive.server.records.jpeg
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.records.newEntry
import xyz.felismp.shoparchive.server.records.online
import xyz.felismp.shoparchive.server.records.openDay
import xyz.felismp.shoparchive.server.records.png
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pictures a channel plugin gets for a message: the slips of the entry the message is about, found through the message's subject. */
class NotificationImagesTest {
    @TempDir
    lateinit var root: Path

    private fun env(): AuthEnv = AuthEnv(root, NO_BACKOFF, withNotify = true).also { it.records!!.branches.add("main", "Main Shop") }

    private fun AuthEnv.images() = services.get(NotificationImages::class.java)!!

    @Test
    fun anEntryWithoutSlipsHasNoImages() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry())
            assertEquals(emptyList(), images().images(notify!!.outbox.items().single().notification).map { it.contentType })
        }
    }

    @Test
    fun theSlipsComeInTheOrderOfTheEntryWithTheirContentType() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            val one = jpeg(1)
            val two = png(2)
            createEntry(noy, newEntry(tenders = listOf(online("THB", "10"))), listOf(one))
            createEntry(noy, newEntry(tenders = listOf(online("THB", "20"))), listOf(one, two))
            val (single, pair) = notify!!.outbox.items().map { it.notification }

            val a = images().images(single)
            assertEquals(listOf("image/jpeg"), a.map { it.contentType })
            assertContentEquals(one, a.single().bytes)
            val b = images().images(pair)
            assertEquals(listOf("image/jpeg", "image/png"), b.map { it.contentType })
            assertContentEquals(one, b[0].bytes)
            assertContentEquals(two, b[1].bytes)
        }
    }

    @Test
    fun aSlipThatCannotBeReadIsLeftOutAndTheRestStay() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            createEntry(noy, newEntry(tenders = listOf(online("THB", "10"))), listOf(jpeg(1), jpeg(2), jpeg(3)))
            Files.delete(root.entryDir(entries(noy).single()).resolve("slip-2.jpg"))

            val got = images().images(notify!!.outbox.items().single().notification)
            assertEquals(2, got.size)
            assertContentEquals(jpeg(1), got[0].bytes)
            assertContentEquals(jpeg(3), got[1].bytes)
        }
    }

    @Test
    fun aMessageOfAnotherEventOrWithoutAnEntryHasNoImagesAndTheOutboxHandsOverTheSubject() = env().run {
        val channel = ScriptedNotifier()
        services.register(Notifier::class.java, channel, 5, "plugin:telegram")
        val noy = login("noy", grant = listOf("shoparchive.day.close"))
        api {
            val day = openDay(noy)
            createEntry(noy, newEntry(tenders = listOf(online("THB", "10"))), listOf(jpeg(1)))
            closeDay(noy, day, mapOf("LAK" to "50000"))
            notify!!.outbox.processDue()

            val (entry, closed) = channel.got
            assertEquals("entry.created", entry.event)
            assertEquals(entries(noy).single().id, entry.subject)
            assertEquals(1, images().images(entry).size)
            assertEquals("day.closed", closed.event)
            assertEquals(day.id, closed.subject)
            assertTrue(images().images(closed).isEmpty())
            assertTrue(images().images(entry.let { xyz.felismp.shoparchive.api.Notification(it.id, it.code, it.event, it.createdAt, it.branch, it.user, it.locale, it.fields) }).isEmpty())
        }
    }
}
