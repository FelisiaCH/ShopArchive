package xyz.felismp.shoparchive.server.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.server.BarrierTimeoutException
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.config.BackupSettings
import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.fsyncDirectory
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Name of the manifest, the last entry of every backup zip; unzipped into a root it is what `backup restore-slips` reads. */
internal const val MANIFEST_NAME = "backup-manifest.json"

/** The manifest format. Bump it only when an older server could no longer read a newer manifest. */
internal const val MANIFEST_VERSION = 1

@Serializable
internal class ManifestFile(val path: String, val size: Long, val sha256: String)

/** A slip image that is not in the zip: [path] is where it goes back, [mirror] its file name in `backups/slips/`. */
@Serializable
internal class ManifestSlip(val path: String, val size: Long, val sha256: String, val mirror: String)

@Serializable
internal class Manifest(
    val manifestVersion: Int,
    val serverVersion: String,
    val createdAt: String,
    /** `data/server-id` of the server that made the zip: what tells a zip of this server from any other zip in a folder. */
    val serverId: String = "",
    val files: List<ManifestFile>,
    val slips: List<ManifestSlip>,
)

internal val manifestJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

/** How the copy to the second folder went. */
internal sealed interface CopyOutcome {
    data object NotConfigured : CopyOutcome
    data class Done(val zips: Int, val slips: Int) : CopyOutcome
    data class Failed(val reason: String) : CopyOutcome
}

internal sealed interface BackupOutcome {
    val time: Instant

    /** [zip] is the file name in `backups/`. */
    data class Ok(override val time: Instant, val zip: String, val sizeBytes: Long, val newSlips: Int, val copy: CopyOutcome) : BackupOutcome
    data class Failed(override val time: Instant, val reason: String) : BackupOutcome
}

/** A backup that failed; [message] says why. Nothing is left in `backups/` but older, whole backups. */
internal class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The names `<yyyyMMdd-HHmmss>[-n].zip` that this server gives its backups, and so the only zips it ever prunes. */
internal val ZIP_NAME = Regex("(\\d{8}-\\d{6})(-\\d+)?\\.zip")

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val SLIP_NAME = Regex("slip-[1-9][0-9]{0,3}\\.(jpg|png)")

/** Top-level folders left out of the zip: derived (cache, libraries, versions), runtime (logs, tmp), or kept elsewhere (backups, exports) or big and re-downloadable (downloads). */
internal val EXCLUDED_DIRS = setOf("cache", "logs", "tmp", "libraries", "versions", "backups", "exports", "downloads")

/**
 * Top-level files left out: the release itself (the jar and the start scripts come from the release zip, so a restore over a newer
 * release never brings back an older program), and the lock the launcher holds. The manifest of an earlier restore would clash with the new one.
 */
internal val EXCLUDED_FILES = setOf("shoparchive-server.jar", "start.sh", "start.bat", "data/session.lock", MANIFEST_NAME)

/**
 * A backup of the whole root: `backups/<time>.zip` with everything but [EXCLUDED_DIRS] and [EXCLUDED_FILES] and the slip images,
 * and the slip images mirrored once each, by checksum, in `backups/slips/`. [pauseWrites] runs a block while the record writer is held:
 * writes in flight finish first, new ones wait. Only the building of the zip runs inside it. The other files of the root (users, devices,
 * audit) are each replaced whole by an atomic rename, so the zip holds each of them whole, though not all at the same instant.
 */
