package xyz.felismp.shoparchive.server.records

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The entry files on disk: layout, the single writer, and what start-up does with what it finds. */
class RecordStoreTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-03T08:00:00Z"), ZoneOffset.UTC)
    private val exponents = mapOf("LAK" to 0, "THB" to 2)
    private val by = UserRef("0b7c55de-1111-4222-8333-444455556666", "noy")
    private val oct3 = LocalDate.of(2026, 10, 3)

    private fun store(files: FileOps = FileOps(RetryPolicy(enabled = false))) = RecordStore(root, { exponents }, RecordWriter(), files, log, clock)

    private fun entry(
        id: String = newUuid7(), date: LocalDate = oct3, slips: List<Slip> = emptyList(),
        tenders: List<Tender> = listOf(Tender("LAK", TenderMethod.CASH, Amount(150000, 0))),
    ) = Entry(
        id, date, EntryType.INCOME, "main", null, "coffee", "", by, "2026-10-03T15:00:00+07:00", "2026-10-03T15:00:00+07:00",
        deleted = false, tenders = tenders, slips = slips, session = null,
    )

    private fun history(action: String = "create") = HistoryItem("2026-10-03T15:00:00+07:00", by, action, emptyList())

    private fun create(store: RecordStore, e: Entry = entry(), slips: Map<String, ByteArray> = emptyMap()): Entry = store.create(NewEntry(e, slips, history()))

    private fun dir(e: Entry) = root.resolve("record/${e.date.toString().replace('-', '/')}/${e.id}")

    // --- layout ---

    @Test
    fun anEntryIsOneFolderByDateWithItsFilesAndNothingIsLeftBehindInStaging() {
        val store = store()
        val jpg = jpeg()
        val e = entry(slips = listOf(Slip("slip-1.jpg", sha256Hex(jpg))), tenders = listOf(Tender("THB", TenderMethod.ONLINE, Amount(12050, 2))))

        create(store, e, mapOf("slip-1.jpg" to jpg))

        assertEquals(setOf("entry.yml", "history.yml", "slip-1.jpg"), Files.list(dir(e)).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
        assertContentEquals(jpg, Files.readAllBytes(dir(e).resolve("slip-1.jpg")))
        assertEquals(0, Files.list(root.resolve("data/staging")).use { it.count() })
        val text = Files.readString(dir(e).resolve("entry.yml"))
        assertTrue(text.startsWith("file-version: 1\nid: ${e.id}\n"), text)
        assertTrue("amount: \"120.50\"" in text, text)
        assertEquals(e, store.find(e.id))
        assertEquals(listOf("create"), store.history(e).map { it.action })
    }

    @Test
    fun whatIsWrittenLoadsBackIdenticallyAfterARestart() {
        val first = store()
        val e = entry(tenders = listOf(Tender("LAK", TenderMethod.CASH, Amount(5, 0)), Tender("THB", TenderMethod.CASH, Amount(5, 2))))
        val thai = e.copy(item = "ນ້ຳກ້ອນ \"สอง\" \\ null\nline2", note = "true")
        create(first, thai)

        val second = store()
        second.load()

        assertEquals(thai, second.find(thai.id))
        assertTrue(second.brokenEntries().isEmpty())
    }

    @Test
    fun editingReplacesTheFileAndAppendsToTheHistoryWithoutLosingTheOldItems() {
        val store = store()
        val e = create(store)

        val edited = e.copy(item = "tea", updatedAt = "2026-10-03T16:00:00+07:00")
        store.replace(edited, emptyMap(), HistoryItem("2026-10-03T16:00:00+07:00", by, "edit", listOf(xyz.felismp.shoparchive.shared.HistoryChange("item", "coffee", "tea"))))

        assertEquals("tea", store.find(e.id)!!.item)
        val items = store.history(edited)
        assertEquals(listOf("create", "edit"), items.map { it.action })
        assertEquals("tea", items[1].changes.single().to)
        assertFalse(Files.exists(dir(e).resolve("entry.yml.tmp")))
    }

    @Test
    fun movingChangesTheFolderAndTheDateInTheFileTogether() {
        val store = store()
        val e = create(store)
        val moved = e.copy(date = LocalDate.of(2026, 10, 1))

        store.move(e, moved, history("move"))

        assertFalse(Files.exists(dir(e)))
        assertTrue(Files.exists(dir(moved).resolve("entry.yml")))
        assertEquals(moved, store().also { it.load() }.find(e.id))
        assertEquals(listOf("create", "move"), store.history(moved).map { it.action })
    }

    @Test
    fun aMoveThatCannotWriteTheFileIsUndoneSoFolderAndDateStillAgree() {
        val calls = AtomicInteger()
        // call 1: the history; call 2: the new entry.yml; call 3: the folder rename fails; call 4: the old entry.yml is put back.
        val files = FileOps(RetryPolicy(enabled = false), move = { from, to ->
            if (calls.incrementAndGet() == 3) throw AccessDeniedException(to.toString())
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        })
        val store = store(files)
        val e = entry().also { store.create(NewEntry(it, emptyMap(), history())) }
        calls.set(0)

        assertFailsWith<AccessDeniedException> { store.move(e, e.copy(date = LocalDate.of(2026, 10, 1)), history("move")) }

        assertEquals(e, store().also { it.load() }.find(e.id), "the file says the old date again, where the folder still is")
        assertFalse(Files.exists(dir(e.copy(date = LocalDate.of(2026, 10, 1)))))
        assertEquals(e, store.find(e.id))
    }

    @Test
    fun theNextSlipNumberSkipsSlipsThatWereTakenOffTheEntryButStayOnDisk() {
        val store = store()
        val jpg = jpeg()
        val e = create(store, entry(slips = listOf(Slip("slip-1.jpg", sha256Hex(jpg)), Slip("slip-2.png", "0".repeat(64)))), mapOf("slip-1.jpg" to jpg, "slip-2.png" to png()))
        val withoutSlip2 = e.copy(slips = e.slips.take(1))
        store.replace(withoutSlip2, emptyMap(), history("edit"))

        assertEquals(3, store.nextSlipNumber(withoutSlip2))
        assertTrue(Files.exists(dir(e).resolve("slip-2.png")), "a slip taken off an entry is never deleted")
    }

    // --- the single writer ---

    @Test
    fun fiftyParallelCreatesAreFiftyWholeEntriesWrittenOneAtATime() {
        val store = store()
        val running = AtomicInteger()
        val most = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val ids = (1..50).map { newUuid7() }

        val results = ids.map { id ->
            pool.submit {
                start.await()
                store.writer.run {
                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    create(store, entry(id = id))
                    running.decrementAndGet()
                }
            }
        }
        start.countDown()
        results.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, most.get(), "two writes ran at the same time")
        assertEquals(50, root.entryFolders().size)
        val reloaded = store().also { it.load() }
        assertEquals(ids.toSet(), reloaded.all().map { it.id }.toSet())
        assertTrue(reloaded.brokenEntries().isEmpty())
    }

    // --- start-up: what a stop in the middle leaves ---

    /** A folder as `create` leaves it just before the rename: the files of a whole entry. */
    private fun stage(e: Entry, slips: Map<String, ByteArray> = emptyMap(), withEntryFile: Boolean = true): Path {
        val dir = root.resolve("data/staging/${e.id}")
        Files.createDirectories(dir)
        slips.forEach { (name, bytes) -> Files.write(dir.resolve(name), bytes) }
        Files.writeString(dir.resolve("history.yml"), renderHistoryHead() + renderHistoryItem(history()))
        if (withEntryFile) Files.writeString(dir.resolve("entry.yml"), renderEntry(e))
        return dir
    }

    @Test
    fun aWholeStagedEntryLeftByAStopIsMovedIntoPlaceAtStart() {
        val jpg = jpeg()
        val e = entry(slips = listOf(Slip("slip-1.jpg", sha256Hex(jpg))))
        stage(e, mapOf("slip-1.jpg" to jpg))
        val store = store()

        store.load()

        assertEquals(e, store.find(e.id))
        assertTrue(Files.exists(dir(e).resolve("slip-1.jpg")))
        assertFalse(Files.exists(root.resolve("data/staging/${e.id}")))
        assertTrue(log.warnings.any { "cut short" in it && e.id in it }, log.warnings.toString())
    }

    @Test
    fun aStagedFolderWhoseIdIsAlreadyStoredGoesToDatafixAndTheStoredEntryIsUntouched() {
        val store = store()
        val stored = create(store)
        val before = Files.readAllBytes(dir(stored).resolve("entry.yml"))
        stage(stored.copy(item = "a different version"))

        store().load()

        assertContentEquals(before, Files.readAllBytes(dir(stored).resolve("entry.yml")))
        assertFalse(Files.exists(root.resolve("data/staging/${stored.id}")))
        val set = Files.walk(root.resolve("data/datafix")).use { s -> s.filter { it.fileName.toString() == "staging-${stored.id}" }.toList() }
        assertEquals(1, set.size)
        assertTrue(Files.readString(set[0].resolve("entry.yml")).contains("a different version"), "the set-aside copy is kept whole")
        assertTrue(log.warnings.any { "exists in record/ already" in it }, log.warnings.toString())
    }

    @Test
    fun aStagedFolderThatIsNotWholeIsSetAsideNotDeletedAndNotUsed() {
        val noEntryFile = entry().also { stage(it, withEntryFile = false) }
        val missingSlip = entry(slips = listOf(Slip("slip-1.jpg", "a".repeat(64)))).also { stage(it) }
        val wrongSlip = entry(slips = listOf(Slip("slip-1.jpg", "a".repeat(64)))).also { stage(it, mapOf("slip-1.jpg" to jpeg())) }
        Files.createDirectories(root.resolve("data/staging/not-an-id"))
        val store = store()

        store.load()

        assertTrue(store.all().isEmpty())
        assertEquals(0, Files.list(root.resolve("data/staging")).use { it.count() })
        val kept = Files.walk(root.resolve("data/datafix")).use { s -> s.filter { it.parent.parent.fileName.toString() == "datafix" && it.fileName.toString().startsWith("staging-") }.map { it.fileName.toString() }.toList().toSet() }
        assertEquals(setOf("staging-${noEntryFile.id}", "staging-${missingSlip.id}", "staging-${wrongSlip.id}", "staging-not-an-id"), kept)
        assertEquals(emptyList(), root.entryFolders())
    }

    @Test
    fun anEntryInTwoDateFoldersIsBrokenAndListedNowhereButTheRestLoads() {
        val store = store()
        val good = create(store)
        val twice = create(store)
        // a move that stopped between its two steps cannot make this, but a copied folder can
        val other = dir(twice.copy(date = LocalDate.of(2026, 10, 2)))
        Files.createDirectories(other.parent)
        copyTree(dir(twice), other)
        val loaded = store()

        loaded.load()

        assertNotNull(loaded.find(good.id))
        assertNull(loaded.find(twice.id), "an entry that exists twice is not served")
        assertTrue("2 folders" in loaded.brokenEntry(twice.id)!!.reason, loaded.brokenEntry(twice.id)!!.reason)
        assertEquals(2, loaded.brokenEntry(twice.id)!!.paths.size)
        assertTrue(Files.exists(dir(twice).resolve("entry.yml")) && Files.exists(other.resolve("entry.yml")), "nothing is removed")
    }

    @Test
    fun aFolderWhoseNameDisagreesWithTheDateOrIdInItsFileIsBrokenOnlyThatOne() {
        val store = store()
        val good = create(store)
        val wrongDay = create(store)
        val wrongId = create(store)
        Files.move(dir(wrongDay), dir(wrongDay.copy(date = LocalDate.of(2026, 10, 4))).also { Files.createDirectories(it.parent) })
        Files.createFile(dir(wrongDay)) // the folder cannot go back to its date
        val impostor = newUuid7()
        Files.move(dir(wrongId), dir(wrongId).resolveSibling(impostor))
        val loaded = store()

        loaded.load()

        assertNotNull(loaded.find(good.id))
        assertTrue("says its date is 2026-10-03" in loaded.brokenEntry(wrongDay.id)!!.reason, loaded.brokenEntry(wrongDay.id)!!.reason)
        assertTrue("holds the entry ${wrongId.id}" in loaded.brokenEntry(impostor)!!.reason, loaded.brokenEntry(impostor)!!.reason)
        assertEquals(setOf(wrongDay.id, impostor), loaded.brokenEntries().map { it.id }.toSet())
    }

    @Test
    fun aMoveThatStoppedAfterTheFolderRenameIsFinishedAtStartByMovingTheFolderToTheDateInItsFile() {
        val e = create(store(), entry(date = LocalDate.of(2026, 10, 2)))
        Files.createDirectories(dir(e.copy(date = oct3)).parent)
        Files.move(dir(e), dir(e.copy(date = oct3))) // the old order: folder renamed, entry.yml not yet rewritten
        val loaded = store()

        loaded.load()

        assertEquals(e, loaded.find(e.id))
        assertTrue(loaded.brokenEntries().isEmpty())
        assertTrue(Files.exists(dir(e).resolve("entry.yml")))
        assertFalse(Files.exists(dir(e.copy(date = oct3))))
        assertTrue(log.warnings.any { "move was cut short" in it }, log.warnings.toString())
    }

    @Test
    fun aRepairWhoseFolderMovedButCouldNotBeMadeDurableStopsTheStartInsteadOfHidingTheEntry() {
        val e = create(store(), entry(date = LocalDate.of(2026, 10, 2)))
        Files.createDirectories(dir(e.copy(date = oct3)).parent)
        Files.move(dir(e), dir(e.copy(date = oct3)))
        val failing = FileOps(RetryPolicy(enabled = false), syncDirectory = { throw java.io.IOException("fsync refused") })

        assertFailsWith<java.io.IOException> { store(failing).load() }

        assertTrue(Files.exists(dir(e).resolve("entry.yml")))
        assertEquals(e, store().also { it.load() }.find(e.id))
    }

    @Test
    fun aMoveThatStoppedAfterTheFileWasRewrittenIsFinishedAtStart() {
        val e = create(store())
        val moved = e.copy(date = LocalDate.of(2026, 10, 2))
        Files.writeString(dir(e).resolve("entry.yml"), renderEntry(moved)) // the new order: entry.yml rewritten, folder not yet renamed
        val loaded = store()

        loaded.load()

        assertEquals(moved, loaded.find(e.id))
        assertTrue(loaded.brokenEntries().isEmpty())
        assertTrue(Files.exists(dir(moved).resolve("entry.yml")))
        assertFalse(Files.exists(dir(e)))
    }

    @Test
    fun aFolderOnTheWrongDateStaysBrokenWhenTheFolderForItsDateIsTaken() {
        val e = create(store(), entry(date = LocalDate.of(2026, 10, 2)))
        Files.createDirectories(dir(e.copy(date = oct3)).parent)
        Files.move(dir(e), dir(e.copy(date = oct3)))
        Files.createFile(dir(e)) // something is where the folder would go
        val loaded = store()

        loaded.load()

        assertNull(loaded.find(e.id))
        assertTrue("says its date is 2026-10-02" in loaded.brokenEntry(e.id)!!.reason, loaded.brokenEntry(e.id)!!.reason)
        assertTrue(Files.exists(dir(e.copy(date = oct3)).resolve("entry.yml")), "nothing is moved")
    }

    @Test
    fun anEditWhoseEntryFileCannotBeWrittenStillHasItsHistoryItem() {
        val calls = AtomicInteger(-2) // so the create below, which renames twice (the folder, the copy in cache/), does not count
        // call 1: the history item is put in place; call 2: the new entry.yml cannot be.
        val files = FileOps(RetryPolicy(enabled = false), move = { from, to ->
            if (calls.incrementAndGet() == 2) throw AccessDeniedException(to.toString())
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        })
        val store = store(files)
        val e = create(store)
        calls.set(0)
        val before = Files.readString(dir(e).resolve("entry.yml"))

        assertFailsWith<AccessDeniedException> { store.replace(e.copy(item = "tea"), emptyMap(), history("edit")) }

        assertEquals(listOf("create", "edit"), store.history(e).map { it.action })
        assertEquals(before, Files.readString(dir(e).resolve("entry.yml")))
        assertEquals(e, store.find(e.id))
    }

    @Test
    fun anEntryFileThatIsEditedIntoNonsenseMakesOnlyThatEntryBrokenAndIsNeverRewritten() {
        val store = store()
        val good = create(store)
        val bad = create(store)
        val broken = create(store)
        val ruined = listOf(
            bad to Files.readString(dir(bad).resolve("entry.yml")).replace("amount: \"150000\"", "amount: \"15,000\""),
            broken to "id: [unclosed\n  nonsense: : :",
        )
        val bytes = ruined.associate { (e, text) -> e.id to text.also { Files.writeString(dir(e).resolve("entry.yml"), it) } }
        val loaded = store()

        loaded.load()

        assertNotNull(loaded.find(good.id))
        assertEquals(setOf(bad.id, broken.id), loaded.brokenEntries().map { it.id }.toSet())
        for ((id, text) in bytes) {
            val entryDir = dir(if (id == bad.id) bad else broken)
            assertEquals(text, Files.readString(entryDir.resolve("entry.yml")), "the file is left exactly as it is")
        }
        assertTrue(log.errors.any { "BROKEN" in it }, log.errors.toString())
    }

    @Test
    fun anEntryWithoutAnEntryFileOrInAnUnknownCurrencyIsBrokenWithAReasonTheAdminCanActOn() {
        val store = store()
        val noFile = create(store)
        val thb = create(store, entry(tenders = listOf(Tender("THB", TenderMethod.CASH, Amount(100, 2)))))
        Files.delete(dir(noFile).resolve("entry.yml"))
        val withoutThb = RecordStore(root, { mapOf("LAK" to 0) }, RecordWriter(), FileOps(), log, clock)

        withoutThb.load()

        assertTrue("no entry.yml" in withoutThb.brokenEntry(noFile.id)!!.reason)
        assertTrue("THB is not in config/currencies.yml" in withoutThb.brokenEntry(thb.id)!!.reason, withoutThb.brokenEntry(thb.id)!!.reason)
    }

    @Test
    fun aNewerFileVersionIsLeftAloneAndReportedNotGuessedAt() {
        val store = store()
        val e = create(store)
        Files.writeString(dir(e).resolve("entry.yml"), Files.readString(dir(e).resolve("entry.yml")).replace("file-version: 1", "file-version: 9"))
        val loaded = store()

        loaded.load()

        assertTrue("only knows up to 1" in loaded.brokenEntry(e.id)!!.reason, loaded.brokenEntry(e.id)!!.reason)
    }

    @Test
    fun foldersThatAreNotEntriesAreLeftAloneWithoutBreakingAnything() {
        val store = store()
        val e = create(store)
        Files.createDirectories(root.resolve("record/sessions/main"))
        Files.createDirectories(root.resolve("record/notes"))
        Files.createDirectories(dir(e).resolveSibling("not-an-id"))
        val loaded = store()

        loaded.load()

        assertEquals(listOf(e.id), loaded.all().map { it.id })
        assertTrue(loaded.brokenEntries().isEmpty())
        assertTrue(log.warnings.any { "not an entry folder" in it })
    }

    // --- a rename the system refuses for a moment ---

    private fun refusing(times: Int, error: () -> Exception): Pair<(Path, Path) -> Unit, AtomicInteger> {
        val seen = AtomicInteger()
        val move: (Path, Path) -> Unit = { from, to ->
            if (seen.incrementAndGet() <= times) throw error()
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        }
        return move to seen
    }

    @Test
    fun aRenameTheAntivirusHoldsIsTriedAgainWithGrowingWaitsUntilItGoesThrough() {
        val waits = mutableListOf<Long>()
        val (move, seen) = refusing(12) { AccessDeniedException("x") }
        val files = FileOps(RetryPolicy(enabled = true, sleep = { waits += it }), move)
        val from = Files.createFile(root.resolve("a.txt"))

        files.rename(from, root.resolve("b.txt"))

        assertTrue(Files.exists(root.resolve("b.txt")))
        assertEquals(13, seen.get())
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2000), waits)
    }

    @Test
    fun aRenameThatNeverGoesThroughGivesUpAfterAboutTenSecondsWithAnErrorForTheClient() {
        val waits = mutableListOf<Long>()
        val (move, _) = refusing(Int.MAX_VALUE) { AccessDeniedException("x") }
        val files = FileOps(RetryPolicy(enabled = true, sleep = { waits += it }), move)

        val failure = assertFailsWith<StorageBusyException> { files.rename(root.resolve("a"), root.resolve("b")) }

        assertTrue(waits.sum() in 8_000L..10_000L, "waited ${waits.sum()} ms")
        assertTrue(failure.message!!.contains("would not rename"))
    }

    @Test
    fun aMissingSourceOrATakenNameFailAtOnceAndOnNonWindowsNothingIsRetried() {
        val waits = mutableListOf<Long>()
        val (gone, goneCalls) = refusing(Int.MAX_VALUE) { NoSuchFileException("x") }
        assertFailsWith<NoSuchFileException> { FileOps(RetryPolicy(enabled = true, sleep = { waits += it }), gone).rename(root.resolve("a"), root.resolve("b")) }
        assertEquals(1, goneCalls.get())
        val (denied, deniedCalls) = refusing(Int.MAX_VALUE) { AccessDeniedException("x") }
        assertFailsWith<AccessDeniedException> { FileOps(RetryPolicy(enabled = false, sleep = { waits += it }), denied).rename(root.resolve("a"), root.resolve("b")) }
        assertEquals(1, deniedCalls.get())
        assertTrue(waits.isEmpty())
    }

    private fun copyTree(from: Path, to: Path) {
        Files.createDirectories(to)
        Files.list(from).use { s -> s.forEach { Files.copy(it, to.resolve(it.fileName)) } }
    }
}
