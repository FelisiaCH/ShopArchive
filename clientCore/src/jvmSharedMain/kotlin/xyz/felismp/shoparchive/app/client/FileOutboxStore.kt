package xyz.felismp.shoparchive.app.client

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * One file per server and user in [dir]. The name is a hash of the server id, then a hash of server id and user, so the user's name is never
 * on disk in the name and a server's files are found by their prefix. A write reaches the disk (fsync) before it replaces the old file.
 */
class FileOutboxStore(private val dir: File) : OutboxStore {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    private fun prefix(serverId: String) = hash(serverId).take(16) + "-"
    private fun fileOf(serverId: String, user: String) = File(dir, prefix(serverId) + hash(serverId + "\n" + user).take(32) + ".json")

    override fun read(serverId: String, user: String): String? {
        val file = fileOf(serverId, user)
        return if (file.isFile) file.readText() else null
    }

    override fun write(serverId: String, user: String, text: String?) {
        val file = fileOf(serverId, user)
        if (text == null) {
            Files.deleteIfExists(file.toPath())
            return
        }
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.encodeToByteArray())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun removeServer(serverId: String) {
        val prefix = prefix(serverId)
        dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { Files.deleteIfExists(it.toPath()) }
    }
}
