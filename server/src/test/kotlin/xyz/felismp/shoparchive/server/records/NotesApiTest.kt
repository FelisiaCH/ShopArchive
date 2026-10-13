package xyz.felismp.shoparchive.server.records

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.NoteCreatedEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.authService
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.shared.CreateNoteRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.MarkNotesReadRequest
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.NotesUnread
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Day notes through the real API module: who may write and read, the day a note belongs to, the read marks, the pushes and the event. */
class NotesApiTest {
    @TempDir
    lateinit var root: Path

    private fun env(): AuthEnv = AuthEnv(root, NO_BACKOFF, withRecords = true).also { e ->
        e.records!!.branches.add("main", "Main")
        e.records.branches.add("market", "Market")
    }

    private fun noteFiles(branch: String = "main"): List<String> {
        val dir = root.resolve("record/notes/$branch")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
    }

    private suspend fun ApplicationTestBuilder.write(token: String, text: String, id: String = newUuid7(), branch: String = "main") =
        postJson("/api/v1/notes", CreateNoteRequest.serializer(), CreateNoteRequest(id, branch, text), token)

    private suspend fun ApplicationTestBuilder.notes(token: String, branch: String = "main", date: String = "2026-10-03") =
        getPath("/api/v1/notes?branch=$branch&date=$date", token).parsed(ListSerializer(NoteDto.serializer()))

    private suspend fun ApplicationTestBuilder.unread(token: String, branch: String = "main", date: String = "2026-10-03") =
        getPath("/api/v1/notes/unread?branch=$branch&date=$date", token)

    private suspend fun ApplicationTestBuilder.unreadCount(token: String, branch: String = "main", date: String = "2026-10-03") =
        unread(token, branch, date).parsed(NotesUnread.serializer()).unread

    private suspend fun ApplicationTestBuilder.readAll(token: String, branch: String = "main", date: String = "2026-10-03") =
        postJson("/api/v1/notes/read", MarkNotesReadRequest.serializer(), MarkNotesReadRequest(branch, date), token)

