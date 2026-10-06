package xyz.felismp.shoparchive.launcher

import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One line of `files.list`: the expected SHA-256 of the payload file and where it lands under root. */
data class PayloadEntry(val sha256: String, val target: String)

/** A payload entry that can't be extracted safely; [message] is shown to the admin, [Stage2] then exits 1. */
class PayloadException(message: String) : Exception(message)

/** Parses `files.list`: `<sha256-hex> <root-relative target path>` per line; blanks and `#` comments skipped. */
fun parseFilesList(input: InputStream): List<PayloadEntry> =
    input.bufferedReader(Charsets.UTF_8).readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val spaceIndex = line.indexOf(' ')
            require(spaceIndex > 0) { "malformed files.list line: $line" }
            PayloadEntry(line.substring(0, spaceIndex).trim().lowercase(), line.substring(spaceIndex + 1).trim())
        }

/**
 * Extracts each [entries] item from [openResource] (jar-resource path -> stream, or null if missing)
 * to `<root>/<target>`, skipping files whose SHA-256 already matches. Returns the targets it actually
 * wrote, so a no-op run is distinguishable from a real extraction - used by tests, and by [Stage2] to
 * print one summary line on a first run or an upgrade. Resource path -> stream is injected rather than
 * reading the real jar, so this is testable without building one. [root] must already be a real path.
 * Throws [PayloadException] if an entry escapes root, has a link on its target or staging path, has no
 * resource, or its extracted bytes don't match the expected SHA-256 (the previous target file is then left
 * untouched). The first two are checked for every entry before anything is written.
 */
fun extractPayload(root: Path, entries: List<PayloadEntry>, openResource: (String) -> InputStream?): List<String> {
    for (entry in entries) {
        if (!root.resolve(entry.target).normalize().startsWith(root)) {
            // A files.list entry containing "../" would otherwise write outside the root.
            throw PayloadException("payload entry escapes root, refusing: ${entry.target}")
        }
        // The core is loaded from these targets too, so they must not lead out through a link.
        val problem = linkProblem(root, entry.target) ?: linkProblem(root, "tmp/${stagingName(entry)}")
        if (problem != null) throw PayloadException(problem)
    }

    val written = mutableListOf<String>()
    val tempDir = root.resolve("tmp")
    Files.createDirectories(tempDir)

    for (entry in entries) {
        val target = root.resolve(entry.target).normalize()
        if (Files.exists(target) && matchesChecksum(target, entry.sha256)) {
            continue
        }

        val resourcePath = "/META-INF/shoparchive/${entry.target}"
        val stream = openResource(resourcePath)
        if (stream == null) {
            throw PayloadException("missing payload resource: $resourcePath")
        }

        val stagingFile = tempDir.resolve(stagingName(entry))
        stream.use { input -> Files.copy(input, stagingFile, StandardCopyOption.REPLACE_EXISTING) }

        val actual = sha256Hex(stagingFile)
        if (actual != entry.sha256) {
            Files.deleteIfExists(stagingFile)
            throw PayloadException("payload ${entry.target} is corrupt: expected SHA-256 ${entry.sha256}, got $actual")
        }

        Files.createDirectories(target.parent)
        moveIntoPlace(stagingFile, target)
        written += entry.target
    }
    return written
}

private fun stagingName(entry: PayloadEntry): String = "extract-${entry.target.substringAfterLast('/')}"

private fun moveIntoPlace(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** An existing file whose checksum can't even be read (e.g. a permissions problem) is treated like a
 *  mismatch rather than crashing the launcher - it simply gets re-extracted. */
private fun matchesChecksum(file: Path, expectedSha256: String): Boolean =
    try {
        sha256Hex(file) == expectedSha256
    } catch (e: IOException) {
        false
    }

private fun sha256Hex(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