internal class BackupService(
    private val root: Path,
    private val settings: () -> BackupSettings,
    private val zone: () -> ZoneId,
    private val pauseWrites: (() -> Unit) -> Unit,
    private val log: ConfigLog,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val version: String = "dev",
    private val serverId: String = "",
    private val barrier: DataBarrier = DataBarrier(),
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var last: BackupOutcome? = null

    // Checksums of slips already read, so a day's backup reads only the slips added since: slips are never rewritten.
    private val hashed = HashMap<Path, Triple<Long, Long, String>>()

    private val backups: Path = root.resolve("backups")
    private val slipMirror: Path = backups.resolve("slips")

    val isRunning: Boolean get() = running.get()

    /** The outcome of the last backup since the server started, else the newest zip on disk, else null. */
    fun lastOutcome(): BackupOutcome? = last ?: newestZip()?.let { (name, time) ->
        BackupOutcome.Ok(time, name, runCatching { Files.size(backups.resolve(name)) }.getOrDefault(0), 0, CopyOutcome.NotConfigured)
    }

    /** The time of the newest backup, whether it was made by this run of the server or an earlier one. */
    fun lastSuccess(): Instant? = (last as? BackupOutcome.Ok)?.time ?: newestZip()?.second

    private fun newestZip(): Pair<String, Instant>? = zipNames().lastOrNull()?.let { name ->
        val stamp = ZIP_NAME.matchEntire(name)!!.groupValues[1]
        val time = runCatching { LocalDateTime.parse(stamp, STAMP).atZone(zone()).toInstant() }.getOrNull() ?: return null
        name to time
    }

    /** The backup zips in `backups/`, oldest first. */
    private fun zipNames(): List<String> = listZips(backups)

    /**
     * Makes one backup, or returns null at once if one is already running. Never throws for a failed backup:
     * the failure is in the result (and logged), and the next run starts afresh.
     */
    fun run(): BackupOutcome? {
        if (!running.compareAndSet(false, true)) return null
        try {
            val outcome = try {
                makeBackup()
            } catch (e: BackupException) {
                BackupOutcome.Failed(clock.instant(), e.message ?: "backup failed")
            } catch (e: Exception) {
                BackupOutcome.Failed(clock.instant(), "${e.javaClass.simpleName}: ${e.message}")
            }
            last = outcome
            if (outcome is BackupOutcome.Failed) log.error("Backup failed: ${outcome.reason}")
            return outcome
        } finally {
            running.set(false)
        }
    }

    private fun makeBackup(): BackupOutcome.Ok {
        val settings = settings()
        val started = clock.instant()
        try {
            requireNoLinks(realRoot(), realRoot().resolve("backups/slips"), "backups/slips")
            Files.createDirectories(slipMirror)
            // The zip holds the keys and credential hashes the originals keep owner-only, so nobody else may read in here.
            secure(backups, "rwx------")
        } catch (e: IOException) {
            throw BackupException("cannot make ${root.relativize(slipMirror)}: ${e.message}", e)
        }
        // A zip.part is only ever a backup that did not finish (a crash, a full disk): nothing else is kept in it.
        Files.newDirectoryStream(backups, "*.zip.part").use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        Files.newDirectoryStream(slipMirror, "*.part").use { stream -> stream.forEach { Files.deleteIfExists(it) } }

        // Slips never change once written, so the bulk of them is mirrored before the pause; the pause only meets the few that are new.
        val newSlipsBefore = mirrorAllSlips()

        val name = zipName(started)
        val part = backups.resolve("$name.part")
        var newSlipsInPause = 0
        try {
            // Lock order: the record writer (pauseWrites), then the data barrier; see DataBarrier.
            pauseWrites {
                try {
                    barrier.freeze(settings.pauseTimeoutSeconds * 1000L) { newSlipsInPause = writeZip(part) }
                } catch (e: BarrierTimeoutException) {
                    throw BackupException("${e.message}; nothing was backed up", e)
                }
            }
            FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(part, backups.resolve(name), StandardCopyOption.ATOMIC_MOVE)
            fsyncDirectory(backups)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(part) }
            throw if (e is BackupException) e else BackupException("cannot write $name: ${e.message}", e)
        }
        val size = Files.size(backups.resolve(name))
        prune(backups, settings.keep)
        val copy = copyOut(settings)
        log.info("Backup $name done: ${formatSize(size)}, ${newSlipsBefore + newSlipsInPause} new slips")
        return BackupOutcome.Ok(started, name, size, newSlipsBefore + newSlipsInPause, copy)
    }

    private fun realRoot(): Path = try {
        root.toRealPath()
    } catch (e: IOException) {
        throw BackupException("cannot resolve the server folder: ${e.message}", e)
    }

    private fun zipName(now: Instant): String {
        val stamp = LocalDateTime.ofInstant(now, zone()).format(STAMP)
        // Two backups in one second get -2, -3, ...: a backup is never replaced.
        return generateSequence(1) { it + 1 }.map { if (it == 1) "$stamp.zip" else "$stamp-$it.zip" }
            .first { !Files.exists(backups.resolve(it)) && !Files.exists(backups.resolve("$it.part")) }
    }

    // --- the zip ---

    private class Walked(val files: MutableList<ManifestFile> = mutableListOf(), val slips: MutableList<ManifestSlip> = mutableListOf())

    /** Writes [part], manifest last. Returns how many slips were new to the mirror. Runs while the writers are held. */
    private fun writeZip(part: Path): Int {
        val walked = Walked()
        var fresh = 0
        Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
            secure(part, "rw-------") // before the first byte; a copy of the zip (copy-to) gets the mode of this file
            ZipOutputStream(out).use { zip ->
                zip.setLevel(6)
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir == root) return FileVisitResult.CONTINUE
                        if (attrs.isSymbolicLink) return skipLink(dir)
                        val relative = relativeName(dir)
                        return if ('/' !in relative && relative in EXCLUDED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // A link is not followed, so nothing outside the root ends up in the zip (the launcher refuses links below the root anyway).
                        if (attrs.isSymbolicLink) return skipLink(file)
                        if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                        val relative = relativeName(file)
                        val fileName = file.fileName.toString()
                        if (relative in EXCLUDED_FILES || fileName.endsWith(".tmp") || fileName.endsWith(".part")) return FileVisitResult.CONTINUE
                        if (relative.startsWith("record/") && SLIP_NAME.matches(fileName)) {
                            val (sha, wasNew) = mirrorSlip(file, attrs)
                            if (wasNew) fresh++
                            walked.slips += ManifestSlip(relative, attrs.size(), sha, mirrorName(sha, fileName))
                            return FileVisitResult.CONTINUE
                        }
                        addFile(zip, file, relative, attrs, walked)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        // Gone since the directory was listed (a file replaced by a writer that is not held, e.g. a .tmp): not part of this backup.
                        if (exc is NoSuchFileException) return FileVisitResult.CONTINUE
                        throw BackupException("cannot read ${relativeName(file)}: ${exc.message}", exc)
                    }
                })
                val manifest = Manifest(MANIFEST_VERSION, version, clock.instant().toString(), serverId, walked.files, walked.slips)
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifestJson.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return fresh
    }

    /** Owner-only access on a filesystem that has POSIX permissions (not Windows); nothing happens elsewhere. */
    private fun secure(path: Path, mode: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        } catch (_: UnsupportedOperationException) {
            // no POSIX permissions here
        }
    }

    private fun skipLink(path: Path): FileVisitResult {
        log.warn("Backup: ${relativeName(path)} is a link and is left out")
        return FileVisitResult.CONTINUE
    }

    private fun relativeName(path: Path): String = root.relativize(path).joinToString("/") { it.toString() }

    private fun addFile(zip: ZipOutputStream, file: Path, relative: String, attrs: BasicFileAttributes, walked: Walked) {
        val entry = ZipEntry(relative).apply { setLastModifiedTime(attrs.lastModifiedTime()) }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                // Opened before the entry starts, so a file that cannot be read leaves no half entry behind.
                zip.putNextEntry(entry)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    zip.write(buffer, 0, read)
                    size += read
                }
                zip.closeEntry()
            }
        } catch (e: NoSuchFileException) {
            throw BackupException("$relative went away while it was being read", e)
        } catch (e: IOException) {
            throw BackupException("cannot read $relative: ${e.message}", e)
        }
        walked.files += ManifestFile(relative, size, hex(digest.digest()))
    }

    // --- slips ---

    private fun mirrorName(sha: String, slipFileName: String) = sha + slipFileName.substring(slipFileName.lastIndexOf('.'))

    /** Walks `record/` and mirrors every slip not mirrored yet. Returns how many were new. */
    private fun mirrorAllSlips(): Int {
        var fresh = 0
        val recordDir = root.resolve("record")
        if (!Files.isDirectory(recordDir, LinkOption.NOFOLLOW_LINKS)) return 0
        Files.walkFileTree(recordDir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !attrs.isSymbolicLink && SLIP_NAME.matches(file.fileName.toString())) {
                    if (mirrorSlip(file, attrs).second) fresh++
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException) =
                if (exc is NoSuchFileException) FileVisitResult.CONTINUE else throw BackupException("cannot read ${relativeName(file)}: ${exc.message}", exc)
        })
        return fresh
    }

    /**
     * Makes sure [file] is in `backups/slips/<sha256>.<ext>`: checksum it, and if the mirror has no such file copy it
     * to `<name>.part` while checking the copy has the same checksum, fsync, and rename atomically. Returns the checksum and whether the mirror got a new file.
     */
    private fun mirrorSlip(file: Path, attrs: BasicFileAttributes): Pair<String, Boolean> {
        hashed[file]?.let { (size, time, sha) ->
            if (size == attrs.size() && time == attrs.lastModifiedTime().toMillis() && Files.isRegularFile(slipMirror.resolve(mirrorName(sha, file.fileName.toString())))) return sha to false
        }
        val sha = try {
            sha256(file)
        } catch (e: IOException) {
            throw BackupException("cannot read ${relativeName(file)}: ${e.message}", e)
        }
        val target = slipMirror.resolve(mirrorName(sha, file.fileName.toString()))
        var isNew = false
        if (!Files.exists(target)) {
            val part = slipMirror.resolve(target.fileName.toString() + ".part")
            try {
                val copied = MessageDigest.getInstance("SHA-256")
                Files.deleteIfExists(part)
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                    FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied.update(buffer, 0, read)
                            channel.write(java.nio.ByteBuffer.wrap(buffer, 0, read))
                        }
                        channel.force(true)
                    }
                }
                if (hex(copied.digest()) != sha) throw BackupException("${relativeName(file)} changed while it was being copied")
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
                fsyncDirectory(slipMirror)
                isNew = true
            } catch (e: IOException) {
                runCatching { Files.deleteIfExists(part) }
                throw BackupException("cannot copy ${relativeName(file)} to ${root.relativize(slipMirror)}: ${e.message}", e)
            } catch (e: BackupException) {
                runCatching { Files.deleteIfExists(part) }
                throw e
            }
        }
        hashed[file] = Triple(attrs.size(), attrs.lastModifiedTime().toMillis(), sha)
        return sha to isNew
    }

    // --- keeping N, copying out ---

    /**
     * Deletes the oldest of this server's backup zips in [dir] until [keep] are left. A zip is this server's only if its
     * `backup-manifest.json` has this server's id: a zip of another server, or any file that only has a name like ours, is never
     * touched (one that cannot be read is kept and warned about).
     */
    private fun prune(dir: Path, keep: Int) {
        val ours = listZips(dir).filter { isOurs(dir.resolve(it)) }
        for (name in ours.dropLast(keep)) {
            try {
                Files.deleteIfExists(dir.resolve(name))
            } catch (e: IOException) {
                log.warn("Backup: cannot delete the old backup $name in $dir: ${e.message}")
            }
        }
    }

    private fun isOurs(zip: Path): Boolean = try {
        ZipFile(zip.toFile()).use { file ->
            val entry = file.getEntry(MANIFEST_NAME) ?: return false.also { log.warn("Backup: $zip has no $MANIFEST_NAME; it is not touched") }
            manifestJson.decodeFromString(Manifest.serializer(), file.getInputStream(entry).readBytes().toString(Charsets.UTF_8)).serverId == serverId
        }
    } catch (e: Exception) {
        log.warn("Backup: $zip cannot be read (${e.message}); it is kept")
        false
    }

    private fun copyOut(settings: BackupSettings): CopyOutcome {
        if (settings.copyTo.isEmpty()) return CopyOutcome.NotConfigured
        val target = root.resolve(settings.copyTo).normalize()
        return try {
            // Never created: a missing folder is a drive that is not plugged in, and creating it would fill the disk the root is on.
            if (!Files.isDirectory(target)) throw BackupException("the folder does not exist or is not a folder")
            // Our own subfolder, so that nothing of ours (and nothing we prune) is ever among the other files of that folder.
            val realBase = target.toRealPath()
            val realTarget = realBase.resolve("ShopArchive-$serverId")
            val slipDir = realTarget.resolve("slips")
            requireNoLinks(realBase, slipDir, "$target/ShopArchive-$serverId/slips")
            Files.createDirectories(slipDir)
            var slips = 0
            Files.newDirectoryStream(slipMirror).use { stream ->
                for (source in stream) {
                    val name = source.fileName.toString()
                    if (name.endsWith(".part") || !Files.isRegularFile(source)) continue
                    val destination = slipDir.resolve(name)
                    // The name is the checksum, so a file of that name that is there is the same file.
                    if (Files.exists(destination)) continue
                    copyWhole(source, destination)
                    slips++
                }
            }
            var zips = 0
            for (name in zipNames()) {
                if (Files.exists(realTarget.resolve(name))) continue
                copyWhole(backups.resolve(name), realTarget.resolve(name))
                zips++
            }
            prune(realTarget, settings.keep)
            CopyOutcome.Done(zips, slips)
        } catch (e: Exception) {
            val reason = if (e is BackupException) e.message ?: "failed" else "${e.javaClass.simpleName}: ${e.message}"
            log.warn("Backup: could not copy to ${settings.copyTo}: $reason. The next backup tries again.")
            CopyOutcome.Failed(reason)
        }
    }

    /** [source] as [destination], through `<destination>.part`: fsynced, then renamed. */
    private fun copyWhole(source: Path, destination: Path) {
        val part = destination.resolveSibling(destination.fileName.toString() + ".part")
        try {
            Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(part) }
            throw e
        }
        fsyncDirectory(destination.parent)
    }

    /** [source] as [target], which must not exist: through `<target>.part`, fsynced, then a rename that refuses an existing target. */
    private fun publishNew(source: Path, target: Path) {
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        try {
            Files.deleteIfExists(part)
            Files.copy(source, part)
            FileChannel.open(part, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(part, target) // no REPLACE_EXISTING: FileAlreadyExistsException if something is there
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(part) }
            throw e
        }
        fsyncDirectory(target.parent)
    }

    // --- restore ---

    /** What [restoreSlips] did: slips put back, slips that were there already, and the paths it could not restore, each with why. */
    internal class RestoreReport(val restored: Int, val alreadyThere: Int, val problems: List<String>)

    /**
     * Puts the slip images named in `backup-manifest.json` of this root (it arrives with the unzipped backup) back from [slipsDir]
     * (`backups/slips` of the old root, or `slips` of the copy-to folder). A file that is there already is left alone, and checked.
     */
    fun restoreSlips(slipsDir: Path): RestoreReport {
        val manifestFile = root.resolve(MANIFEST_NAME)
        val manifest = try {
            manifestJson.decodeFromString(Manifest.serializer(), Files.readString(manifestFile))
        } catch (e: NoSuchFileException) {
            throw BackupException("$MANIFEST_NAME is not in the server folder: unzip the backup into it first")
        } catch (e: Exception) {
            throw BackupException("$MANIFEST_NAME cannot be read: ${e.message}", e)
        }
        if (manifest.manifestVersion > MANIFEST_VERSION) throw BackupException("$MANIFEST_NAME is version ${manifest.manifestVersion}, newer than this server understands")
        if (!Files.isDirectory(slipsDir)) throw BackupException("$slipsDir is not a folder")
        var restored = 0
        var already = 0
        val problems = mutableListOf<String>()
        val realRoot = realRoot()
        for (slip in manifest.slips) {
            val target = realRoot.resolve(slip.path).normalize()
            // A manifest is data from a file: never let it write outside the root, or in a place other than record/.
            if (!target.startsWith(realRoot.resolve("record")) || !SLIP_NAME.matches(target.fileName.toString())) {
                problems += "${slip.path}: not a slip path"
                continue
            }
            try {
                requireNoLinks(realRoot, target.parent, "${slip.path}'s folder")
            } catch (e: BackupException) {
                problems += "${slip.path}: ${e.message}"
                continue
            }
            val source = slipsDir.resolve(slip.mirror).normalize()
            if (source.parent != slipsDir.normalize() || !Files.isRegularFile(source)) {
                // Not needed when the slip is there already; the check below tells.
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    problems += "${slip.path}: ${slip.mirror} is not in $slipsDir"
                    continue
                }
            }
            // On the record writer, one slip at a time: an entry being edited right now may have just written a slip of this very name
            // (the next number comes from the folder), so the look at the target and the publish are one step no write can come between.
            pauseWrites {
                try {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        if (runCatching { sha256(target) }.getOrNull() == slip.sha256) already++ else problems += "${slip.path}: a different file is there (a conflict), left as it is"
                    } else if (sha256(source) != slip.sha256) {
                        problems += "${slip.path}: ${slip.mirror} does not match its checksum, not restored"
                    } else {
                        Files.createDirectories(target.parent)
                        publishNew(source, target)
                        restored++
                    }
                } catch (e: IOException) {
                    problems += "${slip.path}: ${e.message}"
                }
            }
        }
        return RestoreReport(restored, already, problems)
    }

    /** The lines `status` adds. */
    fun statusLines(): List<String> {
        val settings = settings()
        val lines = mutableListOf<String>()
        val zone = zone()
        fun at(time: Instant) = LocalDateTime.ofInstant(time, zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        when (val outcome = lastOutcome()) {
            null -> lines += "Backup: none yet"
            is BackupOutcome.Ok -> {
                lines += "Backup: last ${at(outcome.time)} ok, ${formatSize(outcome.sizeBytes)} (${outcome.zip})"
                lines += "Backup copy-to: " + when (val copy = outcome.copy) {
                    is CopyOutcome.NotConfigured -> if (settings.copyTo.isEmpty()) "not set" else "not tried yet"
                    is CopyOutcome.Done -> "ok (${copy.zips} zips, ${copy.slips} slips copied)"
                    is CopyOutcome.Failed -> "FAILED, ${copy.reason}"
                }
            }
            is BackupOutcome.Failed -> {
                lines += "Backup: last ${at(outcome.time)} FAILED, ${outcome.reason}"
                newestZip()?.let { (name, time) -> lines += "Backup: newest good one ${at(time)} ($name)" }
            }
        }
        lines += if (settings.enabled) "Backup: daily at ${String.format(java.util.Locale.ROOT, "%02d:%02d", settings.time.hour, settings.time.minute)}, keep ${settings.keep}" else "Backup: daily backup is off (backup.enabled)"
        return lines
    }
}

