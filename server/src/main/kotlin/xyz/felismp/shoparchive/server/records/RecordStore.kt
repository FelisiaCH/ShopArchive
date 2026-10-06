package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.UserRef
import xyz.felismp.shoparchive.shared.isUuidV7
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** An entry the server will not touch: [reason] says what is wrong and [paths] where its files are. */
internal class BrokenEntry(val id: String, val reason: String, val paths: List<Path>)

/** An entry staged for [RecordStore.create]: its files, the history and the entry itself. [slipBytes] are keyed by the slip's file name. */
internal class NewEntry(val entry: Entry, val slipBytes: Map<String, ByteArray>, val history: HistoryItem)

/**
 * The entries under `record/yyyy/MM/dd/<id>/`, in memory, and the only code that writes them. Everything that changes the
 * disk runs on [writer], one write at a time. A new entry is built in `data/staging/<id>/` and moved into `record/` with one
 * rename, so `record/` never holds half an entry. Nothing is ever deleted: a folder that cannot be used goes to `data/datafix/`.
 * A change writes its history item first, then the entry: a crash in between leaves at worst an item for a change that did not land.
 * After each write of an `entry.yml` the same bytes go to `cache/records/<id>.yml`. The admin may correct an `entry.yml` by hand:
 * a file that differs from its copy is a hand edit; it becomes a `manual-edit` history item and the entry as it is in memory.
 *
 * (`tmp/` is not used for staging: the launcher empties it at every start, before this recovery could look at what a crash left.)
 */
