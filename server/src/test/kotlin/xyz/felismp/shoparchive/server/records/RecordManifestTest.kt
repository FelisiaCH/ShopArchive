package xyz.felismp.shoparchive.server.records

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The copy of each `entry.yml` in `cache/records/`, and what the store does when the admin has corrected the file by hand. */
class RecordManifestTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-03T08:00:00Z"), ZoneOffset.UTC)
    private val exponents = mapOf("LAK" to 0, "THB" to 2)
    private val by = UserRef("0b7c55de-1111-4222-8333-444455556666", "noy")
    private val oct3 = LocalDate.of(2026, 10, 3)

    private fun store() = RecordStore(root, { exponents }, RecordWriter(), FileOps(RetryPolicy(enabled = false)), log, clock)

    private fun entry(id: String = newUuid7()) = Entry(
        id, oct3, EntryType.INCOME, "main", null, "coffee", "", by, "2026-10-03T15:00:00+07:00", "2026-10-03T15:00:00+07:00",
        deleted = false, tenders = listOf(Tender("LAK", TenderMethod.CASH, Amount(150000, 0))), slips = emptyList(), session = null,
    )

    private fun history(action: String) = HistoryItem("2026-10-03T16:00:00+07:00", by, action, emptyList())

    private fun create(store: RecordStore): Entry = entry().also { store.create(NewEntry(it, emptyMap(), history("create"))) }

    private fun file(e: Entry) = root.resolve("record/2026/10/03/${e.id}/entry.yml")

    private fun snapshot(e: Entry) = root.resolve("cache/records/${e.id}.yml")

    private fun editAmount(e: Entry) = Files.writeString(file(e), Files.readString(file(e)).replace("amount: \"150000\"", "amount: \"175000\""))

    @Test
    fun aHandRestoreAfterAWriteWhoseCopyFailedIsStillSeenAsAHandEdit() {
        // Renames into cache/records/ fail: every copy after create is stale.
        var failCopies = false
        val files = FileOps(RetryPolicy(enabled = false), move = { from, to ->
            if (failCopies && to.startsWith(root.resolve("cache/records"))) throw java.io.IOException("cache is full")
            Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        })
        val store = RecordStore(root, { exponents }, RecordWriter(), files, log, clock)
        val e = entry().also { store.create(NewEntry(it, emptyMap(), history("create"))) }
        val original = Files.readAllBytes(file(e))
        failCopies = true
        val raised = e.copy(tenders = listOf(Tender("LAK", TenderMethod.CASH, Amount(175000, 0))))
        store.replace(raised, emptyMap(), history("edit"))
        Files.write(file(e), original) // the admin puts the old amount back by hand; it matches the stale copy

        assertFailsWith<ApiError> { store.replace(raised.copy(note = "later"), emptyMap(), history("edit")) }

        assertContentEquals(original, Files.readAllBytes(file(e)))
        assertEquals(e, store.find(e.id))
        assertTrue("manual-edit" in Files.readString(root.resolve("record/2026/10/03/${e.id}/history.yml")))
    }

    @Test
    fun aCreatedEntryHasACopyOfItsFileInCache() {
        val e = create(store())

        assertContentEquals(Files.readAllBytes(file(e)), Files.readAllBytes(snapshot(e)))
    }

    @Test
    fun aFileEditedByHandWhileLoadedIsKeptAsAManualEditAndTheChangeBasedOnTheCacheIsRefused() {
        val store = store()
        val e = create(store)
        editAmount(e)
        val edited = Files.readString(file(e))

        val error = assertFailsWith<ApiError> { store.replace(e.copy(item = "tea"), emptyMap(), history("edit")) }

        assertEquals(409, error.status)
        assertTrue("changed by hand" in error.message!!, error.message)
        assertEquals(edited, Files.readString(file(e)), "the hand edit is not overwritten")
        val items = store.history(e)
        assertEquals(listOf("create", "manual-edit"), items.map { it.action })
        val change = items[1].changes.single()
        assertEquals("tenders", change.field)
        assertTrue("150000" in change.from && "175000" in change.to, change.toString())
        assertEquals(Amount(175000, 0), store.find(e.id)!!.tenders.single().amount)
        assertEquals("coffee", store.find(e.id)!!.item)
        assertContentEquals(Files.readAllBytes(file(e)), Files.readAllBytes(snapshot(e)))
    }

    @Test
    fun aValidHandEditIsTakenInAtTheNextStartOnce() {
        val e = create(store())
        editAmount(e)

        val first = store().also { it.load() }
        val second = store().also { it.load() }

        assertEquals(Amount(175000, 0), first.find(e.id)!!.tenders.single().amount)
        assertEquals(listOf("create", "manual-edit"), second.history(e).map { it.action })
        assertContentEquals(Files.readAllBytes(file(e)), Files.readAllBytes(snapshot(e)))
        assertTrue(log.infos.any { "changed by hand" in it }, log.infos.toString())
    }

    @Test
    fun aFileEditedIntoNonsenseBreaksOnlyThatEntryAtStart() {
        val store = store()
        val good = create(store)
        val bad = create(store)
        Files.writeString(file(bad), "id: [unclosed\n  nonsense: : :")

        val loaded = store().also { it.load() }

        assertNotNull(loaded.find(good.id))
        assertNull(loaded.find(bad.id))
        assertEquals(listOf(bad.id), loaded.brokenEntries().map { it.id })
        assertEquals("id: [unclosed\n  nonsense: : :", Files.readString(file(bad)))
    }

    @Test
    fun aMissingCopyIsMadeAgainWithoutAHistoryItem() {
        val e = create(store())
        Files.delete(snapshot(e))

        val loaded = store().also { it.load() }

        assertNotNull(loaded.find(e.id))
        assertContentEquals(Files.readAllBytes(file(e)), Files.readAllBytes(snapshot(e)))
        assertEquals(listOf("create"), loaded.history(e).map { it.action })
    }

    @Test
    fun aMoveAfterAHandEditIsRefusedAndTheFolderStays() {
        val store = store()
        val e = create(store)
        editAmount(e)

        assertFailsWith<ApiError> { store.move(e, e.copy(date = LocalDate.of(2026, 10, 1)), history("move")) }

        assertTrue(Files.exists(file(e)))
        assertFalse(Files.exists(root.resolve("record/2026/10/01/${e.id}")))
        assertEquals(listOf("create", "manual-edit"), store.history(e).map { it.action })
    }

    @Test
    fun aChangeOverAFileThatWasBrokenByHandIsRefusedAndTheFileIsNotTouched() {
        val store = store()
        val e = create(store)
        Files.writeString(file(e), "id: [unclosed")

        assertFailsWith<ApiError> { store.replace(e.copy(item = "tea"), emptyMap(), history("edit")) }

        assertEquals("id: [unclosed", Files.readString(file(e)))
        assertNotNull(store.brokenEntry(e.id))
        assertNull(store.find(e.id))
        assertEquals(listOf("create"), store.history(e).map { it.action })
    }
}
