package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.parseYamlMap
import xyz.felismp.shoparchive.server.users.quoted
import xyz.felismp.shoparchive.shared.isSlug
import xyz.felismp.shoparchive.shared.isUuidV7
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The day notes under `record/notes/<branch>/<id>.yml`, in memory, and the only code that writes them (on the record writer, like the entries).
 * A note is written once and never changed or deleted; a file that cannot be read is logged and left as it is.
 */
internal class NoteStore(
    private val root: Path,
    private val writer: RecordWriter,
    private val files: FileOps,
    private val log: ConfigLog,
) {
    private val notes = ConcurrentHashMap<String, Note>()
    private val dir: Path = root.resolve("record/notes")

    fun find(id: String): Note? = notes[id]

    fun all(): Collection<Note> = notes.values

    /** The notes of [branch] on [date], oldest first. */
    fun onDay(branch: String, date: LocalDate): List<Note> =
        notes.values.filter { it.branch == branch && it.date == date }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    /** Writes the new [note] and makes it the stored one. */
    fun save(note: Note): Note = writer.run {
        val branchDir = dir.resolve(note.branch)
        files.createDirectories(branchDir, upTo = root)
        files.writeAtomic(branchDir.resolve("${note.id}.yml"), renderNote(note).toByteArray(Charsets.UTF_8))
        notes[note.id] = note
        note
    }

    fun load() = writer.run {
        notes.clear()
        if (Files.isDirectory(dir)) {
            Files.newDirectoryStream(dir).use { branches -> branches.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() } }.forEach(::loadBranch)
        }
        log.info("record/notes/: ${notes.size} day notes loaded")
    }

    private fun loadBranch(branchDir: Path) {
        val branch = branchDir.fileName.toString()
        if (!isSlug(branch)) {
            log.warn("${root.relativize(branchDir)}: not a branch folder; left alone")
            return
        }
        val paths = Files.newDirectoryStream(branchDir, "*.yml").use { it.sortedBy { p -> p.fileName.toString() } }
        for (path in paths) {
            val where = root.relativize(path)
            val id = path.fileName.toString().removeSuffix(".yml")
            try {
                val note = parseNote(String(Files.readAllBytes(path), Charsets.UTF_8))
                if (note.id != id || note.branch != branch || !isUuidV7(id)) {
                    log.error("$where: holds the note ${note.id} of branch ${note.branch}, which does not match its name; left alone and not used")
                } else {
                    notes[id] = note
                }
            } catch (e: RecordFormatException) {
                log.error("$where: ${e.message}; left alone and not used")
            }
        }
    }
}

/** Who read a note, and when. */
internal data class NoteReader(val id: String, val name: String)

/**
 * Which notes each user has read, in `data/notes-read/<user id>.yml` (one file per user, rewritten whole and atomically), in memory.
 * Not a record: it can be rewritten, so it is not below `record/`. Changes run on the record writer, so a backup (which pauses it) sees whole files.
 */
internal class NoteReadStore(
    private val root: Path,
    private val writer: RecordWriter,
    private val files: FileOps,
    private val log: ConfigLog,
) {
    /** What one user's file holds: the name the user had at the last mark, and note id -> time read. */
    private class Reads(val name: String, val at: Map<String, String>)

    private val reads = ConcurrentHashMap<String, Reads>()
    private val dir: Path = root.resolve("data/notes-read")

    /** The time [userId] read [noteId], or null. */
    fun readAt(userId: String, noteId: String): String? = reads[userId]?.at?.get(noteId)

    /** Who read [noteId], in the order of the times (then by user id). */
    fun readers(noteId: String): List<Pair<NoteReader, String>> =
        reads.entries.mapNotNull { (id, r) -> r.at[noteId]?.let { NoteReader(id, r.name) to it } }.sortedWith(compareBy({ it.second }, { it.first.id }))

    /** Marks [noteIds] as read by the user at [at]; those read before keep their time. Writes the file only when something is new. */
    fun mark(userId: String, userName: String, noteIds: Collection<String>, at: String) = writer.run {
        val before = reads[userId]
        val fresh = noteIds.filter { before?.at?.containsKey(it) != true }
        if (fresh.isEmpty()) return@run
        val merged = LinkedHashMap(before?.at.orEmpty()).also { m -> fresh.forEach { m[it] = at } }
        files.createDirectories(dir, upTo = root)
        files.writeAtomic(dir.resolve("$userId.yml"), render(userId, userName, merged).toByteArray(Charsets.UTF_8))
        reads[userId] = Reads(userName, merged)
    }

    fun load() = writer.run {
        reads.clear()
        if (Files.isDirectory(dir)) {
            for (path in Files.newDirectoryStream(dir, "*.yml").use { it.sortedBy { p -> p.fileName.toString() } }) {
                val where = root.relativize(path)
                val userId = path.fileName.toString().removeSuffix(".yml")
                try {
                    reads[userId] = parse(userId, String(Files.readAllBytes(path), Charsets.UTF_8))
                } catch (e: RecordFormatException) {
                    log.error("$where: ${e.message}; left alone and not used")
                }
            }
        }
        log.info("data/notes-read/: the reads of ${reads.size} users loaded")
    }

    private fun render(userId: String, name: String, at: Map<String, String>) = buildString {
        append("file-version: $RECORD_FILE_VERSION\n")
        append("user: ${quoted(userId)}\n")
        append("name: ${quoted(name)}\n")
        append("read:\n")
        for ((note, time) in at) append("  ${quoted(note)}: ${quoted(time)}\n")
    }

    private fun parse(userId: String, text: String): Reads {
        val map = try {
            parseYamlMap("notes-read", text)
        } catch (e: xyz.felismp.shoparchive.server.config.ConfigFileException) {
            throw RecordFormatException(e.message ?: "cannot be parsed")
        }
        val version = (map["file-version"] as? String)?.trim()?.toIntOrNull() ?: throw RecordFormatException("file-version is missing or is not a number")
        if (version < 1 || version > RECORD_FILE_VERSION) throw RecordFormatException("file-version $version is newer than this server knows")
        if (map["user"] != userId) throw RecordFormatException("holds the reads of another user than its name says")
        val name = map["name"] as? String ?: throw RecordFormatException("'name' is missing or is not text")
        @Suppress("UNCHECKED_CAST")
        val at = (map["read"] as? Map<String, Any?>)?.mapValues { it.value as? String ?: throw RecordFormatException("a time read is not text") }
            ?: throw RecordFormatException("'read' is missing or is not a mapping")
        return Reads(name, at)
    }
}
