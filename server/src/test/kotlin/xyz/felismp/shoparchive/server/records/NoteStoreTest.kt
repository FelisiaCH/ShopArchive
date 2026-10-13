package xyz.felismp.shoparchive.server.records

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.shared.UserRef
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The note files and the read marks on disk: layout, what a restart brings back, and what start-up does with what it finds. */
class NoteStoreTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()
    private val by = UserRef("0b7c55de-1111-4222-8333-444455556666", "noy")
    private val oct3 = LocalDate.of(2026, 10, 3)

    private fun notes() = NoteStore(root, RecordWriter(), FileOps(RetryPolicy(enabled = false)), log)
    private fun reads() = NoteReadStore(root, RecordWriter(), FileOps(RetryPolicy(enabled = false)), log)

    private fun note(id: String = newUuid7(), branch: String = "main", date: LocalDate = oct3, text: String = "hello", at: String = "2026-10-03T15:00:00+07:00") =
        Note(id, branch, date, text, by, at)

    @Test
    fun aNoteIsOneFileUnderItsBranchAndComesBackWhole() {
        val text = "Bl168 held until Friday\n\"quoted\" \\ ລາວ ไทย 1e3"
        val n = notes().save(note(text = text))

        val file = root.resolve("record/notes/main/${n.id}.yml")
        assertTrue(Files.readString(file).startsWith("file-version: 1\nid: ${n.id}\nbranch: \"main\"\ndate: 2026-10-03\n"))
        val again = notes().also { it.load() }
        assertEquals(n, again.find(n.id))
        assertEquals(text, again.find(n.id)!!.text)
        assertTrue(log.infos.any { "record/notes/: 1 day notes loaded" in it })
        assertEquals(0, Files.list(file.parent).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() })
    }

    @Test
    fun aDayListsItsNotesOldestFirstAndOnlyThoseOfItsBranchAndDate() {
        val store = notes()
        val late = store.save(note(at = "2026-10-03T16:00:00+07:00"))
        val early = store.save(note(at = "2026-10-03T09:00:00+07:00"))
        store.save(note(date = oct3.plusDays(1)))
        store.save(note(branch = "market"))

        assertEquals(listOf(early.id, late.id), store.onDay("main", oct3).map { it.id })
    }

    @Test
    fun aFileThatIsDamagedOrDoesNotMatchItsNameIsLoggedAndLeftAlone() {
        val store = notes()
        val good = store.save(note())
        val dir = root.resolve("record/notes/main")
        val bad = dir.resolve("${newUuid7()}.yml").also { Files.writeString(it, "this is { not a note") }
        val misnamed = dir.resolve("${newUuid7()}.yml").also { Files.writeString(it, Files.readString(dir.resolve("${good.id}.yml"))) }
        val newer = dir.resolve("${newUuid7()}.yml").also { Files.writeString(it, "file-version: 9\n") }
        Files.createDirectories(root.resolve("record/notes/Not A Branch"))

        val loaded = notes().also { it.load() }

        assertEquals(listOf(good.id), loaded.all().map { it.id })
        assertEquals(3, log.errors.size, log.errors.toString())
        assertTrue(log.warnings.any { "not a branch folder" in it })
        assertEquals("this is { not a note", Files.readString(bad))
        assertTrue(Files.exists(misnamed) && Files.exists(newer))
    }

    @Test
    fun readMarksAreOneFilePerUserKeepTheFirstTimeAndComeBackAfterARestart() {
        val store = reads()
        store.mark("u-1", "owner", listOf("n1", "n2"), "2026-10-03T18:00:00+07:00")
        store.mark("u-1", "owner", listOf("n2", "n3"), "2026-10-03T19:00:00+07:00")
        store.mark("u-2", "boss", listOf("n1"), "2026-10-03T17:00:00+07:00")

        assertEquals("2026-10-03T18:00:00+07:00", store.readAt("u-1", "n2"), "the first time stays")
        assertEquals("2026-10-03T19:00:00+07:00", store.readAt("u-1", "n3"))
        assertNull(store.readAt("u-2", "n2"))
        assertEquals(listOf("boss", "owner"), store.readers("n1").map { it.first.name })
        assertEquals(setOf("u-1.yml", "u-2.yml"), Files.list(root.resolve("data/notes-read")).use { s -> s.map { it.fileName.toString() }.toList().toSet() })

        val again = reads().also { it.load() }
        assertEquals(store.readers("n1"), again.readers("n1"))
        assertEquals("2026-10-03T19:00:00+07:00", again.readAt("u-1", "n3"))
        assertTrue(log.infos.any { "data/notes-read/: the reads of 2 users loaded" in it })
    }

    @Test
    fun aMarkThatChangesNothingWritesNothingAndADamagedReadFileIsLeftAlone() {
        val store = reads()
        store.mark("u-1", "owner", listOf("n1"), "2026-10-03T18:00:00+07:00")
        val file = root.resolve("data/notes-read/u-1.yml")
        val before = Files.getLastModifiedTime(file)
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(before.toMillis() - 5_000))
        val stamped = Files.getLastModifiedTime(file)

        store.mark("u-1", "owner", listOf("n1"), "2026-10-03T20:00:00+07:00")
        assertEquals(stamped, Files.getLastModifiedTime(file))

        val broken = root.resolve("data/notes-read/u-9.yml").also { Files.writeString(it, "user: someone-else\n") }
        val loaded = reads().also { it.load() }
        assertNotNull(loaded.readAt("u-1", "n1"))
        assertNull(loaded.readAt("u-9", "n1"))
        assertEquals(1, log.errors.size, log.errors.toString())
        assertEquals("user: someone-else\n", Files.readString(broken))
    }
}
