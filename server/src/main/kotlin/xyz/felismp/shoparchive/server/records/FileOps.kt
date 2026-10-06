package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.server.fsyncDirectory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The disk would not take a rename for [RetryPolicy.giveUpAfterMs]; nothing was changed. The client is told to try again. */
internal class StorageBusyException(message: String, cause: Throwable) : IOException(message, cause)

/**
 * How a rename that fails is tried again: on Windows an antivirus scan or an indexer may hold a file for a moment and the
 * rename is refused until it lets go. The wait starts at [firstDelayMs], doubles up to [maxDelayMs], and the rename is given
 * up after [giveUpAfterMs] in all. Elsewhere a refused rename is a real error and is not tried again ([enabled] false).
 */
internal class RetryPolicy(
    val enabled: Boolean = System.getProperty("os.name").startsWith("Windows"),
    val firstDelayMs: Long = 1,
    val maxDelayMs: Long = 2_000,
    val giveUpAfterMs: Long = 10_000,
    val sleep: (Long) -> Unit = Thread::sleep,
)

/**
 * Every write below `record/` goes through here: whole files are written to a temp name, fsynced and renamed over the target;
 * a directory is moved with one rename; a rename that the system refuses for a moment is tried again. Reading and writing is NIO only.
 */
internal class FileOps(
    private val retry: RetryPolicy = RetryPolicy(),
    private val move: (Path, Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) },
    private val syncDirectory: (Path) -> Unit = ::fsyncDirectory,
) {
    /** Writes a new file (it must not exist) and fsyncs it. For files inside a folder that is not in place yet. */
    fun writeNew(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    /** Replaces (or makes) [path] with [bytes]: a crash leaves the old file or the new one, never half of one. */
    fun writeAtomic(path: Path, bytes: ByteArray) {
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        // A .tmp left by a crash must not block this write; it is only ever our own half-written copy.
        Files.deleteIfExists(temp)
        writeNew(temp, bytes)
        rename(temp, path)
        fsyncDirectory(path.parent)
    }

    /** Moves a file or folder to a name that does not exist yet, atomically. */
    fun rename(from: Path, to: Path) {
        var delay = retry.firstDelayMs
        var waited = 0L
        while (true) {
            try {
                move(from, to)
                return
            } catch (e: FileAlreadyExistsException) {
                throw e
            } catch (e: NoSuchFileException) {
                throw e
            } catch (e: DirectoryNotEmptyException) {
                throw e
            } catch (e: FileSystemException) {
                // AccessDeniedException, or the generic "used by another process".
                if (!retry.enabled || (e !is AccessDeniedException && e.javaClass != FileSystemException::class.java)) throw e
                if (waited + delay > retry.giveUpAfterMs) throw StorageBusyException("The disk would not rename ${from.fileName} for ${waited / 1000} s: ${e.message}", e)
                retry.sleep(delay)
                waited += delay
                delay = minOf(delay * 2, retry.maxDelayMs)
            }
        }
    }

    /** Makes [dir] and what is missing above it, and makes the new directory entries durable (not possible on Windows, and not needed there). */
    fun createDirectories(dir: Path, upTo: Path) {
        val missing = generateSequence(dir) { it.parent }.takeWhile { it != upTo && !Files.exists(it) }.toList()
        Files.createDirectories(dir)
        for (created in missing.asReversed()) fsyncDirectory(created.parent)
    }

    fun fsync(dir: Path) = syncDirectory(dir)
}
