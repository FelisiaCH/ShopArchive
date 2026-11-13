package xyz.felismp.shoparchive.server.notify

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.EntryChangedEvent
import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.plugin.PLUGIN_API_VERSION
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.deletePath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.server.records.createEntry
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.records.newEntry
import xyz.felismp.shoparchive.server.records.openDay
import xyz.felismp.shoparchive.server.records.updateEntry
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.MoveEntryRequest
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** entry.edited, entry.deleted and entry.moved: published once after a real change, and queued as messages when `notify.events` lists them. */
class EntryChangeNotifyTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String = NO_BACKOFF): AuthEnv = AuthEnv(root, config, withNotify = true).also { e ->
        e.records!!.branches.add("main", "Main Shop")
        e.records.categories.add("drinks", LocalizedName("ເຄື່ອງດື່ມ", "เครื่องดื่ม", "Drinks"), AppliesTo.BOTH)
        e.records.categories.add("food", LocalizedName("ອາຫານ", "อาหาร", "Food"), AppliesTo.BOTH)
    }

    private fun AuthEnv.listen(type: String): List<EntryChangedEvent> = mutableListOf<EntryChangedEvent>().also { seen ->
        events.subscribe(type, ShopEventListener { seen += it as EntryChangedEvent })
    }

    private suspend fun ApplicationTestBuilder.created(token: String): EntryDto {
        openDay(token)
        return createEntry(token, newEntry(category = "drinks", item = "coffee")).parsed(EntryDto.serializer())
    }

    private fun edit(e: EntryDto, item: String = e.item, category: String? = e.category) = UpdateEntryRequest(e.type, category, item, e.note, e.tenders, keepSlips = null)

    private fun AuthEnv.changeMessages() = notify!!.outbox.items().filter { it.notification.event != "entry.created" }

    @Test
    fun aRealEditPublishesEntryEditedWithBeforeAfterByAndCategoryNames() = env().run {
        val seen = listen("entry.edited")
        val noy = login("noy")
        api {
            val e = created(noy)
            val response = updateEntry(noy, e, edit(e, item = "latte", category = "food"))
            assertEquals(HttpStatusCode.OK, response.status)

            val event = seen.single()
            assertEquals("entry.edited", event.type)
            assertEquals("main", event.branch)
            assertEquals("coffee", event.before.item)
            assertEquals("latte", event.after.item)
            assertEquals(event.after.updatedAt, event.at)
            assertEquals("noy", event.by.name)
            assertEquals("Main Shop", event.branchName)
            assertEquals("Drinks", event.categoryBefore?.en)
            assertEquals("Food", event.categoryAfter?.en)
        }
    }

    @Test
    fun anEditThatChangesNothingPublishesNothing() = env().run {
        val seen = listen("entry.edited")
        val noy = login("noy")
        api {
            val e = created(noy)
            assertEquals(HttpStatusCode.OK, updateEntry(noy, e, edit(e)).status)

            assertEquals(emptyList(), seen)
            assertEquals(emptyList(), changeMessages())
        }
    }

    @Test
    fun twoEditsOfOneEntryQueueTwoMessagesWithDifferentSubjects() = env().run {
        val noy = login("noy")
        api {
            val e = created(noy)
            val first = updateEntry(noy, e, edit(e, item = "latte")).parsed(EntryDto.serializer())
            val second = updateEntry(noy, first, edit(first, item = "mocha")).parsed(EntryDto.serializer())

            val items = changeMessages()
            assertEquals(listOf("entry.edited", "entry.edited"), items.map { it.notification.event })
            assertEquals(setOf("${first.id}@${first.updatedAt}", "${second.id}@${second.updatedAt}"), items.map { it.subject }.toSet())
        }
    }

    @Test
    fun deletePublishesEntryDeletedOnceAndDeletingAgainNothing() = env().run {
        val seen = listen("entry.deleted")
        val noy = login("noy")
        api {
            val e = created(noy)
            assertEquals(HttpStatusCode.OK, deletePath("/api/v1/entries/${e.date}/${e.id}", noy).status)
            assertEquals(HttpStatusCode.OK, deletePath("/api/v1/entries/${e.date}/${e.id}", noy).status)

            val event = seen.single()
            assertEquals("entry.deleted", event.type)
            assertEquals(false, event.before.deleted)
            assertEquals(true, event.after.deleted)
            assertEquals(listOf("entry.deleted"), changeMessages().map { it.notification.event })
        }
    }

    @Test
    fun movePublishesEntryMovedAndMovingToTheSameDateNothing() = env(NO_BACKOFF + "records:\n  edit-window-days: 1\n").run {
        val seen = listen("entry.moved")
        val noy = login("noy")
        api {
            val e = created(noy)
            suspend fun move(date: String) = postJson("/api/v1/entries/${e.date}/${e.id}/move", MoveEntryRequest.serializer(), MoveEntryRequest(date), noy)
            assertEquals(HttpStatusCode.OK, move(e.date).status)
            assertEquals(emptyList(), seen)

            assertEquals(HttpStatusCode.OK, move("2026-10-02").status)
            val event = seen.single()
            assertEquals("entry.moved", event.type)
            assertEquals("2026-10-03", event.before.date)
            assertEquals("2026-10-02", event.after.date)
        }
    }

    @Test
    fun theMessageHasTheFieldsAndALogLine() = env().run {
        val noy = login("noy")
        api {
            val e = created(noy)
            updateEntry(noy, e, edit(e, item = "latte", category = "food"))

            val n = changeMessages().single().notification
            assertEquals("entry.edited", n.event)
            assertEquals("20261003-" + e.id.takeLast(5).uppercase(), n.code)
            assertEquals("Main Shop", n.fields["branch"])
            assertEquals("noy", n.fields["user"])
            assertEquals("coffee", n.fields["before.item"])
            assertEquals("latte", n.fields["item"])
            assertEquals("ເຄື່ອງດື່ມ", n.fields["before.category"])
            assertEquals("ອາຫານ", n.fields["category"])
            assertEquals("food", n.fields["category.key"])
            assertEquals("+150.000 LAK", n.fields["before.money"])
            assertEquals("noy", n.fields["recordedBy"])
            assertTrue(describe(n).startsWith("[${n.code}] Main Shop ${n.fields["time"]}: noy edited entry"), describe(n))
        }
    }

    @Test
    fun theMessagesAreQueuedOnlyForTypesInNotifyEvents() = env(NO_BACKOFF.replace("version: 1", "version: 3") + "notify:\n  events: [entry.created, entry.deleted]\n").run {
        val noy = login("noy")
        api {
            val e = created(noy)
            updateEntry(noy, e, edit(e, item = "latte"))
            assertEquals(emptyList(), changeMessages())
            deletePath("/api/v1/entries/${e.date}/${e.id}", noy)

            assertEquals(listOf("entry.deleted"), changeMessages().map { it.notification.event })
        }
    }

    @Test
    fun aListenerThatThrowsDoesNotFailTheUpdate() = env().run {
        events.subscribe("entry.edited", ShopEventListener { error("listener broke") })
        val noy = login("noy")
        api {
            val e = created(noy)
            val response = updateEntry(noy, e, edit(e, item = "latte"))

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("latte", response.parsed(EntryDto.serializer()).item)
            assertEquals(1, changeMessages().size, "the core's own listener still ran")
        }
    }

    @Test
    fun theApiVersionStaysOneAndAnOldPluginOnEntryCreatedGetsNoChangeEvent() = env().run {
        assertEquals(1, PLUGIN_API_VERSION)
        val seen = mutableListOf<ShopEvent>()
        events.subscribe("entry.created", ShopEventListener { seen += it })
        val noy = login("noy")
        api {
            val e = created(noy)
            updateEntry(noy, e, edit(e, item = "latte"))
            deletePath("/api/v1/entries/${e.date}/${e.id}", noy)

            assertEquals(listOf("entry.created"), seen.map { it.type })
        }
    }
}
