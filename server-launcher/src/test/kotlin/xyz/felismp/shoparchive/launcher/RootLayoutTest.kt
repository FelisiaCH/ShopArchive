package xyz.felismp.shoparchive.launcher

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootLayoutTest {
    @TempDir
    lateinit var base: Path

    private fun newRoot(name: String = "root"): Path = Files.createDirectories(base.resolve(name)).toRealPath()

    private fun newOutside(name: String = "outside"): Path {
        val outside = Files.createDirectories(base.resolve(name)).toRealPath()
        Files.write(outside.resolve("keep.txt"), "untouched".toByteArray())
        return outside
    }

    private fun assertUntouched(outside: Path) =
        Files.list(outside).use { stream -> assertEquals(listOf("keep.txt"), stream.map { it.fileName.toString() }.toList()) }

    @Test
    fun reportsDataOrTmpLinkedOutsideRoot() {
        for (name in listOf("data", "tmp")) {
            val root = newRoot("root-$name")
            val outside = newOutside("outside-$name")
            dirLinkOrSkip(root.resolve(name), outside)

            val problem = assertNotNull(layoutLinkProblem(root))
            assertTrue(problem.contains(name), problem)
            assertTrue(problem.contains(outside.toString()), problem)
            assertUntouched(outside)
        }
    }

    @Test
    fun reportsTmpLinkedToAnotherDirectoryInsideRoot() {
        val root = newRoot()
        createRootLayout(root)
        Files.delete(root.resolve("tmp/jvm"))
        Files.delete(root.resolve("tmp"))
        dirLinkOrSkip(root.resolve("tmp"), root.resolve("cache"))

        val problem = assertNotNull(layoutLinkProblem(root))
        assertTrue(problem.contains("tmp"), problem)
        assertTrue(problem.contains(root.resolve("cache").toString()), problem)
    }

    @Test
    fun reportsNestedLinkBelowALayoutDirectory() {
        val root = newRoot()
        val outside = newOutside()
        Files.createDirectories(root.resolve("versions"))
        dirLinkOrSkip(root.resolve("versions/0.1.0-dev"), outside)

        val problem = assertNotNull(linkProblem(root, "versions/0.1.0-dev/server.jar"))
        assertTrue(problem.contains(outside.toString()), problem)
        assertUntouched(outside)
    }

    @Test
    fun reportsSessionLockAndServerIdLinkedToAFileElsewhere() {
        for (name in listOf("session.lock", "server-id")) {
            val root = newRoot("root-$name")
            val elsewhere = Files.write(base.resolve("elsewhere-$name"), "untouched".toByteArray())
            Files.createDirectories(root.resolve("data"))
            fileSymlinkOrSkip(root.resolve("data/$name"), elsewhere)

            val problem = assertNotNull(layoutLinkProblem(root))
            assertTrue(problem.contains(name), problem)
            assertEquals("untouched", String(Files.readAllBytes(elsewhere)))
        }
    }

    @Test
    fun reportsDanglingLink() {
        val root = newRoot()
        val gone = Files.createDirectories(base.resolve("gone"))
        dirLinkOrSkip(root.resolve("data"), gone)
        Files.delete(gone)

        val problem = assertNotNull(layoutLinkProblem(root))
        assertTrue(problem.contains("data"), problem)
    }

    @Test
    fun acceptsAPlainRealTree() {
        val root = newRoot()
        assertNull(layoutLinkProblem(root)) // nothing exists yet

        createRootLayout(root)
        Files.createDirectories(root.resolve("versions/0.1.0-dev"))
        Files.write(root.resolve("data/session.lock"), ByteArray(0))
        Files.write(root.resolve("data/server-id"), "id".toByteArray())
        assertNull(layoutLinkProblem(root))
        assertNull(linkProblem(root, "versions/0.1.0-dev/server.jar"))
        assertNull(linkProblem(root, "libraries/org.example/lib.jar"))
    }
}
