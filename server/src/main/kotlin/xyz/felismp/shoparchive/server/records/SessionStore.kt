package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.shared.isSlug
import xyz.felismp.shoparchive.shared.isUuidV7
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The day sessions under `record/sessions/<branch>/<id>.yml`, in memory, and the only code that writes them (on the record writer,
 * like the entries). A branch has at most one open session; a file that cannot be read is logged and left as it is.
 */
internal class SessionStore(
    private val root: Path,
    private val exponents: () -> Map<String, Int>,
    private val writer: RecordWriter,
    private val files: FileOps,
    private val log: ConfigLog,
) {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val dir: Path = root.resolve("record/sessions")

    fun find(id: String): Session? = sessions[id]

    /** The branch's open session, if any. */
    fun openFor(branch: String): Session? = sessions.values.filter { it.branch == branch && it.closed == null }.maxByOrNull { it.openedAt }

    fun all(): Collection<Session> = sessions.values

    fun inRange(branch: String, from: LocalDate, to: LocalDate): List<Session> =
        sessions.values.filter { it.branch == branch && it.businessDate in from..to }.sortedWith(compareBy({ it.businessDate }, { it.openedAt }, { it.id }))

    /** Writes [session] (new, or the same session closed) and makes it the stored one. */
    fun save(session: Session): Session = writer.run {
        val branchDir = dir.resolve(session.branch)
        files.createDirectories(branchDir, upTo = root)
        files.writeAtomic(branchDir.resolve("${session.id}.yml"), renderSession(session).toByteArray(Charsets.UTF_8))
        sessions[session.id] = session
        session
    }

    fun load() = writer.run {
        sessions.clear()
        if (Files.isDirectory(dir)) {
            Files.newDirectoryStream(dir).use { branches -> branches.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() } }.forEach(::loadBranch)
        }
        val open = sessions.values.count { it.closed == null }
        log.info("record/sessions/: ${sessions.size} day sessions loaded, $open open")
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
                val session = parseSession(String(Files.readAllBytes(path), Charsets.UTF_8), exponents())
                if (session.id != id || session.branch != branch || !isUuidV7(id)) {
                    log.error("$where: holds the session ${session.id} of branch ${session.branch}, which does not match its name; left alone and not used")
                } else {
                    sessions[id] = session
                }
            } catch (e: RecordFormatException) {
                log.error("$where: ${e.message}; left alone and not used")
            }
        }
        val open = sessions.values.filter { it.branch == branch && it.closed == null }
        if (open.size > 1) log.warn("branch $branch has ${open.size} open sessions (${open.joinToString { it.id }}); the newest is used")
    }
}