/** The zip names in [dir], oldest first; empty if [dir] cannot be listed. */
internal fun listZips(dir: Path): List<String> = try {
    Files.newDirectoryStream(dir).use { stream ->
        stream.map { it.fileName.toString() }.filter { ZIP_NAME.matches(it) }
            .sortedBy { name -> ZIP_NAME.matchEntire(name)!!.let { it.groupValues[1] + "-" + (it.groupValues[2].removePrefix("-").ifEmpty { "0" }).padStart(6, '0') } }
    }
} catch (e: IOException) {
    emptyList()
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1L shl 20).toDouble())
    else -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
}

/**
 * Fails if [dir] (at or below [base], which must be a real path) is, or lies behind, a link (symlink or junction, dangling or not):
 * the deepest part of it that exists must be its own real path. Called before anything is created or written there, so a link
 * planted below the root (or below the copy-to folder) cannot send a write somewhere else.
 */
internal fun requireNoLinks(base: Path, dir: Path, what: String) {
    if (!dir.startsWith(base)) throw BackupException("$what is not inside $base")
    var existing = dir
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return
    val real = try {
        existing.toRealPath()
    } catch (e: IOException) {
        throw BackupException("$what is a link that cannot be resolved; no links are allowed there", e)
    }
    if (real != existing) throw BackupException("$what is, or lies behind, a link (it resolves to $real); no links are allowed there")
}

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input -> DigestInputStream(input as InputStream, digest).use { it.copyTo(java.io.OutputStream.nullOutputStream()) } }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
