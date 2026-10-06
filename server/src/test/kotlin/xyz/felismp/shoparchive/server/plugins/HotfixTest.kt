package xyz.felismp.shoparchive.server.plugins

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.PluginSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HotfixTest {
    @TempDir
    lateinit var root: Path

    @AfterTest
    fun closeLog() = xyz.felismp.shoparchive.server.Log.close()

    private fun hotfixYml(name: String, fixes: String = "[SA-1]", severity: String = "security", build: Int = 1) =
        "name: $name\nversion: 1.0\nhotfix: true\nfixes: $fixes\nseverity: $severity\ntarget-build: $build\n"

    private fun writeJar(name: String, yml: String): String {
        val file = root.resolve("plugins/$name.jar")
        Files.createDirectories(file.parent)
        JarOutputStream(Files.newOutputStream(file)).use { jar ->
            jar.putNextEntry(JarEntry("plugin.yml"))
            jar.write(yml.toByteArray())
            jar.closeEntry()
        }
        return sha256(file)
    }

    private fun line(state: HotfixState, name: String, sha: String, fixes: String = "SA-1", detail: String = "") =
        listOf(state.name, name, sha, "security", fixes, detail).joinToString("\t")

    private fun manager(report: String, noPatches: Boolean = false, safeMode: Boolean = false) = PluginManager(
        root, PluginSettings(requireApproval = true), Services(), Commands(), Permissions(), safeMode,
        hotfixes = HotfixReport.parse(report), build = BuildInfo(1, setOf("SA-9")), noPatches = noPatches,
    )

    private fun describe(m: PluginManager) = m.also { it.loadAll() }.describe()

    @Test
    fun theReportIsParsedOneLinePerHotfix() {
        val report = HotfixReport.parse(line(HotfixState.APPLIED, "A", "a".repeat(64), "SA-1,SA-2", "1 patch(es)") + "\n" + line(HotfixState.FIXED_IN_CORE, "B", "b".repeat(64)) + "\nnonsense")
        assertEquals(2, report.entries.size)
        assertEquals(listOf("SA-1", "SA-2"), report.entries[0].fixes)
        assertEquals(HotfixState.FIXED_IN_CORE, report.bySha("b".repeat(64))?.state)
        assertEquals(HotfixReport.NONE.entries, HotfixReport.parse(null).entries)
        assertEquals(HotfixReport.NONE.entries, HotfixReport.parse("").entries)
    }

    @Test
    fun anActiveHotfixIsListedAndNeverLoadedAsAPlugin() {
        val sha = writeJar("Fix1", hotfixYml("Fix1"))
        val m = manager(line(HotfixState.APPLIED, "Fix1", sha, detail = "1 patch(es)"))
        val lines = describe(m)
        assertContains(lines, "Hotfixes (core build 1):")
        assertContains(lines, " Fix1 [security: SA-1] - active")
        assertTrue(lines.none { it.startsWith("Plugins (") }, lines.toString())
        assertEquals(PluginState.HOTFIX, m.entries.single().state)
        assertEquals("Hotfixes: 1 active (core build 1)", m.statusLines().single())
        m.enableAll()
        m.disableAll()
    }

    @Test
    fun aHotfixTheCoreAlreadyFixesIsListedAsRemovable() {
        val sha = writeJar("Fix1", hotfixYml("Fix1"))
        val m = manager(line(HotfixState.FIXED_IN_CORE, "Fix1", sha, detail = "x"))
        assertContains(describe(m), " Fix1 [security: SA-1] - fixed in core, remove the jar")
        assertEquals("Hotfixes: 0 active, 1 fixed in core (remove the jar) (core build 1)", m.statusLines().single())
    }

    @Test
    fun aHotfixThatIsNotApprovedShowsAsNotApprovedAndCanBeApproved() {
        val sha = writeJar("Fix1", hotfixYml("Fix1"))
        val m = manager(line(HotfixState.NOT_APPROVED, "Fix1", sha, detail = "is new (SHA-256 $sha)"))
        assertEquals(PluginState.NOT_APPROVED, m.also { it.loadAll() }.entries.single().state)
        val approve = m.approve("Fix1")
        assertContains(approve.first(), "Approved Fix1")
        assertEquals(mapOf("Fix1" to sha), ApprovedPlugins(root.resolve("plugins/approved.yml")).read())
    }

    @Test
    fun aHotfixJarTheLauncherDidNotSeeIsSaidToNeedARestart() {
        writeJar("Fix1", hotfixYml("Fix1"))
        val m = manager("")
        val lines = describe(m)
        assertContains(lines, " Fix1 - not seen at startup: restart the server to apply it")
    }

    @Test
    fun hotfixesAreShownInSafeModeAndWithNoPatches() {
        val sha = writeJar("Fix1", hotfixYml("Fix1"))
        val off = line(HotfixState.OFF, "Fix1", sha, detail = "--no-patches")
        assertContains(describe(manager(off, safeMode = true)), " Fix1 [security: SA-1] - off (--no-patches)")
        val m = manager(off, noPatches = true)
        assertContains(m.statusLines().single(), "OFF (--no-patches)")
    }

    @Test
    fun pluginsInfoExplainsAHotfix() {
        val sha = writeJar("Fix1", hotfixYml("Fix1"))
        val m = manager(line(HotfixState.APPLIED, "Fix1", sha)).also { it.loadAll() }
        assertContains(m.info("Fix1").joinToString("\n"), "hotfix for SA-1 (security), made for core build 1; this core is build 1")
    }

    @Test
    fun plainPluginsAreCountedWithoutHotfixes() {
        val sha = writeJar("Fix1", hotfixYml("Fix1"))
        buildPluginJar(root.resolve("plugins/hello.jar"), "hello", pluginYml("Hello", "testplugins.hello.HelloPlugin"))
        val m = PluginManager(root, PluginSettings(requireApproval = false), Services(), Commands(), Permissions(), hotfixes = HotfixReport.parse(line(HotfixState.APPLIED, "Fix1", sha)), build = BuildInfo(1, emptySet()))
        m.loadAll()
        assertTrue(m.statusLines().first().startsWith("Plugins: 0 enabled of 1"))
        assertContains(m.describe(), "Plugins (1):")
        m.disableAll()
    }

    @Test
    fun hotfixHashIsTheShaOfTheClassFile() {
        val sent = mutableListOf<String>()
        val sender = object : CommandSender {
            override val name = "t"
            override fun sendMessage(message: String) { sent += message }
        }
        val command = PluginCommand(manager(""))
        command.execute(sender, listOf("hotfix-hash", HotfixReport::class.java.name))
        val expected = sha256(HotfixReport::class.java.getResourceAsStream("/" + HotfixReport::class.java.name.replace('.', '/') + ".class")!!.readBytes())
        assertEquals(listOf("${HotfixReport::class.java.name} SHA-256 $expected"), sent)
        sent.clear()
        command.execute(sender, listOf("hotfix-hash", "no.Such"))
        assertContains(sent.single(), "No class")
    }

    @Test
    fun theCoreJarsBuildInfoIsReadable() {
        val info = BuildInfo.load()
        assertNotNull(info.build)
        assertTrue(info.build!! >= 1)
    }

    @Test
    fun aHotfixHasNoMainClassButNeedsItsFields() {
        val yml = parsePluginYml(hotfixYml("Fix1", fixes = "[SA-1, SA-2]", severity = "bug", build = 3))
        assertTrue(yml.hotfix)
        assertEquals(listOf("SA-1", "SA-2"), yml.fixes)
        assertEquals("bug", yml.severity)
        assertEquals(3, yml.targetBuild)
        for (bad in listOf(
            hotfixYml("Fix1", severity = "meh"), hotfixYml("Fix1", fixes = "[]"), hotfixYml("Fix1", fixes = "[a b]"),
            "name: A\nversion: 1\nhotfix: true\nfixes: [X]\nseverity: bug\n", // no target-build
            "name: A\nversion: 1\nmain: a.B\napi-version: 1\nfixes: [X]\n",   // a plugin with hotfix keys
            "name: A\nversion: 1\nhotfix: maybe\n",
        )) {
            assertFailsWith<PluginYmlException>(bad) { parsePluginYml(bad) }
        }
    }

    // The launcher reads this file with its own small reader (MiniYamlTest.readsWhatTheCoreWrites pins the same text), so the writer's format is fixed here.
    @Test
    fun approvedYmlHasTheFormatTheLauncherReads() {
        val file = root.resolve("plugins/approved.yml")
        Files.createDirectories(file.parent)
        val store = ApprovedPlugins(file)
        store.approve("Beta-2", "b".repeat(64))
        store.approve("Alpha", "a".repeat(64))
        assertEquals(
            "# Plugin jars the admin approved: plugin name -> SHA-256 of the jar.\n" +
                "# Written by the console commands 'plugins approve' and 'plugins revoke'; a change applies at the next start.\n" +
                "approved:\n  \"Alpha\": \"${"a".repeat(64)}\"\n  \"Beta-2\": \"${"b".repeat(64)}\"\n",
            Files.readString(file),
        )
    }
}

class HotfixTemplateTest {
    @Test
    fun theTemplateJarIsAHotfixWithoutKotlinInside() {
        val jar = java.util.jar.JarFile(System.getProperty("shoparchive.hotfixTemplateJar"))
        jar.use {
            val yml = parsePluginYml(it.getInputStream(it.getJarEntry("plugin.yml")).readBytes().toString(Charsets.UTF_8))
            assertTrue(yml.hotfix)
            assertEquals("bug", yml.severity)
            assertTrue(it.entries().asSequence().none { e -> e.name.startsWith("kotlin/") || e.name.startsWith("kotlinx/") })
            assertNotNull(it.getJarEntry("xyz/felismp/shoparchive/plugins/hotfix/PatchesKt.class"))
        }
    }
}
