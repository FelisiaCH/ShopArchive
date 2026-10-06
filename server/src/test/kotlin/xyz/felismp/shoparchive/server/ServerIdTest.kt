package xyz.felismp.shoparchive.server

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerIdTest {
    @TempDir
    lateinit var root: Path

    @BeforeTest
    fun setUp() {
        // The launcher creates data/ before the core ever runs; mirror that here.
        Files.createDirectories(root.resolve("data"))
    }

    private fun idFile() = root.resolve("data").resolve("server-id")

    @Test
    fun createsIdOnFirstCall() {
        val id = readOrCreateServerId(root)
        assertEquals(id.toString(), Files.readString(idFile()).trim())
    }

    @Test
    fun secondCallReturnsSameValueAndDoesNotRewriteFile() {
        val first = readOrCreateServerId(root)
        val writtenAt = Files.getLastModifiedTime(idFile())

        val second = readOrCreateServerId(root)

        assertEquals(first, second)
        assertEquals(writtenAt, Files.getLastModifiedTime(idFile()))
    }

    @Test
    fun leftoverTempFileDoesNotBreakCreation() {
        Files.write(root.resolve("data").resolve("server-id.tmp"), "stale-crash-leftover".toByteArray())

        val id = readOrCreateServerId(root)

        assertEquals(id.toString(), Files.readString(idFile()).trim())
    }

    @Test
    fun garbageServerIdIsRejected() {
        Files.writeString(idFile(), "not-a-uuid")

        assertFailsWith<InvalidServerIdException> { readOrCreateServerId(root) }
    }
}
