package xyz.felismp.shoparchive.server

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The one way ShopArchive writes a whole file: `<name>.tmp` next to it, fsync, atomic rename over the
 * target, fsync of the directory. A crash leaves either the old file or the new one, never half of one.
 */
internal fun writeAtomically(path: Path, bytes: ByteArray) {
    val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
    // A .tmp left by a crash right after CREATE_NEW must not block this write. Deleting it first (rather
    // than opening with REPLACE_EXISTING) keeps CREATE_NEW's guarantee that we never write over a file
    // mid-write by some other process; TRUNCATE_EXISTING would silently accept that instead.
    Files.deleteIfExists(tmp)
    FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer) // a single write() may not take the whole buffer
        channel.force(true) // fsync: the content must survive a crash immediately after this write
    }
    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE)
    fsyncDirectory(path.parent)
}

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