    private suspend fun ApplicationTestBuilder.readOne(token: String, id: String) = client.post("/api/v1/notes/$id/read") {
        header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    // --- create ---

    @Test
    fun aNoteIsStoredOnceWithTheTrimmedTextTheBusinessDateAndItsWriter() = env().run {
        val noy = login("noy")
        api {
            val response = write(noy, "  Bl168 held until Friday\nwill collect in the evening  ")

            assertEquals(HttpStatusCode.Created, response.status)
            val note = response.parsed(NoteDto.serializer())
            assertEquals("Bl168 held until Friday\nwill collect in the evening", note.text)
            assertEquals("2026-10-03", note.date)
            assertEquals("main", note.branch)
            assertEquals("noy", note.createdBy.name)
            assertEquals(emptyList(), note.reads)
            assertEquals(listOf("${note.id}.yml"), noteFiles())
            assertTrue(Files.readString(root.resolve("record/notes/main/${note.id}.yml")).startsWith("file-version: 1\n"))
            assertEquals(listOf(note), notes(noy))
        }
    }

    @Test
    fun aNoteBelongsToTheDayThatIsOpenEvenAfterMidnight() = env().run {
        val noy = login("noy")
        api {
            openDay(noy)
            clock.advance(Duration.ofHours(20)) // 11:00 of 4 October in the shop

            val note = write(login("noy"), "after midnight").parsed(NoteDto.serializer())

            assertEquals("2026-10-03", note.date)
            assertEquals(listOf(note.id), notes(login("noy"), date = "2026-10-03").map { it.id })
            assertEquals(emptyList(), notes(login("noy"), date = "2026-10-04"))
            assertEquals("2026-10-03", write(login("kham"), "same day, whoever writes").parsed(NoteDto.serializer()).date)
        }
    }

    @Test
    fun withoutAnOpenDayTheNoteIsOnTodaysDate() = env().run {
        val noy = login("noy")
        api {
            clock.advance(Duration.ofHours(20))
            assertEquals("2026-10-04", write(login("noy"), "late").parsed(NoteDto.serializer()).date)
        }
    }

    @Test
    fun sendingTheSameIdAgainGivesTheStoredNoteAndWritesNothingMore() = env().run {
        val noy = login("noy")
        val kham = login("kham")
        val boss = login("boss", op = true)
        val published = mutableListOf<String>()
        events.subscribe("note.created", ShopEventListener { published += (it as NoteCreatedEvent).note.id })
        api {
            val id = newUuid7()
            val first = write(noy, "first text", id)
            val again = write(noy, "other text", id)

            assertEquals(HttpStatusCode.Created, first.status)
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(first.parsed(NoteDto.serializer()), again.parsed(NoteDto.serializer()))
            assertEquals(listOf("$id.yml"), noteFiles())
            assertEquals(listOf(id), published, "the event is for the first one only")
            val taken = write(kham, "mine now", id)
            assertEquals(HttpStatusCode.Conflict, taken.status)
            assertEquals(ErrorCode.CONFLICT, taken.errorCode())
            assertEquals("first text", notes(boss).single().text)
        }
    }

    @Test
    fun badNotesAreRefusedAndNothingIsWritten() = env().run {
        users.addUser("mute", "none", listOf("main"))
        users.setUserPermission("mute", NOTE_CREATE_NODE, false)
        val noy = login("noy")
        val mute = login("mute")
        val boss = login("boss", op = true)
        records!!.branches.add("old", "Old")
        records.branches.update("old", null, archived = true)
        api {
            assertEquals(HttpStatusCode.BadRequest, write(noy, "   \n ").status, "empty")
            assertEquals(HttpStatusCode.BadRequest, write(noy, "text", id = "not-an-id").status)
            assertEquals(HttpStatusCode.BadRequest, write(boss, "text", branch = "nowhere").status)
            assertEquals(HttpStatusCode.Forbidden, write(noy, "text", branch = "market").status, "not the user's branch")
            assertEquals(HttpStatusCode.BadRequest, write(boss, "text", branch = "old").status, "archived")
            assertEquals(HttpStatusCode.Forbidden, write(mute, "text").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/notes") { header(PROTOCOL_HEADER, "$PROTOCOL_VERSION") }.status)
            assertEquals(emptyList(), noteFiles())
            assertEquals(emptyList(), noteFiles("market"))
        }
    }

    // --- who sees what ---

    @Test
    fun withoutNoteReadAUserSeesOnlyTheirOwnNotesAndTheOwnerSeesAllOfTheBranch() = env().run {
        users.addUser("quiet", "none", listOf("main"))
        users.setUserPermission("quiet", NOTE_CREATE_NODE, false)
        val noy = login("noy")
        val kham = login("kham")
        val owner = login("owner", grant = listOf(NOTE_READ_NODE))
        val other = login("mai", branches = listOf("market"), grant = listOf(NOTE_READ_NODE))
        val quiet = login("quiet")
        api {
            val a = write(noy, "from noy").parsed(NoteDto.serializer())
            val b = write(kham, "from kham").parsed(NoteDto.serializer())

            assertEquals(listOf(a.id), notes(noy).map { it.id })
            assertEquals(listOf(b.id), notes(kham).map { it.id })
            assertEquals(setOf(a.id, b.id), notes(owner).map { it.id }.toSet())
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/notes?branch=main&date=2026-10-03", other).status, "another branch")
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/notes?branch=main&date=2026-10-03", quiet).status, "neither node")
            assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/notes?branch=main&date=03-10-2026", noy).status)
            assertEquals(emptyList(), notes(owner, date = "2026-10-02"))
        }
    }

    // --- reads ---

    @Test
    fun anOwnerMarksANoteReadTheWriterSeesWhoAndWhenAndTheFirstTimeStays() = env().run {
        val noy = login("noy")
        val owner = login("owner", grant = listOf(NOTE_READ_NODE))
        api {
            val note = write(noy, "hello").parsed(NoteDto.serializer())
            assertEquals(1, unreadCount(owner))

            val read = readOne(owner, note.id)
            clock.advance(Duration.ofHours(1))
            val twice = readOne(login("owner"), note.id)

            assertEquals(HttpStatusCode.OK, read.status)
            val reads = read.parsed(NoteDto.serializer()).reads.single()
            assertEquals("owner", reads.by.name)
            assertEquals("2026-10-03T15:00:00+07:00", reads.at)
            assertEquals(reads, twice.parsed(NoteDto.serializer()).reads.single(), "read again: still the first time")
            assertEquals(listOf(reads), notes(login("noy")).single().reads, "the writer sees who read it")
            assertEquals(0, unreadCount(login("owner")))
            val file = Files.list(root.resolve("data/notes-read")).use { it.toList().single() }
            assertTrue(note.id in Files.readString(file))
        }
    }

    @Test
    fun markingNeedsTheReadNodeAndTheBranchAndAKnownNote() = env().run {
        val noy = login("noy")
        val other = login("mai", branches = listOf("market"), grant = listOf(NOTE_READ_NODE))
        api {
            val note = write(noy, "hello").parsed(NoteDto.serializer())

            assertEquals(HttpStatusCode.Forbidden, readOne(noy, note.id).status, "a writer cannot mark")
            assertEquals(HttpStatusCode.Forbidden, readOne(other, note.id).status, "another branch")
            assertEquals(HttpStatusCode.NotFound, readOne(other, newUuid7()).status)
            assertEquals(HttpStatusCode.NotFound, readOne(other, "not-an-id").status)
            assertEquals(HttpStatusCode.Forbidden, readAll(noy).status)
            assertEquals(HttpStatusCode.Forbidden, readAll(other).status, "another branch")
            assertEquals(HttpStatusCode.Forbidden, unread(noy).status)
            assertEquals(HttpStatusCode.Forbidden, unread(other).status)
            assertEquals(HttpStatusCode.BadRequest, readAll(other, branch = "market", date = "tomorrow").status)
            assertEquals(emptyList(), notes(noy).single().reads)
            assertTrue(!Files.exists(root.resolve("data/notes-read")))
        }
    }

    @Test
    fun markAllReadMarksTheOthersNotesOfThatDayOnlyAndCountsStayRight() = env().run {
        val noy = login("noy")
        login("owner", grant = listOf(NOTE_READ_NODE))
        login("lead", grant = listOf(NOTE_READ_NODE))
        api {
            write(noy, "one")
            write(noy, "two")
            val own = write(login("owner"), "from the owner").parsed(NoteDto.serializer())
            clock.advance(Duration.ofHours(20))
            write(login("noy"), "next day")
            val owner = login("owner")
            val lead = login("lead")

            assertEquals(2, unreadCount(owner), "the owner's own note is not unread")
            assertEquals(3, unreadCount(lead))
            assertEquals(1, unreadCount(owner, date = "2026-10-04"))

            val marked = readAll(owner).parsed(ListSerializer(NoteDto.serializer()))

            assertEquals(3, marked.size)
            assertEquals(listOf(true, true, false), marked.map { n -> n.reads.any { it.by.name == "owner" } }, "the owner's own note is not marked")
            assertEquals(own.id, marked.last().id)
            assertEquals(0, unreadCount(owner))
            assertEquals(3, unreadCount(lead), "marks are per user")
            assertEquals(1, unreadCount(owner, date = "2026-10-04"))
        }
    }

    @Test
    fun notesAndReadsSurviveARestart() = env().run {
        val noy = login("noy")
        val owner = login("owner", grant = listOf(NOTE_READ_NODE))
        api {
            val note = write(noy, "keep me").parsed(NoteDto.serializer())
            readOne(owner, note.id)
            val before = notes(owner)

            records!!.load()

            assertEquals(before, notes(owner))
            assertEquals(1, before.single().reads.size)
        }
    }

    // --- pushes and the event ---

    @Test
    fun theUsersWhoMaySeeTheNoteHearOfItAndOfItsReading() = env().run {
        val noy = login("noy")
        val kham = login("kham")
        val owner = login("owner", grant = listOf(NOTE_READ_NODE))
        val market = login("mai", branches = listOf("market"), grant = listOf(NOTE_READ_NODE))
        val eventService = services.get(EventService::class.java)!!
        val heard = mutableMapOf<String, MutableList<String>>()
        for ((name, token) in mapOf("noy" to noy, "kham" to kham, "owner" to owner, "mai" to market)) {
            eventService.register(authService().authenticate(token)!!) { heard.getOrPut(name) { mutableListOf() } += it; true }
        }
        api {
            val note = write(noy, "hello").parsed(NoteDto.serializer())
            readOne(owner, note.id)
            readOne(owner, note.id) // nothing changed: nothing pushed
            val types = { name: String -> heard[name].orEmpty().map { Json.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content } }

            assertEquals(listOf("note.changed", "note.changed"), types("noy"), "the writer hears of the write and of the read")
            assertEquals(listOf("note.changed", "note.changed"), types("owner"))
            assertEquals(emptyList(), types("kham"), "a colleague who wrote nothing hears nothing")
            assertEquals(emptyList(), types("mai"), "another branch hears nothing")
            val pushed = Json.parseToJsonElement(heard.getValue("owner")[0]).jsonObject
            assertEquals("main", pushed.getValue("branch").jsonPrimitive.content)
            assertEquals("2026-10-03", pushed.getValue("date").jsonPrimitive.content)
        }
    }

    @Test
    fun aNewNoteIsPublishedAsAShopEventWithTheBranchName() = env().run {
        val noy = login("noy")
        val seen = mutableListOf<NoteCreatedEvent>()
        events.subscribe("note.created", ShopEventListener { seen += it as NoteCreatedEvent })
        api {
            val note = write(noy, "hello").parsed(NoteDto.serializer())

            val event = seen.single()
            assertEquals(note, event.note)
            assertEquals("Main", event.branchName)
            assertEquals("main", event.branch)
            assertEquals(note.createdAt, event.at)
        }
    }
}
