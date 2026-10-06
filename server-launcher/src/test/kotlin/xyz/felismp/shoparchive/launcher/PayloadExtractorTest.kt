package xyz.felismp.shoparchive.launcher

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayloadExtractorTest {
    @TempDir
    lateinit var root: Path

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun extractsThenSkipsThenRewritesOnCorruption() {
        val payload = "hello-payload".toByteArray()
        val entry = PayloadEntry(sha256(payload), "versions/0.1.0-dev/server.jar")
        val resourcePath = "/META-INF/shoparchive/${entry.target}"
        val opener: (String) -> InputStream? = { path -> if (path == resourcePath) ByteArrayInputStream(payload) else null }

        // First run: file doesn't exist yet, so it's written.
        assertEquals(listOf(entry.target), extractPayload(root, listOf(entry), opener))
        assertEquals(payload.toList(), Files.readAllBytes(root.resolve(entry.target)).toList())

        // Second run: checksum already matches, so nothing is rewritten.
        assertEquals(emptyList(), extractPayload(root, listOf(entry), opener))

        // Corrupt the target: checksum no longer matches, so it's rewritten from the payload.
        Files.write(root.resolve(entry.target), "corrupted".toByteArray())
        assertEquals(listOf(entry.target), extractPayload(root, listOf(entry), opener))
        assertEquals(payload.toList(), Files.readAllBytes(root.resolve(entry.target)).toList())
    }

    @Test
    fun rejectsPayloadWhoseChecksumDoesNotMatchAndKeepsThePreviousFile() {
        val entry = PayloadEntry(sha256("what-files.list-expects".toByteArray()), "versions/0.1.0-dev/server.jar")
        val opener: (String) -> InputStream? = { ByteArrayInputStream("something-else".toByteArray()) }
        val target = root.resolve(entry.target)
        Files.createDirectories(target.parent)
        Files.write(target, "previous".toByteArray())

        val failure = assertFailsWith<PayloadException> { extractPayload(root, listOf(entry), opener) }

        assertTrue(failure.message!!.contains(entry.sha256) && failure.message!!.contains(entry.target), failure.message)
        assertEquals("previous", String(Files.readAllBytes(target)))
        Files.list(root.resolve("tmp")).use { assertEquals(emptyList(), it.toList()) }
    }

    private fun assertRefusedThroughLinkAndWritesNothing(linkRelative: String, targetRelative: String) {
        val realRoot = Files.createDirectories(root.resolve("srv")).toRealPath()
        val outside = Files.createDirectories(root.resolve("outside"))
        Files.createDirectories(realRoot.resolve(linkRelative).parent)
        dirLinkOrSkip(realRoot.resolve(linkRelative), outside)

        val payload = "payload".toByteArray()
        val entries = listOf(
            PayloadEntry(sha256(payload), "versions/0.1.0-dev/core.jar"), // fine on its own, still not written
            PayloadEntry(sha256(payload), targetRelative),
        )
        val failure = assertFailsWith<PayloadException> { extractPayload(realRoot, entries) { ByteArrayInputStream(payload) } }

        assertTrue(failure.message!!.contains(outside.toRealPath().toString()), failure.message)
        Files.list(outside).use { assertEquals(emptyList(), it.toList()) }
        assertFalse(Files.exists(realRoot.resolve("versions/0.1.0-dev/core.jar")))
        assertFalse(Files.exists(realRoot.resolve("tmp")))
    }

    @Test
    fun refusesVersionDirectoryLinkedOutsideRootAndWritesNothing() =
        assertRefusedThroughLinkAndWritesNothing("versions/9.9.9", "versions/9.9.9/core.jar")

    @Test
    fun refusesLibraryGroupLinkedOutsideRootAndWritesNothing() =
        assertRefusedThroughLinkAndWritesNothing("libraries/org.example", "libraries/org.example/lib.jar")

    @Test
    fun refusesStagingFileThatIsALink() {
        val realRoot = Files.createDirectories(root.resolve("srv")).toRealPath()
        val elsewhere = Files.write(root.resolve("elsewhere"), "untouched".toByteArray())
        Files.createDirectories(realRoot.resolve("tmp"))
        fileSymlinkOrSkip(realRoot.resolve("tmp/extract-core.jar"), elsewhere)

        val payload = "payload".toByteArray()
        val entry = PayloadEntry(sha256(payload), "versions/0.1.0-dev/core.jar")
        assertFailsWith<PayloadException> { extractPayload(realRoot, listOf(entry)) { ByteArrayInputStream(payload) } }

        assertEquals("untouched", String(Files.readAllBytes(elsewhere)))
        assertFalse(Files.exists(realRoot.resolve("versions")))
    }

    @Test
    fun parsesFilesListSkippingBlankAndCommentLines() {
        val text = "# comment\n\n3f2a versions/0.1.0-dev/server.jar\n9b01 libraries/org.example/lib.jar\n"
        assertEquals(
            listOf(
                PayloadEntry("3f2a", "versions/0.1.0-dev/server.jar"),
                PayloadEntry("9b01", "libraries/org.example/lib.jar"),
            ),
            parseFilesList(text.byteInputStream()),
        )
    }
}
