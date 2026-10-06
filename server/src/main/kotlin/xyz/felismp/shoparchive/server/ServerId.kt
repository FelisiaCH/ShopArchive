package xyz.felismp.shoparchive.server

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** `data/server-id` exists but its content isn't a valid UUID - identity is never silently overwritten. */
class InvalidServerIdException(message: String) : Exception(message)

/**
 * Reads `data/server-id`, creating it on first boot. The id is the server's permanent identity, so a
 * missing file is fine (first run) but a present-and-broken one is a hard failure - this project has no
 * hard deletes, so we never guess and overwrite; the admin restores or fixes the file by hand.
 */
fun readOrCreateServerId(root: Path): UUID {
    val path = root.resolve("data").resolve("server-id")
    if (Files.exists(path)) {
        val text = Files.readString(path, StandardCharsets.UTF_8).trim()
        val id = try {
            UUID.fromString(text)
        } catch (e: IllegalArgumentException) {
            throw InvalidServerIdException(
                "data/server-id exists but is not a valid UUID (found: '$text'). " +
                    "Restore it from backup or fix it by hand - it is never overwritten automatically."
            )
        }
        // On every boot, not just the first: if the directory fsync failed after the rename, the file exists
        // next boot but the rename may still not be durable.
        fsyncDirectory(path.parent)
        return id
    }

    val id = UUID.randomUUID()
    writeAtomically(path, id.toString().toByteArray(StandardCharsets.UTF_8))
    return id
}
