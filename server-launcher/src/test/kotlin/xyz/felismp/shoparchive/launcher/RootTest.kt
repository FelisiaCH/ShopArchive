package xyz.felismp.shoparchive.launcher

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootTest {
    @TempDir
    lateinit var base: Path

    private val root = Paths.get("srv root").toAbsolutePath().normalize()

    @Test
    fun tempDirInsideRootIsAccepted() {
        assertTrue(isInsideRoot(root.resolve("tmp/jvm"), root))
        assertTrue(isInsideRoot(root.resolve("tmp/jvm dir/../jvm"), root))
    }

    @Test
    fun tempDirOutsideRootIsRejected() {
        assertFalse(isInsideRoot(root.resolveSibling("other"), root))
        assertFalse(isInsideRoot(root.resolveSibling("srv root2/tmp"), root)) // shares the name prefix only
        assertFalse(isInsideRoot(root.resolve("tmp/../../elsewhere"), root))
    }

    @Test
    fun relativeTempDirIsMadeAbsoluteFirst() {
        // The start script's -Djava.io.tmpdir=tmp/jvm is relative to the working directory, not to root.
        assertTrue(isInsideRoot(Paths.get("tmp/jvm"), Paths.get("").toAbsolutePath().normalize()))
    }

    @Test
    fun scriptStartRefusesTempDirOutsideRootWithAnExplanation() {
        val problem = assertNotNull(startScriptTempDirProblem(root.resolveSibling("script folder/tmp/jvm"), root))
        assertTrue(problem.contains(root.toString()), problem)
        assertTrue(problem.contains("script folder"), problem)
        assertTrue(problem.contains("--root"), problem)
    }

    @Test
    fun scriptStartAcceptsTempDirInsideRoot() {
        assertNull(startScriptTempDirProblem(root.resolve("tmp/jvm"), root))
    }

    @Test
    fun scriptStartRefusesSiblingWhoseNameStartsWithTheRootName() {
        assertNotNull(startScriptTempDirProblem(root.resolveSibling("srv root2/tmp/jvm"), root))
    }

    @Test
    fun tempDirReachedThroughALinkIntoTheRootIsInside() {
        val realRoot = Files.createDirectories(base.resolve("srv root")).toRealPath()
        Files.createDirectories(realRoot.resolve("tmp/jvm"))
        dirLinkOrSkip(base.resolve("alias"), realRoot) // like a subst drive or a symlinked folder

        assertNull(startScriptTempDirProblem(base.resolve("alias/tmp/jvm"), realRoot))
    }

    @Test
    fun tempDirLinkedFromInsideTheRootToOutsideIsRefused() {
        val realRoot = Files.createDirectories(base.resolve("srv root")).toRealPath()
        val outside = Files.createDirectories(base.resolve("elsewhere"))
        Files.createDirectories(realRoot.resolve("tmp"))
        dirLinkOrSkip(realRoot.resolve("tmp/jvm"), outside)

        assertNotNull(startScriptTempDirProblem(realRoot.resolve("tmp/jvm"), realRoot))
    }

    @Test
    fun notYetExistingTempDirBelowALinkIntoTheRootIsInside() {
        val realRoot = Files.createDirectories(base.resolve("srv root")).toRealPath()
        dirLinkOrSkip(base.resolve("alias"), realRoot) // first run: tmp/jvm is not there yet

        assertNull(startScriptTempDirProblem(base.resolve("alias/tmp/jvm"), realRoot))
    }

    @Test
    fun notYetExistingTempDirBelowALinkToOutsideIsRefusedAndNothingIsCreated() {
        val realRoot = Files.createDirectories(base.resolve("srv root")).toRealPath()
        val outside = Files.createDirectories(base.resolve("elsewhere")).toRealPath()
        dirLinkOrSkip(realRoot.resolve("tmp"), outside)

        assertNotNull(startScriptTempDirProblem(realRoot.resolve("tmp/jvm"), realRoot))
        Files.list(outside).use { assertEquals(emptyList(), it.toList()) }
    }
}
