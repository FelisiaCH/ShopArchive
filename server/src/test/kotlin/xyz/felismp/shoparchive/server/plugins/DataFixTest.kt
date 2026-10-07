package xyz.felismp.shoparchive.server.plugins

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.PluginSettings
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataFixTest {
    @TempDir
    lateinit var root: Path

    private val folder get() = root.resolve("data/datafix/Fix-fix1")

    /** Disabled at the end, which closes the plugin jars (on Windows the temp folder cannot be deleted while they are open). */
    private val managers = mutableListOf<PluginManager>()

    @AfterTest
    fun tearDown() {
        managers.forEach { it.disableAll() }
        System.clearProperty("testplugins.datafix")
    }

    private fun write(relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun text(relative: String) = Files.readString(root.resolve(relative))

    /** One server start with the fixture plugin in [mode]; returns the plugin's entry. */
    private fun start(mode: String): PluginEntry {
        System.setProperty("testplugins.datafix", mode)
        buildPluginJar(root.resolve("plugins/Fix.jar"), "datafix", pluginYml("Fix", "testplugins.datafix.DataFixPlugin"))
        val manager = PluginManager(
            root, PluginSettings(requireApproval = false), Services(), Commands(), Permissions(),
            clock = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC),
        )
        managers += manager
        manager.loadAll()
        manager.enableAll()
        return manager.entries.single()
    }

    private fun result() = Files.readString(root.resolve("plugins/Fix/result.txt"))

    @Test
    fun theFixKeepsCopiesOfTheOriginalsRunsOnceAndWritesTheMarker() {
        write("data/a.txt", "alpha")
        write("record/b.txt", "bravo")

        val entry = start("ok")

        assertEquals(PluginState.ENABLED, entry.state)
        assertEquals("ran=true", result())
        assertEquals("alpha-fixed", text("data/a.txt"))
        assertEquals("bravo-fixed", text("record/b.txt"))
        assertEquals("alpha", text("data/datafix/Fix-fix1/data/a.txt"))
        assertEquals("bravo", text("data/datafix/Fix-fix1/record/b.txt"))
        assertFalse(Files.exists(folder.resolve("data/missing.txt")))
        val marker = text("data/datafix/Fix-fix1/done")
        assertContains(marker, "done-at: 2026-03-04T05:06:07Z")
        assertContains(marker, "  - data/a.txt")
        assertContains(marker, "  - data/missing.txt")
    }

    @Test
    fun aSecondStartDoesNotRunTheFixAgain() {
        write("data/a.txt", "alpha")
        write("record/b.txt", "bravo")
        start("ok")

        start("ok")

        assertEquals("ran=false", result())
        assertEquals(listOf("run"), Files.readAllLines(root.resolve("plugins/Fix/runs.txt")))
        assertEquals("alpha-fixed", text("data/a.txt"))
    }

    @Test
    fun aFixThatThrowsLeavesNoMarkerKeepsTheCopyAndFailsThePlugin() {
        write("data/a.txt", "alpha")

        val entry = start("boom")

        assertEquals(PluginState.FAILED, entry.state)
        assertContains(entry.detail, "data fix 'fix1' failed")
        assertContains(entry.detail, "fix exploded")
        assertFalse(Files.exists(folder.resolve("done")))
        assertEquals("alpha", text("data/datafix/Fix-fix1/data/a.txt"))
        assertEquals("alpha-fixed", text("data/a.txt"), "the half-done change is what the copy is for")
    }

    @Test
    fun aRetryAfterAFailedFixKeepsTheFirstCopyBecauseThatIsTheOriginal() {
        write("data/a.txt", "alpha")
        start("boom")

        val entry = start("ok")

        assertEquals(PluginState.ENABLED, entry.state, entry.detail)
        assertEquals("ran=true", result())
        assertEquals("alpha", text("data/datafix/Fix-fix1/data/a.txt"))
        assertEquals("alpha-fixed-fixed", text("data/a.txt"))
        assertTrue(Files.exists(folder.resolve("done")))
    }

    @Test
    fun pathsThatLeaveTheDataFoldersAreRefusedBeforeAnythingIsCopied() {
        write("data/a.txt", "alpha")
        write("config/shoparchive.yml", "x")
        for (bad in listOf("../outside.txt", "data/../config/shoparchive.yml", "/etc/passwd", "config/shoparchive.yml", "data/datafix/Fix-fix1/done", "data", "plugins/Fix.jar")) {
            Files.deleteIfExists(root.resolve("plugins/Fix/result.txt"))
            val entry = start("escape=$bad")

            assertEquals(PluginState.ENABLED, entry.state, bad)
            assertContains(result(), "refused: data fix path", message = bad)
            assertFalse(Files.exists(folder.resolve("done")), bad)
            assertEquals("alpha", text("data/a.txt"))
        }
    }

    @Test
    fun aSymbolicLinkOutOfTheDataFolderIsRefused() {
        val outside = root.resolve("outside.txt").also { Files.writeString(it, "secret") }
        Files.createDirectories(root.resolve("data"))
        try {
            Files.createSymbolicLink(root.resolve("data/link.txt"), outside)
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "no symbolic links here")
        } catch (e: java.io.IOException) {
            assumeTrue(false, "no symbolic links here")
        }

        start("escape=data/link.txt")

        assertContains(result(), "leads outside")
        assertEquals("secret", text("outside.txt"))
        assertFalse(Files.exists(folder))
    }

    @Test
    fun aFixAskedForAfterOnLoadIsRefused() {
        write("data/a.txt", "alpha")

        start("late")

        assertContains(result(), "dataFix can only be called in onLoad")
        assertEquals("alpha", text("data/a.txt"))
        assertFalse(Files.exists(folder))
    }

    private fun link(from: String, to: Path) {
        Files.createDirectories(root.resolve(from).parent)
        try {
            Files.createSymbolicLink(root.resolve(from), to)
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "no symbolic links here")
        } catch (e: java.io.IOException) {
            assumeTrue(false, "no symbolic links here")
        }
    }

    @Test
    fun aDataFolderThatIsItselfALinkOutOfTheRootDoesNotMakeItsTargetAllowed() {
        val outside = Files.createDirectories(root.resolveSibling("${root.fileName}-outside"))
        Files.writeString(outside.resolve("a.txt"), "alpha")
        link("data", outside)

        start("escape=data/a.txt")

        assertContains(result(), "leads outside")
        assertEquals(listOf("a.txt"), Files.list(outside).use { l -> l.map { it.fileName.toString() }.toList() })
        assertEquals("alpha", Files.readString(outside.resolve("a.txt")))
    }

    @Test
    fun aLinkInsideDataThatLeadsIntoTheCopiesFolderIsRefused() {
        write("data/datafix/other/x.txt", "kept")
        link("data/sneaky", root.resolve("data/datafix/other"))

        start("escape=data/sneaky/x.txt")

        assertContains(result(), "leads into data/datafix")
        assertFalse(Files.exists(folder))
    }

    @Test
    fun aDatafixFolderThatIsALinkOutOfTheRootGetsNoCopiesAndNoMarker() {
        write("data/a.txt", "alpha")
        val outside = Files.createDirectories(root.resolveSibling("${root.fileName}-outside2"))
        link("data/datafix", outside)

        val entry = start("ok")

        assertEquals(PluginState.ENABLED, entry.state)
        assertContains(result(), "refused")
        assertEquals(emptyList(), Files.list(outside).use { l -> l.toList() })
        assertEquals("alpha", text("data/a.txt"), "the fix must not have run")
    }
}
