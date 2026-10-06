package xyz.felismp.shoparchive.launcher

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempCleanupTest {
    @TempDir
    lateinit var base: Path

    private val tmp: Path by lazy { Files.createDirectories(base.resolve("root/tmp")).toRealPath() }

    /** A directory outside tmp holding a file and a subdirectory with a file; nothing may ever delete these. */
    private fun newOutside(): Path {
        val outside = Files.createDirectories(base.resolve("outside/keep")).toRealPath()
        Files.write(outside.resolve("file.txt"), "untouched".toByteArray())
        Files.createDirectories(outside.resolve("sub"))
        Files.write(outside.resolve("sub/inner.txt"), "untouched".toByteArray())
        return outside
    }

    private fun assertOutsideIntact(outside: Path) {
        assertEquals("untouched", String(Files.readAllBytes(outside.resolve("file.txt"))))
        assertEquals("untouched", String(Files.readAllBytes(outside.resolve("sub/inner.txt"))))
    }

    private fun symlinkOrSkip(link: Path, target: Path) {
        val created = try {
            Files.createSymbolicLink(link, target)
            true
        } catch (e: IOException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        }
        assumeTrue(created, "cannot create a symlink here")
    }

    private fun assertWipedKeepingLinkTarget(link: Path, outside: Path) {
        wipeTempDir(tmp)
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link itself must be removed")
        assertTrue(Files.isDirectory(tmp))
        assertOutsideIntact(outside)
    }

    @Test
    fun junctionInTmpIsRemovedWithoutTouchingItsTarget() {
        val outside = newOutside()
        junctionOrSkip(tmp.resolve("j"), outside)
        assertWipedKeepingLinkTarget(tmp.resolve("j"), outside)
    }

    @Test
    fun symlinkInTmpIsRemovedWithoutTouchingItsTarget() {
        val outside = newOutside()
        symlinkOrSkip(tmp.resolve("j"), outside)
        assertWipedKeepingLinkTarget(tmp.resolve("j"), outside)
    }

    private fun assertNestedLinkSurvivesWipe(makeLink: (Path, Path) -> Unit) {
        val outside = newOutside()
        Files.createDirectories(tmp.resolve("a"))
        Files.write(tmp.resolve("a/leftover.txt"), "x".toByteArray())
        makeLink(tmp.resolve("a/j"), outside)
        assertWipedKeepingLinkTarget(tmp.resolve("a"), outside)
    }

    @Test
    fun junctionNestedBelowTmpIsRemovedWithoutTouchingItsTarget() = assertNestedLinkSurvivesWipe(::junctionOrSkip)

    @Test
    fun symlinkNestedBelowTmpIsRemovedWithoutTouchingItsTarget() = assertNestedLinkSurvivesWipe(::symlinkOrSkip)

    @Test
    fun wipesOrdinaryNestedFilesAndKeepsTheStartDirectory() {
        Files.createDirectories(tmp.resolve("a/b/c"))
        Files.write(tmp.resolve("top.txt"), "x".toByteArray())
        Files.write(tmp.resolve("a/b/c/deep.txt"), "x".toByteArray())

        wipeTempDir(tmp)

        assertTrue(Files.isDirectory(tmp))
        Files.list(tmp).use { assertEquals(emptyList(), it.toList()) }
    }
}
