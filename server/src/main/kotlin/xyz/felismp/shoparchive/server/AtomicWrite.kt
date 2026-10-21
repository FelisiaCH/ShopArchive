package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.server.records.FileOps
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The one way ShopArchive writes a whole file: `<name>.tmp` next to it, fsync, atomic rename over the
 * target, fsync of the directory. A crash leaves either the old file or the new one, never half of one.
 * The rename is tried again on Windows when an antivirus scan or indexer holds the file for a moment
 * (see [FileOps.rename]).
 */
internal fun writeAtomically(path: Path, bytes: ByteArray, files: FileOps = sharedFileOps) = files.writeAtomic(path, bytes)

private val sharedFileOps = FileOps()

/**
 * The file content is fsynced on write, but the rename is a separate directory-entry write that isn't
 * durable until the directory itself is fsynced too - without this, a crash right after move() can
 * still lose the rename on some filesystems (for server-id: the next boot mints a *new* id). Windows has
 * no way to open a directory as a FileChannel, so that failure is expected there and ignored; on any
 * other OS it propagates, so boot fails loudly instead of running with a non-durable file.
 */
internal fun fsyncDirectory(dir: Path) {
    try {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    } catch (e: IOException) {
        if (!System.getProperty("os.name").startsWith("Windows")) throw e
    }
}
