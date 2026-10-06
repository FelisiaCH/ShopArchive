package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.parseYamlMap
import xyz.felismp.shoparchive.server.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** SHA-256 of the file as lower-case hex: what is approved is these exact bytes. */
internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * `plugins/approved.yml`: plugin name -> SHA-256 of the jar the admin approved. A jar whose hash differs (new,
 * rebuilt or replaced by `plugins/update/`) is not loaded while `plugins.require-approval` is on. Only the console
 * writes this file, and a change applies at the next start like every plugin change.
 */
internal class ApprovedPlugins(private val file: Path) {
    /** The approved hashes by plugin name. Throws [ConfigFileException] if the file cannot be understood; it is never overwritten then. */
    fun read(): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        val content = parseYamlMap("plugins/approved.yml", Files.readString(file))
        val approved = content["approved"] ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val map = approved as? Map<String, Any?> ?: throw ConfigFileException("plugins/approved.yml: 'approved' must be a mapping of plugin name to SHA-256")
        return map.mapNotNull { (name, hash) -> (hash as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()
    }

    fun approve(name: String, sha256: String) = write(read() + (name to sha256))

    /** Removes the approval of [name] (ignoring case); false if there was none. */
    fun revoke(name: String): Boolean {
        val current = read()
        val key = current.keys.firstOrNull { it == name } ?: current.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return false
        write(current - key)
        return true
    }

    private fun write(approved: Map<String, String>) {
        val text = buildString {
            append("# Plugin jars the admin approved: plugin name -> SHA-256 of the jar.\n")
            append("# Written by the console commands 'plugins approve' and 'plugins revoke'; a change applies at the next start.\n")
            if (approved.isEmpty()) append("approved: {}\n") else {
                append("approved:\n")
                approved.toSortedMap().forEach { (name, hash) -> append("  \"$name\": \"$hash\"\n") }
            }
        }
        writeAtomically(file, text.toByteArray(Charsets.UTF_8))
    }
}
