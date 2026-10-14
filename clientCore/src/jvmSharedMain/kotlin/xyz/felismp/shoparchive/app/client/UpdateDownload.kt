package xyz.felismp.shoparchive.app.client

import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Fetches [file] into [target] (through `<target>.part`, then moved into place), reporting the bytes so far. A failed download leaves nothing at [target]. */
suspend fun UpdatesApi.downloadUpdate(file: String, target: Path, onProgress: (Long) -> Unit) {
    val partial = target.resolveSibling(target.fileName.toString() + ".part")
    try {
        downloadUpdate(file, {
            val out = Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            object : UpdateSink {
                override fun write(bytes: ByteArray, count: Int) = out.write(bytes, 0, count)
                override fun close() = out.close()
            }
        }, onProgress)
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(partial)
    }
}
