package xyz.felismp.shoparchive.server

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
}
