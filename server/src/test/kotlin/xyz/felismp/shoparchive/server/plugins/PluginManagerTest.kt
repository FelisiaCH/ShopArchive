package xyz.felismp.shoparchive.server.plugins

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.RegistryConflictException
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.PluginSettings
import xyz.felismp.shoparchive.shared.InfoResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManagerTest {
    @TempDir
    lateinit var root: Path

    private val plugins get() = root.resolve("plugins")
    private val hello = "testplugins.hello.HelloPlugin"

    /** Every start of a test: its plugins are disabled at the end, which closes their jars (on Windows the temp folder cannot be deleted while they are open). */
    private val starts = mutableListOf<Start>()

    /** What one server start sees: fresh registries (the core's own service in them), and a manager over [root]. */
    private inner class Start(
        settings: PluginSettings = PluginSettings(requireApproval = false, disableTimeoutMs = 2_000),
        safeMode: Boolean = false,
        hostKotlin: KotlinVersion = KotlinVersion.CURRENT,
        apiVersion: Int = 1,
    ) {
        val services = Services().also {
            it.register(InfoService::class.java, object : InfoService {
                override fun info() = InfoResponse("id", "from-core", "", "1", 1)
            }, 0, "core")
        }
        val commands = Commands()
        val permissions = Permissions()
        val manager = PluginManager(
            root, settings, services, commands, permissions, safeMode,
            hostKotlin = hostKotlin, apiVersion = apiVersion,
            clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC),
        )

        init {
            starts += this
        }

        fun run(): Start {
            manager.loadAll()
            manager.enableAll()
            return this
        }

        fun infoName() = services.get(InfoService::class.java)!!.info().name
        fun entry(name: String) = manager.entries.first { it.displayName == name }
        fun state(name: String) = entry(name).state
    }

    private fun jar(fileName: String, pkg: String, yml: String?, extra: Map<String, ByteArray> = emptyMap()) =
        buildPluginJar(plugins.resolve(fileName), pkg, yml, extra)

    private fun helloJar(fileName: String = "hello.jar", version: String = "1.0") =
        jar(fileName, "hello", pluginYml("Hello", hello, version))

    private fun plain(name: String, depend: List<String> = emptyList(), soft: List<String> = emptyList(), apiVersion: String = "1") =
        jar("$name.jar", "plain", pluginYml(name, "testplugins.plain.PlainPlugin", apiVersion = apiVersion, depend = depend, softdepend = soft))

    @AfterTest
    fun tearDown() {
        starts.forEach { it.manager.disableAll() }
        System.clearProperty("testplugins.orderFile")
        System.clearProperty("testplugins.leak")
        xyz.felismp.shoparchive.server.Log.close()
    }

    @Test
    fun noPluginsLeavesTheCoreAsItIs() {
        val start = Start().run()
        assertEquals(emptyList(), start.manager.entries)
        assertEquals("from-core", start.infoName())
        assertEquals(listOf("No plugins (put jars in plugins/ and restart)"), start.manager.describe())
        start.manager.disableAll()
    }

    @Test
    fun aPluginOverridesAServiceAndRemovingItsJarGivesTheCoreBack() {
        val jar = helloJar()
        val first = Start().run()
        assertEquals("from-plugin", first.infoName())
        assertEquals(PluginState.ENABLED, first.state("Hello"))
        first.manager.disableAll()

        Files.delete(jar)
        assertEquals("from-core", Start().run().infoName())
    }

    @Test
    fun theOwnerOfWhatAPluginRegistersIsThePluginNotWhatItPasses() {
        helloJar()
        val second = Start().run()
        assertNotNull(second.commands.find("hello"))
        assertEquals(listOf("hello.use"), second.permissions.all().map { it.node })
        val lines = second.manager.describe()
        assertContains(lines, "   overrides: InfoService (priority 10)")
        assertContains(lines, " Hello 1.0 - enabled - hello.jar")
        // A service of the same type and priority registered by "Hello" again names the plugin, not the owner it passed.
        val clash = assertFailsWith<RegistryConflictException> {
            second.services.register(InfoService::class.java, second.services.get(InfoService::class.java)!!, 10, "other")
        }
        assertContains(clash.message!!, "'plugin:Hello'")
        assertContains(clash.message!!, "'other'")
    }

    @Test
    fun lifecycleRunsLoadEnableDisableAndDisableComesLast() {
        helloJar()
        val start = Start().run()
        start.manager.disableAll()
        val events = Files.readAllLines(plugins.resolve("Hello/events.txt"))
        assertEquals(listOf("load", "enable", "disable"), events)
        assertEquals(PluginState.DISABLED, start.state("Hello"))
        assertNull(start.commands.find("hello"))
        assertEquals("from-core", start.infoName())
    }

    @Test
    fun twoPluginsWithTheSameServicePriorityStopTheStartAndNameBoth() {
        helloJar()
        jar("hello2.jar", "hello2", pluginYml("Hello2", "testplugins.hello2.Hello2Plugin"))
        val e = assertFailsWith<RegistryConflictException> { Start().run() }
        assertContains(e.message!!, "'plugin:Hello'")
        assertContains(e.message!!, "'plugin:Hello2'")
        assertContains(e.message!!, "priority 10")
    }

    @Test
    fun aPluginThatThrowsInEnableIsDisabledAndTheRestCarryOn() {
        jar("boom.jar", "boom", pluginYml("Boom", "testplugins.boom.BoomPlugin"))
        helloJar()
        val start = Start().run()
        assertEquals(PluginState.FAILED, start.state("Boom"))
        assertContains(start.entry("Boom").detail, "enable failed on purpose")
        assertEquals(PluginState.ENABLED, start.state("Hello"))
        // Boom's service (priority 30) and command went with it.
        assertEquals("from-plugin", start.infoName())
        assertNull(start.commands.find("boom"))
        assertContains(start.manager.describe().joinToString("\n"), "Boom 1.0 - disabled-error")
    }

    @Test
    fun aPluginThatThrowsInLoadTakesBackItsNodesAndServices() {
        jar("lb.jar", "loadboom", pluginYml("LoadBoom", "testplugins.loadboom.LoadBoomPlugin"))
        val start = Start().run()
        assertEquals(PluginState.FAILED, start.state("LoadBoom"))
        assertEquals("from-core", start.infoName())
        assertEquals(emptyList(), start.permissions.all())
    }

    @Test
    fun aJarWithShadedKotlinIsRejected() {
        jar("shaded.jar", "plain", pluginYml("Shaded", "testplugins.plain.PlainPlugin"), mapOf("kotlin/Fake.class" to byteArrayOf(1)))
        jar("shaded2.jar", "plain", pluginYml("Shaded2", "testplugins.plain.PlainPlugin"), mapOf("kotlinx/coroutines/Fake.class" to byteArrayOf(1)))
        val start = Start().run()
        for (name in listOf("Shaded", "Shaded2")) {
            assertEquals(PluginState.REJECTED, start.state(name))
            assertContains(start.entry(name).detail, "do not shade kotlin")
        }
    }

    @Test
    fun aPluginBuiltWithNewerKotlinIsRejectedAndNeverRun() {
        System.setProperty("testplugins.orderFile", root.resolve("order.txt").toString())
        plain("Plain")
        // The host pretends to be older than the Kotlin these tests are compiled with.
        val start = Start(hostKotlin = KotlinVersion(1, 0, 0)).run()
        assertEquals(PluginState.REJECTED, start.state("Plain"))
        assertContains(start.entry("Plain").detail, "newer than this server's Kotlin 1.0")
        assertFalse(Files.exists(root.resolve("order.txt")))
    }

    @Test
    fun aPluginFromTheSameOrAnOlderKotlinLoads() {
        plain("Plain")
        assertEquals(PluginState.ENABLED, Start().run().state("Plain"))
    }

    @Test
    fun aDifferentApiVersionIsNotLoaded() {
        plain("Old", apiVersion = "2")
        val start = Start().run()
        assertEquals(PluginState.REJECTED, start.state("Old"))
        assertEquals("built for plugin api-version 2, this server has 1", start.entry("Old").detail)
    }

    @Test
    fun aJarThatIsNotApprovedIsNotLoadedUntilApprovedAndRestarted() {
        helloJar()
        val settings = PluginSettings(requireApproval = true, disableTimeoutMs = 2_000)
        val first = Start(settings).run()
        assertEquals(PluginState.NOT_APPROVED, first.state("Hello"))
        assertContains(first.entry("Hello").detail, first.entry("Hello").sha256)
        assertEquals("from-core", first.infoName())
        assertFalse(Files.exists(plugins.resolve("Hello")) && Files.exists(plugins.resolve("Hello/events.txt")))

        val approved = first.manager.approve("Hello")
        assertContains(approved.joinToString("\n"), "loaded at the next start")
        assertEquals("from-core", first.infoName(), "approving does not load it now")
        assertContains(Files.readString(plugins.resolve("approved.yml")), first.entry("Hello").sha256)

        assertEquals("from-plugin", Start(settings).run().infoName())
    }

    @Test
    fun aChangedJarNeedsApprovalAgainAndRevokeRemovesIt() {
        val settings = PluginSettings(requireApproval = true, disableTimeoutMs = 2_000)
        helloJar()
        Start(settings).manager.approve("hello.jar")
        assertEquals(PluginState.ENABLED, Start(settings).run().state("Hello"))

        helloJar(version = "2.0")
        val changed = Start(settings).run()
        assertEquals(PluginState.NOT_APPROVED, changed.state("Hello"))
        assertContains(changed.entry("Hello").detail, "changed since it was approved")

        changed.manager.approve("Hello")
        assertEquals(PluginState.ENABLED, Start(settings).run().state("Hello"))
        assertContains(Start(settings).manager.revoke("hello").single(), "removed")
        assertEquals(PluginState.NOT_APPROVED, Start(settings).run().state("Hello"))
        assertEquals("'Hello' is not approved", Start(settings).manager.revoke("Hello").single())
    }

    @Test
    fun approveRefusesAJarThatCannotLoadAndUnknownNames() {
        jar("nameless.jar", "plain", pluginYml(null, "testplugins.plain.PlainPlugin"))
        val manager = Start().manager
        assertContains(manager.approve("nameless").single(), "Cannot approve nameless.jar: plugin.yml: name is required")
        assertContains(manager.approve("nothing").single(), "No jar in plugins/ matches 'nothing'")
        assertFalse(Files.exists(plugins.resolve("approved.yml")))
    }

    @Test
    fun anUpdateJarReplacesTheOneWithTheSameNameAndKeepsTheOldOne() {
        val settings = PluginSettings(requireApproval = true, disableTimeoutMs = 2_000)
        helloJar("hello.jar", "1.0")
        Start(settings).manager.approve("Hello")
        val oldHash = Start(settings).entry0("hello.jar")

        jar("update/hello-2.jar", "hello", pluginYml("Hello", hello, "2.0"))
        val start = Start(settings).run()

        assertFalse(Files.exists(plugins.resolve("hello.jar")))
        assertTrue(Files.exists(plugins.resolve("hello-2.jar")))
        assertFalse(Files.exists(plugins.resolve("update/hello-2.jar")))
        val kept = plugins.resolve("update/old/20260102-030405/hello.jar")
        assertTrue(Files.exists(kept), "the old jar is moved, not deleted")
        assertEquals(oldHash, sha256(kept))
        // The replacement is a new jar: approval again.
        assertEquals(PluginState.NOT_APPROVED, start.state("Hello"))
        assertEquals("2.0", start.entry("Hello").yml!!.version)
    }

    private fun Start.entry0(file: String): String = sha256(plugins.resolve(file))

    @Test
    fun anUpdateJarOfAnUnknownPluginIsAdded() {
        jar("update/hello.jar", "hello", pluginYml("Hello", hello))
        val start = Start().run()
        assertTrue(Files.exists(plugins.resolve("hello.jar")))
        assertEquals(PluginState.ENABLED, start.state("Hello"))
        assertFalse(Files.exists(plugins.resolve("update/old")))
    }

    @Test
    fun anUpdateJarWithoutAReadablePluginYmlIsLeftAlone() {
        Files.createDirectories(plugins.resolve("update"))
        Files.writeString(plugins.resolve("update/junk.jar"), "not a zip")
        Start().run()
        assertTrue(Files.exists(plugins.resolve("update/junk.jar")))
    }

    @Test
    fun aMissingDependencyAndACycleAreErrorsNamingThePluginsAndTheOthersStillLoad() {
        plain("NeedsGhost", depend = listOf("Ghost"))
        plain("CycleA", depend = listOf("CycleB"))
        plain("CycleB", depend = listOf("CycleA"))
        plain("OnCycle", depend = listOf("CycleA"))
        plain("Fine")
        val start = Start().run()

        assertEquals(PluginState.REJECTED, start.state("NeedsGhost"))
        assertEquals("needs 'Ghost', which is not installed", start.entry("NeedsGhost").detail)
        assertEquals("depend cycle: CycleA -> CycleB -> CycleA", start.entry("CycleA").detail)
        assertEquals("depend cycle: CycleB -> CycleA -> CycleB", start.entry("CycleB").detail)
        assertContains(start.entry("OnCycle").detail, "needs 'CycleA', which is not loaded (rejected:depend cycle")
        assertEquals(PluginState.ENABLED, start.state("Fine"))
    }

    @Test
    fun aPluginNeedingOneThatIsNotApprovedIsNotLoaded() {
        val settings = PluginSettings(requireApproval = true, disableTimeoutMs = 2_000)
        plain("Base")
        plain("Top", depend = listOf("Base"))
        Start(settings).manager.approve("Top")
        val start = Start(settings).run()
        assertEquals(PluginState.NOT_APPROVED, start.state("Base"))
        assertEquals(PluginState.REJECTED, start.state("Top"))
        assertContains(start.entry("Top").detail, "needs 'Base', which is not loaded (not-approved)")
    }

    @Test
    fun pluginsLoadDependenciesFirstThenByNameAndDisableInReverse() {
        val order = root.resolve("order.txt")
        System.setProperty("testplugins.orderFile", order.toString())
        plain("Zed")
        plain("Alpha", depend = listOf("Zed"))
        plain("Mid", soft = listOf("Alpha"))
        plain("Beta")
        val start = Start().run()
        start.manager.disableAll()

        val lines = Files.readAllLines(order)
        val loads = lines.filter { it.endsWith(":load") }.map { it.substringBefore(':') }
        // Beta has nothing to wait for and sorts first; Zed before Alpha (depend); Mid after Alpha (softdepend).
        assertEquals(listOf("Beta", "Zed", "Alpha", "Mid"), loads)
        assertEquals(loads, lines.filter { it.endsWith(":enable") }.map { it.substringBefore(':') })
        assertEquals(loads.reversed(), lines.filter { it.endsWith(":disable") }.map { it.substringBefore(':') })
    }

    @Test
    fun aSoftdependOnAnAbsentPluginIsIgnored() {
        plain("Solo", soft = listOf("NotThere"))
        assertEquals(PluginState.ENABLED, Start().run().state("Solo"))
    }

    @Test
    fun aSoftdependCycleDoesNotStopEitherPlugin() {
        plain("A", soft = listOf("B"))
        plain("B", soft = listOf("A"))
        val start = Start().run()
        assertEquals(PluginState.ENABLED, start.state("A"))
        assertEquals(PluginState.ENABLED, start.state("B"))
    }

    @Test
    fun aPluginCannotLoadTheCoresOwnOrItsLibrariesClasses() {
        jar("spy.jar", "spy", pluginYml("Spy", "testplugins.spy.SpyPlugin"))
        Start().run()
        val result = Files.readAllLines(plugins.resolve("Spy/result.txt")).associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("hidden", result["xyz.felismp.shoparchive.server.Log"])
        assertEquals("hidden", result["xyz.felismp.shoparchive.server.plugins.PluginManager"])
        assertEquals("hidden", result["io.ktor.server.application.Application"])
        assertEquals("hidden", result["com.charleskorn.kaml.Yaml"])
        assertEquals("hidden", result["org.apache.logging.log4j.LogManager"])
        assertEquals("visible", result["xyz.felismp.shoparchive.api.ServiceRegistry"])
        assertEquals("visible", result["xyz.felismp.shoparchive.shared.InfoResponse"])
        assertEquals("visible", result["kotlin.collections.CollectionsKt"])
        assertEquals("visible", result["java.util.ArrayList"])
    }

    @Test
    fun aPluginSeesTheClassesOfThePluginItDependsOnButNotOthers() {
        jar("provider.jar", "provider", pluginYml("Provider", "testplugins.provider.ProviderPlugin"))
        jar("consumer.jar", "consumer", pluginYml("Consumer", "testplugins.consumer.ConsumerPlugin", depend = listOf("Provider")))
        val start = Start().run()
        assertEquals(PluginState.ENABLED, start.state("Consumer"))
        assertEquals("shared-hello", Files.readString(plugins.resolve("Consumer/result.txt")))
    }

    @Test
    fun withoutTheDependencyDeclaredTheClassIsNotThere() {
        jar("provider.jar", "provider", pluginYml("Provider", "testplugins.provider.ProviderPlugin"))
        jar("consumer.jar", "consumer", pluginYml("Consumer", "testplugins.consumer.ConsumerPlugin"))
        val start = Start().run()
        assertEquals(PluginState.FAILED, start.state("Consumer"))
        assertContains(start.entry("Consumer").detail, "NoClassDefFoundError")
    }

    @Test
    fun aSlowOnDisableDoesNotHoldUpTheShutdown() {
        jar("slow.jar", "slow", pluginYml("Slow", "testplugins.slow.SlowPlugin"))
        helloJar()
        val start = Start(PluginSettings(requireApproval = false, disableTimeoutMs = 200)).run()
        val began = System.nanoTime()
        start.manager.disableAll()
        assertTrue((System.nanoTime() - began) / 1_000_000 < 3_000)
        assertEquals(PluginState.DISABLED, start.state("Slow"))
        // The slow one did not stop the others from being disabled.
        assertContains(Files.readAllLines(plugins.resolve("Hello/events.txt")), "disable")
        // Once its onDisable does return, its jar is closed too.
        Thread.getAllStackTraces().keys.filter { it.name == "plugin-disable-Slow" }.forEach { it.join() }
        assertNull(start.entry("Slow").loader!!.ownResource("plugin.yml"))
    }

    @Test
    fun safeModeLoadsNothingAndLeavesUpdatesAlone() {
        helloJar()
        jar("update/other.jar", "plain", pluginYml("Other", "testplugins.plain.PlainPlugin"))
        val start = Start(safeMode = true).run()
        assertEquals("from-core", start.infoName())
        assertEquals(emptyList(), start.manager.entries)
        assertTrue(Files.exists(plugins.resolve("update/other.jar")))
        assertEquals(listOf("Plugins are off for this run (--no-plugins)"), start.manager.describe())
    }

    @Test
    fun aBadPluginYmlRejectsTheJarWithTheFieldsNamed() {
        jar("bad.jar", "plain", "name: \"bad name!\"\nmain: 12ab\napi-version: x\ndepend: Foo\n")
        jar("none.jar", "plain", null)
        jar("notajar.jar", "plain", pluginYml("X", "a.B")).also { Files.writeString(it, "garbage") }
        val start = Start().run()
        val bad = start.entry("bad.jar").detail
        assertContains(bad, "name 'bad name!' must be 1 to 32 characters")
        assertContains(bad, "version is required")
        assertContains(bad, "main '12ab' is not a class name")
        assertContains(bad, "api-version 'x' must be a whole number from 1")
        assertContains(bad, "depend must be a list of plugin names")
        assertEquals("the jar has no plugin.yml", start.entry("none.jar").detail)
        assertContains(start.entry("notajar.jar").detail, "not a readable jar")
        assertContains(start.manager.describe().joinToString("\n"), "bad.jar - rejected:plugin.yml:")
    }

    @Test
    fun aMissingMainClassOrOneThatIsNotAShopPluginIsDisabledWithAClearMessage() {
        jar("nomain.jar", "plain", pluginYml("NoMain", "testplugins.plain.Nope"))
        jar("notplugin.jar", "provider", pluginYml("NotPlugin", "testplugins.provider.Shared"))
        val start = Start().run()
        assertEquals("main class testplugins.plain.Nope is not in the jar", start.entry("NoMain").detail)
        assertEquals(PluginState.FAILED, start.state("NoMain"))
        assertEquals("main class testplugins.provider.Shared does not extend ShopPlugin", start.entry("NotPlugin").detail)
    }

    @Test
    fun twoJarsForOnePluginNameRejectTheSecond() {
        helloJar("a-hello.jar")
        helloJar("b-hello.jar")
        val start = Start().run()
        assertEquals(PluginState.ENABLED, start.manager.entries.first { it.file.fileName.toString() == "a-hello.jar" }.state)
        val second = start.manager.entries.first { it.file.fileName.toString() == "b-hello.jar" }
        assertEquals(PluginState.REJECTED, second.state)
        assertContains(second.detail, "a second jar for plugin 'Hello'")
    }

    @Test
    fun defaultConfigIsCopiedFromTheJarOnceAndReadByType() {
        val yml = "greeting: hi\nflag: true\nnames: [a, b]\nlimits:\n  max: 7\n  min: 1\n"
        jar("cfg.jar", "cfg", pluginYml("Cfg", "testplugins.cfg.ConfigPlugin"), mapOf("config.yml" to yml.toByteArray()))
        Start().run()
        assertEquals(yml, Files.readString(plugins.resolve("Cfg/config.yml")))
        assertEquals("hi|7|true|[a, b]|-5|dflt|[limits.max, limits.min]", Files.readString(plugins.resolve("Cfg/result.txt")))

        // An admin's edit is kept on the next start.
        Files.writeString(plugins.resolve("Cfg/config.yml"), "greeting: edited\n")
        Start().run()
        assertEquals("edited|-1|false|[]|-5|dflt|[]", Files.readString(plugins.resolve("Cfg/result.txt")))
    }

    @Test
    fun aPluginServiceAtTheCoresPriorityIsRefusedInThePlugin() {
        // Not a registry conflict (that stops the server): the plugin asked for something it may not have.
        val start = Start()
        jar("hello.jar", "hello", pluginYml("Hello", hello))
        start.manager.loadAll()
        val context = start.manager.entries.single().context!!
        val e = assertFailsWith<IllegalArgumentException> { context.services.register(InfoService::class.java, start.services.get(InfoService::class.java)!!, 0, "core") }
        assertContains(e.message!!, "above the core's")
    }

    private fun reloaderJar(name: String = "Reloader") =
        jar("$name.jar", "reloader", pluginYml(name, "testplugins.reloader.ReloaderPlugin"), mapOf("config.yml" to "value: one\n".toByteArray()))

    private fun reloads(name: String = "Reloader") = Files.readAllLines(plugins.resolve("$name/reloads.txt"))

    @Test
    fun reloadingAPluginRereadsItsConfigAndTellsIt() {
        reloaderJar()
        val start = Start().run()
        Files.writeString(plugins.resolve("Reloader/config.yml"), "value: two\n")

        assertEquals(listOf("Reloader"), start.manager.names())
        assertTrue(start.manager.reload("Reloader"))

        assertEquals(listOf("two"), reloads())
        assertTrue(start.manager.reload("reloader"), "the name matches ignoring case")
        assertEquals(listOf("two", "two"), reloads())
    }

    @Test
    fun reloadAllReloadsEveryEnabledPluginAndOnlyThose() {
        reloaderJar("A")
        reloaderJar("B")
        jar("hello.jar", "hello", pluginYml("Hello", hello))
        val start = Start().run()

        assertEquals(listOf("A", "B", "Hello"), start.manager.reloadAll())
        assertEquals(listOf("one"), reloads("A"))
        assertEquals(listOf("one"), reloads("B"))
    }

    @Test
    fun aPluginThatIsNotEnabledCannotBeReloaded() {
        reloaderJar()
        val start = Start(PluginSettings(requireApproval = true, disableTimeoutMs = 2_000)).run()

        assertEquals(PluginState.NOT_APPROVED, start.state("Reloader"))
        assertEquals(emptyList(), start.manager.names())
        assertFalse(start.manager.reload("Reloader"))
        assertFalse(start.manager.reload("Nope"))
    }

    @Test
    fun aPluginThatThrowsInOnConfigReloadStaysEnabledAndLaterReloadsStillWork() {
        reloaderJar()
        val start = Start().run()
        Files.writeString(plugins.resolve("Reloader/config.yml"), "value: two\nboom: true\n")

        assertTrue(start.manager.reload("Reloader"))
        assertEquals(PluginState.ENABLED, start.state("Reloader"))

        Files.writeString(plugins.resolve("Reloader/config.yml"), "value: three\n")
        assertTrue(start.manager.reload("Reloader"))
        assertEquals(listOf("three"), reloads())
    }

    @Test
    fun aConfigThatCannotBeReadKeepsTheOldValuesAndDoesNotCallOnConfigReload() {
        reloaderJar()
        val start = Start().run()
        Files.writeString(plugins.resolve("Reloader/config.yml"), "value: [unclosed\n")

        assertTrue(start.manager.reload("Reloader"))

        assertFalse(Files.exists(plugins.resolve("Reloader/reloads.txt")))
        assertEquals("one", start.entry("Reloader").context!!.config.getString("value", "none"))
    }

    @Test
    fun pluginsInfoShowsTheJarTheRegistrationsAndTheConfigWithSecretsMasked() {
        val yml = "greeting: hi\nmail:\n  host: smtp.example.com\n  password: hunter2hunter2\nbot-token: abc\nnames: [a, b]\nempty-token:\n"
        jar("cfg.jar", "cfg", pluginYml("Cfg", "testplugins.cfg.ConfigPlugin"), mapOf("config.yml" to yml.toByteArray()))
        val start = Start().run()

        val text = start.manager.info("cfg").joinToString("\n")

        assertContains(text, "Cfg 1.0 - enabled")
        assertContains(text, "main: testplugins.cfg.ConfigPlugin, api-version 1")
        assertContains(text, "    mail.host: smtp.example.com")
        assertContains(text, "    mail.password: ***")
        assertContains(text, "    bot-token: ***")
        assertContains(text, "    greeting: hi")
        assertContains(text, "    names: [a, b]")
        assertContains(text, "    empty-token: (empty)")
        assertFalse("hunter2hunter2" in text)
        assertEquals(listOf("No plugin 'Nope' (type plugins to list them)"), start.manager.info("Nope"))
    }

    @Test
    fun pluginsInfoListsTheServicesAndPermissionNodesAPluginRegistered() {
        helloJar()
        val text = Start().run().manager.info("Hello").joinToString("\n")

        assertContains(text, "services: InfoService (priority 10, overrides the core)")
        assertContains(text, "permission nodes: hello.use")
    }

    @Test
    fun theLoadedPluginIsTheSnapshotThatWasHashedNotWhateverIsInPluginsLater() {
        val jar = helloJar()
        val start = Start()
        start.manager.loadAll()
        val entry = start.manager.entries.single()
        assertEquals(root.resolve("cache/plugins/${entry.sha256}.jar"), entry.snapshot)
        assertEquals(entry.sha256, sha256(entry.snapshot!!))

        // The file in plugins/ is overwritten in place after the hash was taken: what runs must still be the hashed bytes.
        Files.write(jar, "not a jar".toByteArray())
        start.manager.enableAll()

        assertEquals(PluginState.ENABLED, entry.state, entry.detail)
        assertNotNull(start.commands.find("hello"))
    }

    @Test
    fun snapshotsOfEarlierRunsAreDeletedAtTheNextStart() {
        val jar = helloJar()
        Start().run().manager.disableAll() // the earlier run has ended: it holds no snapshot open
        val first = Files.list(root.resolve("cache/plugins")).use { it.toList() }.single()
        helloJar(version = "2.0")
        Start().run()
        assertFalse(Files.exists(first))
        assertEquals(1, Files.list(root.resolve("cache/plugins")).use { it.count() })
        assertTrue(Files.exists(jar))
    }

    @Test
    fun aJarWhoseManifestHasAClassPathIsRejected() {
        val manifest = "Manifest-Version: 1.0\r\nClass-Path: other.jar /tmp/evil.jar\r\n\r\n".toByteArray()
        jar("cp.jar", "hello", pluginYml("Cp", hello), mapOf("META-INF/MANIFEST.MF" to manifest))

        val entry = Start().run().entry("Cp")

        assertEquals(PluginState.REJECTED, entry.state)
        assertContains(entry.detail, "Class-Path")
    }

    @Test
    fun aPluginCannotTakeTheNameOfTheCoreAndAFailingOneNeverTakesBackCoreRegistrations() {
        for (name in listOf("core", "Core", "CORE", "console")) {
            jar("$name.jar", "boom", pluginYml(name, "testplugins.boom.BoomPlugin"))
        }
        val start = Start()
        start.commands.register(object : xyz.felismp.shoparchive.api.Command {
            override val name = "corecmd"
            override val description = "core"
            override fun execute(sender: xyz.felismp.shoparchive.api.CommandSender, args: List<String>) {}
        }, "core")
        start.manager.loadAll()
        start.manager.enableAll()

        assertEquals(setOf(PluginState.REJECTED), start.manager.entries.map { it.state }.toSet())
        assertContains(start.manager.entries.first().detail, "reserved")
        assertEquals("from-core", start.infoName())
        assertNotNull(start.commands.find("corecmd"))
    }

    @Test
    fun aFailingPluginTakesBackOnlyWhatItRegisteredItself() {
        jar("Boom.jar", "boom", pluginYml("Boom", "testplugins.boom.BoomPlugin"))
        val start = Start()
        start.commands.register(object : xyz.felismp.shoparchive.api.Command {
            override val name = "corecmd"
            override val description = "core"
            override fun execute(sender: xyz.felismp.shoparchive.api.CommandSender, args: List<String>) {}
        }, "core")
        start.manager.loadAll()
        start.manager.enableAll()

        assertEquals(PluginState.FAILED, start.state("Boom"))
        assertEquals("from-core", start.infoName())
        assertNotNull(start.commands.find("corecmd"))
    }

    private fun leakyStart(step: String): Start {
        System.setProperty("testplugins.leak", step)
        xyz.felismp.shoparchive.server.Log.start(root)
        jar("Leaky.jar", "leaky", pluginYml("Leaky", "testplugins.leaky.LeakyPlugin"), mapOf("config.yml" to "api-token: hunter2hunter2\n".toByteArray()))
        return Start().run()
    }

    private fun logText() = Files.readString(root.resolve("logs/latest.log"))

    @Test
    fun aSecretQuotedByAnExceptionInOnEnableIsNeitherStoredNorLoggedNorShown() {
        val start = leakyStart("enable")

        assertEquals(PluginState.FAILED, start.state("Leaky"))
        assertContains(start.entry("Leaky").detail, "login failed with ***")
        assertFalse("hunter2hunter2" in start.manager.describe().joinToString("\n"))
        assertFalse("hunter2hunter2" in logText(), logText())
    }

    @Test
    fun aSecretQuotedByAnExceptionInOnDisableOrOnConfigReloadIsNotLogged() {
        val start = leakyStart("reload")
        assertTrue(start.manager.reload("Leaky"))
        System.setProperty("testplugins.leak", "disable")
        start.manager.disableAll()

        val log = logText()
        assertContains(log, "onConfigReload threw IllegalStateException: login failed with ***")
        assertContains(log, "onDisable threw IllegalStateException: login failed with ***")
        assertFalse("hunter2hunter2" in log, log)
    }

    @Test
    fun anUpdateThatCannotBeInstalledPutsTheOldJarBack() {
        val old = helloJar()
        val before = Files.readAllBytes(old)
        val update = jar("update/hello-2.jar", "hello", pluginYml("Hello", hello, "2.0"))
        // The old jar can be moved aside, but the new one cannot be moved out of a folder that cannot be written to.
        Files.createDirectories(plugins.resolve("update/old/20260102-030405"))
        val updateDir = plugins.resolve("update")
        val writable = java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")
        org.junit.jupiter.api.Assumptions.assumeTrue(updateDir.fileSystem.supportedFileAttributeViews().contains("posix"))
        Files.setPosixFilePermissions(updateDir, writable)
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(updateDir), "running as a user who can write anyway")
            PluginUpdates(plugins, Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)).apply()

            assertContentEquals(before, Files.readAllBytes(old))
            assertTrue(Files.exists(update))
        } finally {
            Files.setPosixFilePermissions(updateDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    private fun link(from: Path, to: Path) {
        Files.createDirectories(from.parent)
        try {
            Files.createSymbolicLink(from, to)
        } catch (e: UnsupportedOperationException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here")
        } catch (e: java.io.IOException) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here")
        }
    }

    @Test
    fun aSnapshotFolderThatIsALinkToThePluginsFolderLoadsNothingAndDeletesNothing() {
        val hello = helloJar()
        link(root.resolve("cache/plugins"), plugins)

        val start = Start().run()

        assertTrue(Files.exists(hello), "the installed jar must not be taken for a snapshot")
        assertEquals(emptyList(), start.manager.entries)
        assertContains(start.manager.describe().single(), "cache/plugins")
    }

    @Test
    fun aSnapshotFolderThatLeadsOutsideTheRootIsNeitherCleanedNorWrittenTo() {
        helloJar()
        val outside = Files.createDirectories(root.resolveSibling("${root.fileName}-snap"))
        val foreign = Files.writeString(outside.resolve("${"a".repeat(64)}.jar"), "foreign")
        link(root.resolve("cache"), outside)

        Start().run()

        assertEquals("foreign", Files.readString(foreign))
        assertEquals(1, Files.list(outside).use { it.count() })
    }

    @Test
    fun cleanupDeletesOnlyPlainFilesNamedLikeSnapshots() {
        val dir = Files.createDirectories(root.resolve("cache/plugins"))
        val stale = Files.writeString(dir.resolve("${"b".repeat(64)}.jar"), "old snapshot")
        val other = Files.writeString(dir.resolve("mine.jar"), "not ours")
        val target = Files.writeString(root.resolve("precious.jar"), "precious")
        link(dir.resolve("${"c".repeat(64)}.jar"), target)

        Start().run()

        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(other))
        assertEquals("precious", Files.readString(target))
        assertTrue(Files.isSymbolicLink(dir.resolve("${"c".repeat(64)}.jar")))
    }

    @Test
    fun aMoveAsideThatWasDoneButNotSyncedIsStillRolledBack() {
        org.junit.jupiter.api.Assumptions.assumeTrue(plugins.fileSystem.supportedFileAttributeViews().contains("posix"))
        val old = helloJar()
        val before = Files.readAllBytes(old)
        jar("update/hello-2.jar", "hello", pluginYml("Hello", hello, "2.0"))
        // Write+search but no read: the move into it works, opening the folder to fsync it does not.
        val aside = Files.createDirectories(plugins.resolve("update/old/20260102-030405"))
        Files.setPosixFilePermissions(aside, java.nio.file.attribute.PosixFilePermissions.fromString("-wx------"))
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(aside), "running as a user who can read anyway")
            PluginUpdates(plugins, Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)).apply()

            assertContentEquals(before, Files.readAllBytes(old))
        } finally {
            Files.setPosixFilePermissions(aside, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }
}