internal class RecordStore(
    private val root: Path,
    private val exponents: () -> Map<String, Int>,
    val writer: RecordWriter,
    private val files: FileOps = FileOps(),
    private val log: ConfigLog,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val broken = ConcurrentHashMap<String, BrokenEntry>()
    // The entry.yml bytes this store last wrote or took in, per id: what a hand edit is told apart from while running, even when the copy in cache/ could not be written.
    private val written = ConcurrentHashMap<String, ByteArray>()

    private val recordDir: Path = root.resolve("record")
    private val stagingDir: Path = root.resolve("data/staging")
    private val cacheDir: Path = root.resolve("cache/records")

    fun find(id: String): Entry? = entries[id]

    fun brokenEntry(id: String): BrokenEntry? = broken[id]

    fun all(): Collection<Entry> = entries.values

    fun brokenEntries(): List<BrokenEntry> = broken.values.sortedBy { it.id }

    /** `record/yyyy/MM/dd/<id>`. */
    fun dirOf(date: LocalDate, id: String): Path {
        val (year, month, day) = date.toString().split('-')
        return recordDir.resolve(year).resolve(month).resolve(day).resolve(id)
    }

    // --- reading from disk ---

    fun slipBytes(entry: Entry, file: String): ByteArray = Files.readAllBytes(dirOf(entry.date, entry.id).resolve(file))

    /** The items of the entry's `history.yml`. @throws RecordFormatException the file cannot be read */
    fun history(entry: Entry): List<HistoryItem> {
        val text = try {
            String(Files.readAllBytes(dirOf(entry.date, entry.id).resolve("history.yml")), Charsets.UTF_8)
        } catch (e: NoSuchFileException) {
            return emptyList()
        }
        return parseHistory(text)
    }

    /** The number for the next slip file: one above every `slip-N` in the folder, including slips that were taken off the entry (those files stay). */
    fun nextSlipNumber(entry: Entry): Int = Files.newDirectoryStream(dirOf(entry.date, entry.id)).use { stream ->
        (stream.mapNotNull { SLIP_FILE.matchEntire(it.fileName.toString())?.groupValues?.get(1)?.toInt() }.maxOrNull() ?: 0) + 1
    }

    // --- writing; every method here runs on the writer ---

    /** Puts a new entry in place, all of it or nothing. @return the entry as stored */
    fun create(new: NewEntry): Entry = writer.run {
        val entry = new.entry
        val staging = stagingDir.resolve(entry.id)
        if (Files.exists(staging)) quarantine(staging, "staging-${entry.id}", "left over from an earlier try to save the same entry")
        val target = dirOf(entry.date, entry.id)
        Files.createDirectories(staging)
        for ((file, bytes) in new.slipBytes) files.writeNew(staging.resolve(file), bytes)
        files.writeNew(staging.resolve("history.yml"), (renderHistoryHead() + renderHistoryItem(new.history)).toByteArray(Charsets.UTF_8))
        // The entry file last: a staged folder that has it is whole. A failure before the rename leaves the folder in
        // data/staging/ for the next try with this id, or the next start, to set aside.
        val entryBytes = renderEntry(entry).toByteArray(Charsets.UTF_8)
        files.writeNew(staging.resolve("entry.yml"), entryBytes)
        files.fsync(staging)
        files.fsync(stagingDir)
        files.createDirectories(target.parent, upTo = root)
        files.rename(staging, target)
        files.fsync(target.parent)
        writeSnapshot(entry.id, entryBytes)
        entries[entry.id] = entry
        entry
    }

    /**
     * Adds a history item, then writes [updated] over the entry and adds [newSlips].
     * @throws xyz.felismp.shoparchive.api.ApiError the file was changed by hand since the server wrote it; nothing is written
     */
    fun replace(updated: Entry, newSlips: Map<String, ByteArray>, history: HistoryItem): Entry = writer.run {
        entries[updated.id]?.let { requireNoHandEdit(it) }
        val dir = dirOf(updated.date, updated.id)
        appendHistory(dir, history)
        for ((file, bytes) in newSlips) files.writeAtomic(dir.resolve(file), bytes)
        val entryBytes = renderEntry(updated).toByteArray(Charsets.UTF_8)
        files.writeAtomic(dir.resolve("entry.yml"), entryBytes)
        writeSnapshot(updated.id, entryBytes)
        entries[updated.id] = updated
        updated
    }

    /**
     * Adds a history item and writes [updated] (whose date is the new one) in the old folder, then moves the folder to the new date.
     * A crash between the two leaves a folder whose date differs from its entry's; [scan] finishes the move.
     */
    fun move(old: Entry, updated: Entry, history: HistoryItem): Entry = writer.run {
        requireNoHandEdit(old)
        val from = dirOf(old.date, old.id)
        val to = dirOf(updated.date, updated.id)
        if (Files.exists(to)) throw conflict("There is a folder for this entry on ${updated.date} already.", ErrorReasons.ENTRY_MOVE_OCCUPIED)
        appendHistory(from, history)
        val oldFile = Files.readAllBytes(from.resolve("entry.yml"))
        val entryBytes = renderEntry(updated).toByteArray(Charsets.UTF_8)
        files.writeAtomic(from.resolve("entry.yml"), entryBytes)
        try {
            files.createDirectories(to.parent, upTo = root)
            files.rename(from, to)
            files.fsync(from.parent)
            files.fsync(to.parent)
        } catch (e: Exception) {
            // The file says the new date but the folder is still on the old one; put the old file back so the two agree.
            runCatching { files.writeAtomic(from.resolve("entry.yml"), oldFile) }.onFailure { log.error("entry ${old.id}: could not undo a move to ${updated.date}: ${it.message}; the entry is broken until its folder and date agree") }
            throw e
        }
        writeSnapshot(updated.id, entryBytes)
        entries[updated.id] = updated
        updated
    }

    // --- hand edits: entry.yml against the copy in cache/ ---

    private fun snapshotPath(id: String): Path = cacheDir.resolve("$id.yml")

    /** The cache is not precious: a copy that cannot be written is only logged. */
    private fun writeSnapshot(id: String, bytes: ByteArray) {
        written[id] = bytes
        try {
            files.createDirectories(cacheDir, upTo = root)
            files.writeAtomic(snapshotPath(id), bytes)
        } catch (e: Exception) {
            log.warn("entry $id: could not write its copy in cache/records/: ${e.message}")
        }
    }

    /**
     * Compares the `entry.yml` that was read ([bytes], parsed as [entry]) with the copy the server wrote last. [known] is the entry in memory, if any.
     * A file with no copy is taken as it is. A file that differs from its copy is a hand edit: it is added to the history as `manual-edit`.
     * @return whether a hand edit was found; the caller then keeps [entry] instead of [known]
     */
    private fun reconcile(dir: Path, known: Entry?, entry: Entry, bytes: ByteArray): Boolean {
        val snapshot = written[entry.id] ?: try {
            Files.readAllBytes(snapshotPath(entry.id))
        } catch (e: java.io.IOException) {
            null
        }
        if (snapshot != null && snapshot.contentEquals(bytes)) {
            written[entry.id] = bytes
            return false
        }
        val old = if (snapshot == null) known else runCatching { parseEntry(String(snapshot, Charsets.UTF_8), exponents()) }.getOrNull() ?: known
        if (old == null || (snapshot == null && old == entry)) {
            writeSnapshot(entry.id, bytes)
            return false
        }
        val now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS).format(STAMP)
        appendHistory(dir, HistoryItem(now, UserRef("", "manual-edit"), "manual-edit", differences(old, entry)))
        writeSnapshot(entry.id, bytes)
        log.info("entry ${entry.id}: its entry.yml was changed by hand; added to its history as manual-edit")
        return true
    }

    /** Before a change: reads [current]'s file again. A hand edit is taken in (history, memory, copy) and the change is refused; an unreadable file breaks the entry. */
    private fun requireNoHandEdit(current: Entry) {
        val dir = dirOf(current.date, current.id)
        val found = read(dir, current.id, dir.parent)
        if (found.entry == null || found.error != null) {
            entries.remove(current.id)
            markBroken(current.id, found.error!!, listOf(dir))
            throw conflict("The file of this entry was changed by hand and cannot be read. The admin can see why on the server console (records).")
        }
        if (reconcile(dir, current, found.entry, found.bytes!!)) {
            entries[current.id] = found.entry
            throw conflict("The entry was changed by hand; load it again.")
        }
    }

    private fun appendHistory(dir: Path, item: HistoryItem) {
        val path = dir.resolve("history.yml")
        val old = if (Files.exists(path)) String(Files.readAllBytes(path), Charsets.UTF_8) else renderHistoryHead()
        val text = (if (old.endsWith("\n")) old else old + "\n") + renderHistoryItem(item)
        files.writeAtomic(path, text.toByteArray(Charsets.UTF_8))
    }

    // --- start: what is on disk, and what a crash left ---

    /** Reads every entry under `record/` and deals with what is left in `data/staging/`. Entries that cannot be used are set aside in [brokenEntries]. */
    fun load() = writer.run {
        entries.clear()
        broken.clear()
        written.clear()
        scan()
        recoverStaging()
        log.info("record/: ${entries.size} entries loaded" + if (broken.isEmpty()) "" else ", ${broken.size} BROKEN (see: records)")
    }

    /** What [read] found in a folder; [entry] is set with an [error] only when the folder is on the wrong date. [bytes] are those of `entry.yml` when it was read. */
    private class Found(val path: Path, val entry: Entry?, val error: String?, val bytes: ByteArray? = null)

    private fun scan() {
        val found = HashMap<String, MutableList<Found>>()
        for (year in subdirs(recordDir, Regex("\\d{4}"))) for (month in subdirs(year, Regex("\\d{2}"))) for (day in subdirs(month, Regex("\\d{2}"))) {
            for (dir in subdirs(day, Regex(".*"))) {
                val id = dir.fileName.toString()
                if (!isUuidV7(id)) {
                    log.warn("${root.relativize(dir)}: not an entry folder (its name is not an entry id); left alone")
                    continue
                }
                found.getOrPut(id) { mutableListOf() } += read(dir, id, day)
            }
        }
        for ((id, places) in found) {
            when {
                places.size > 1 -> markBroken(id, "the entry exists in ${places.size} folders: ${places.joinToString { root.relativize(it.path).toString() }}", places.map { it.path })
                places[0].error != null && places[0].entry != null && repairDate(places[0]) -> entries[id] = places[0].entry!!
                places[0].error != null -> markBroken(id, places[0].error!!, listOf(places[0].path))
                else -> {
                    reconcile(places[0].path, null, places[0].entry!!, places[0].bytes!!)
                    entries[id] = places[0].entry!!
                }
            }
        }
    }

    /** A crash during [move] leaves a folder on one date and its entry on another: finishes the move, unless the target is taken. @return whether the folder is where its entry says now */
    private fun repairDate(found: Found): Boolean {
        val entry = found.entry!!
        val to = dirOf(entry.date, entry.id)
        if (Files.exists(to)) return false
        try {
            files.createDirectories(to.parent, upTo = root)
            files.rename(found.path, to)
        } catch (e: java.io.IOException) {
            return false
        }
        // Moved: a failure to make that durable stops the start, rather than leaving the entry out of the totals.
        files.fsync(found.path.parent)
        files.fsync(to.parent)
        writeSnapshot(entry.id, found.bytes!!)
        log.warn("entry ${entry.id}: a move was cut short by a stop; the folder was moved from ${root.relativize(found.path)} to ${root.relativize(to)}, where its entry.yml says it belongs")
        return true
    }

    private fun read(dir: Path, id: String, dayDir: Path): Found {
        val where = root.relativize(dir)
        val bytes: ByteArray
        val entry = try {
            bytes = Files.readAllBytes(dir.resolve("entry.yml"))
            parseEntry(String(bytes, Charsets.UTF_8), exponents())
        } catch (e: NoSuchFileException) {
            return Found(dir, null, "$where has no entry.yml")
        } catch (e: RecordFormatException) {
            return Found(dir, null, "$where/${e.message}")
        } catch (e: java.io.IOException) {
            return Found(dir, null, "$where cannot be read: ${e.message}")
        }
        val pathDate = "${dayDir.parent.parent.fileName}-${dayDir.parent.fileName}-${dayDir.fileName}"
        return when {
            entry.id != id -> Found(dir, null, "$where holds the entry ${entry.id}, not $id")
            entry.date.toString() != pathDate -> Found(dir, entry, "$where says its date is ${entry.date}", bytes)
            else -> Found(dir, entry, null, bytes)
        }
    }

    private fun markBroken(id: String, reason: String, paths: List<Path>) {
        broken[id] = BrokenEntry(id, reason, paths)
        log.error("entry $id is BROKEN and is left as it is: $reason")
    }

    private fun recoverStaging() {
        if (!Files.isDirectory(stagingDir)) return
        for (child in subdirs(stagingDir, Regex(".*"), onlyDirectories = false)) {
            val name = child.fileName.toString()
            if (!Files.isDirectory(child) || !isUuidV7(name)) {
                quarantine(child, "staging-$name", "is not a staged entry")
                continue
            }
            if (entries.containsKey(name) || broken.containsKey(name)) {
                quarantine(child, "staging-$name", "the entry exists in record/ already")
                continue
            }
            val entry = stagedEntry(child, name)
            if (entry == null) {
                quarantine(child, "staging-$name", "the staged entry is not whole")
                continue
            }
            val target = dirOf(entry.date, entry.id)
            if (Files.exists(target)) {
                quarantine(child, "staging-$name", "${root.relativize(target)} exists already")
                continue
            }
            files.createDirectories(target.parent, upTo = root)
            files.rename(child, target)
            files.fsync(target.parent)
            entries[entry.id] = entry
            log.warn("entry ${entry.id}: a save was cut short by a stop; it was whole in data/staging/ and is in place now")
        }
    }

    /** The staged entry if its folder is whole: the entry parses, its history is there and every slip it names is there with the right checksum. */
    private fun stagedEntry(dir: Path, id: String): Entry? = try {
        val entry = parseEntry(String(Files.readAllBytes(dir.resolve("entry.yml")), Charsets.UTF_8), exponents())
        val whole = entry.id == id && Files.isRegularFile(dir.resolve("history.yml")) &&
            entry.slips.all { Files.isRegularFile(dir.resolve(it.file)) && sha256Hex(Files.readAllBytes(dir.resolve(it.file))) == it.sha256 }
        entry.takeIf { whole }
    } catch (e: Exception) {
        null
    }

    /** Moves [path] to `data/datafix/<time>/<name>`: it is kept, for the admin to look at, and nothing reads it again. */
    private fun quarantine(path: Path, name: String, why: String) {
        val stamp = LocalDateTime.now(clock).format(DATAFIX_STAMP)
        var attempt = 0
        var target: Path
        do {
            target = root.resolve("data/datafix").resolve(if (attempt == 0) stamp else "$stamp-$attempt").resolve(name)
            attempt++
        } while (Files.exists(target))
        files.createDirectories(target.parent, upTo = root)
        files.rename(path, target)
        log.warn("${root.relativize(path)}: $why; moved to ${root.relativize(target)}")
    }

    private fun subdirs(dir: Path, name: Regex, onlyDirectories: Boolean = true): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.filter { (!onlyDirectories || Files.isDirectory(it)) && name.matches(it.fileName.toString()) }.sortedBy { it.fileName.toString() }
        }
    }

    private companion object {
        val DATAFIX_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
