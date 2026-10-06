package xyz.felismp.shoparchive.server.records

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryChange
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plan's check for Windows with Defender running: a thousand creates and a thousand moves of whole entries (each with a slip),
 * from several threads, with the real rename retry. No error may reach the caller, and every entry must be whole afterwards.
 */
class RecordStressTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun aThousandCreatesAndMovesLeaveEveryEntryWholeAndRaiseNoError() {
        val log = RecordingLog()
        val retries = AtomicInteger()
        val files = FileOps(RetryPolicy(sleep = { retries.incrementAndGet(); Thread.sleep(it) }))
        val store = RecordStore(root, { mapOf("LAK" to 0) }, RecordWriter(), files, log, Clock.systemUTC())
        val by = UserRef("0b7c55de-1111-4222-8333-444455556666", "noy")
        val slip = jpeg(size = 2048)
        val day = LocalDate.of(2026, 10, 3)
        val pool = Executors.newFixedThreadPool(8)

        val started = System.nanoTime()
        val creates = (1..1000).map {
            pool.submit<Entry> {
                val id = newUuid7()
                val entry = Entry(
                    id, day, EntryType.INCOME, "main", null, "stress $it", "", by, "2026-10-03T15:00:00+07:00", "2026-10-03T15:00:00+07:00",
                    deleted = false, tenders = listOf(Tender("LAK", TenderMethod.ONLINE, Amount(it.toLong(), 0))),
                    slips = listOf(Slip("slip-1.jpg", sha256Hex(slip))), session = null,
                )
                store.create(NewEntry(entry, mapOf("slip-1.jpg" to slip), HistoryItem(entry.createdAt, by, "create", emptyList())))
            }
        }
        val created = creates.map { it.get(10, TimeUnit.MINUTES) }
        val createdAt = System.nanoTime()
        val moves = created.map { e ->
            pool.submit {
                val moved = e.copy(date = day.minusDays(1))
                store.move(e, moved, HistoryItem(moved.updatedAt, by, "move", listOf(HistoryChange("date", e.date.toString(), moved.date.toString()))))
            }
        }
        moves.forEach { it.get(10, TimeUnit.MINUTES) }
        val done = System.nanoTime()
        pool.shutdown()

        println("STRESS creates: ${(createdAt - started) / 1_000_000} ms for 1000, moves: ${(done - createdAt) / 1_000_000} ms for 1000, total ${(done - started) / 1_000_000} ms, renames tried again: ${retries.get()}")
        val reloaded = RecordStore(root, { mapOf("LAK" to 0) }, RecordWriter(), FileOps(), log, Clock.systemUTC())
        reloaded.load()
        assertEquals(1000, reloaded.all().size)
        assertTrue(reloaded.brokenEntries().isEmpty(), reloaded.brokenEntries().toString())
        assertTrue(reloaded.all().all { it.date == day.minusDays(1) })
        assertEquals(1000, root.entryFolders().size)
        assertEquals(listOf("create", "move"), reloaded.history(reloaded.all().first()).map { it.action })
        assertTrue(log.errors.isEmpty(), log.errors.toString())
    }
}
