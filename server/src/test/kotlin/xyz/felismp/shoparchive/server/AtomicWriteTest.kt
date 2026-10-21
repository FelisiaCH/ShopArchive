package xyz.felismp.shoparchive.server

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.records.FileOps
import xyz.felismp.shoparchive.server.records.RetryPolicy
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AtomicWriteTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun writesNewFileAndLeavesNoTempFile() {
        val file = dir.resolve("a.txt")

        writeAtomically(file, "สวัสดี".toByteArray())

        assertContentEquals("สวัสดี".toByteArray(), Files.readAllBytes(file))
        assertEquals(listOf("a.txt"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun replacesExistingFileCompletely() {
        val file = dir.resolve("a.txt")
        Files.writeString(file, "a much longer old content")

        writeAtomically(file, "new".toByteArray())

        assertEquals("new", Files.readString(file))
    }

    @Test
    fun staleTempFileFromACrashDoesNotBlockTheWrite() {
        Files.writeString(dir.resolve("a.txt.tmp"), "half written")

        writeAtomically(dir.resolve("a.txt"), "ok".toByteArray())

        assertEquals("ok", Files.readString(dir.resolve("a.txt")))
        assertFalse(Files.exists(dir.resolve("a.txt.tmp")))
    }

    @Test
    fun aRenameTheSystemRefusesForAMomentIsTriedAgain() {
        val file = dir.resolve("a.txt")
        var calls = 0
        val files = FileOps(RetryPolicy(enabled = true, sleep = {}), move = { from, to ->
            if (++calls <= 3) throw AccessDeniedException(to.toString())
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        })

        writeAtomically(file, "ok".toByteArray(), files)

        assertEquals(4, calls)
        assertEquals("ok", Files.readString(file))
        assertFalse(Files.exists(dir.resolve("a.txt.tmp")))
    }
}
