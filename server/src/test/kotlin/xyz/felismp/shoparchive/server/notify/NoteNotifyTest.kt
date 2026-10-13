package xyz.felismp.shoparchive.server.notify

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.records.newUuid7
import xyz.felismp.shoparchive.shared.CreateNoteRequest
import xyz.felismp.shoparchive.shared.NotificationState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A day note becomes a message for the owner: its words, its code, once, and again from the records when the message was lost. */
class NoteNotifyTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String = NO_BACKOFF): AuthEnv = AuthEnv(root, config, withNotify = true).also { e ->
        if (e.records!!.branches.all().isEmpty()) e.records.branches.add("main", "Main Shop")
    }

    @Test
    fun aNewNoteQueuesAMessageWithItsWordsAndAFixedCodeAndASendAgainOfTheSameIdQueuesNoSecond() = env().run {
        val noy = login("noy")
        api {
            val id = newUuid7()
            for (text in listOf("Bl168 held until Friday\nwill collect tonight", "ignored: a resend")) {
                postJson("/api/v1/notes", CreateNoteRequest.serializer(), CreateNoteRequest(id, "main", text), noy)
            }

            val item = notify!!.outbox.items().single()
            val n = item.notification
            assertEquals(NotificationState.QUEUED, item.state)
            assertEquals("note.created", n.event)
            assertEquals("main", n.branch)
            assertEquals("noy", n.user)
            assertEquals("20261003-" + id.takeLast(5).uppercase(), n.code)
            assertEquals(id, item.subject)
            assertEquals(
                mapOf("branch" to "Main Shop", "user" to "noy", "time" to n.fields.getValue("time"), "date" to "2026-10-03", "text" to "Bl168 held until Friday\nwill collect tonight"),
                n.fields,
            )
            assertTrue(n.fields.getValue("time").startsWith("2026-10-03T15:00:00"))
            assertEquals("[${n.code}] Main Shop ${n.fields["time"]}: noy left a note: Bl168 held until Friday\nwill collect tonight", describe(n))
        }
    }

    @Test
    fun noteCreatedLeftOutOfNotifyEventsQueuesNothing() = env(NO_BACKOFF.replace("version: 1", "version: 2") + "notify:\n  events: [day.closed, entry.created]\n").run {
        val noy = login("noy")
        api {
            postJson("/api/v1/notes", CreateNoteRequest.serializer(), CreateNoteRequest(newUuid7(), "main", "hello"), noy)
            assertEquals(emptyList(), notify!!.outbox.items())
        }
    }

    @Test
    fun aNoteThatLostItsMessageGetsOneAtTheNextStartExactlyOnce() {
        val first = env()
        val noy = first.login("noy")
        first.api {
            postJson("/api/v1/notes", CreateNoteRequest.serializer(), CreateNoteRequest(newUuid7(), "main", "keep me"), noy)
        }
        assertEquals(1, first.notify!!.outbox.items().size)
        Files.list(root.resolve("data/outbox")).use { l -> l.filter { it.fileName.toString().endsWith(".yml") }.toList() }.forEach(Files::delete)

        val second = env()
        val items = second.notify!!.outbox.items()

        assertEquals(listOf("note.created"), items.map { it.notification.event })
        assertEquals("keep me", items.single().notification.fields["text"])
        assertEquals(0, second.notify.reconcile())
        assertEquals(1, env().notify!!.outbox.items().size)
    }
}
