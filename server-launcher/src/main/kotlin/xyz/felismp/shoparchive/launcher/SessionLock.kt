package xyz.felismp.shoparchive.launcher

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

// Held for the process lifetime: local vals would be eligible for GC, which silently releases the lock.
private var lockChannel: FileChannel? = null
private var sessionLock: FileLock? = null

/** Refuses to start if another ShopArchive instance already holds `data/session.lock` in this root. */
fun acquireSessionLock(root: Path) {
    val lockPath = root.resolve("data/session.lock")
    val channel = try {
        Files.createDirectories(lockPath.parent)
        RandomAccessFile(lockPath.toFile(), "rw").channel
    } catch (e: IOException) {
        failCannotWriteRoot(root, e)
    }

    val lock = try {
        channel.tryLock()
    } catch (e: OverlappingFileLockException) {
        null
    } catch (e: IOException) {
        // Some filesystems report a held lock this way instead of returning null - same "already
        // running / cannot write" message either way, not a raw stack trace.
        null
    }

    if (lock == null) {
        System.err.println("[ERROR] another ShopArchive server is already running in $root")
        exitProcess(1)
    }

    lockChannel = channel
    sessionLock = lock
}
