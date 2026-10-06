package xyz.felismp.shoparchive.server

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.text
import xyz.felismp.shoparchive.server.config.write
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandsTest {
    @TempDir
    lateinit var root: Path

    private class RecordingSender : CommandSender {
        override val name = "test"
        val messages = mutableListOf<String>()
        override fun sendMessage(message: String) {
            messages += message
        }
    }

    private class FakeCommand(
        override val name: String,
        private val onExecute: () -> Unit = {},
        private val completions: List<String> = emptyList(),
    ) : Command {
        override val description = "$name description"
        override fun execute(sender: CommandSender, args: List<String>) = onExecute()
        override fun complete(args: List<String>) = completions.filter { it.startsWith(args.last()) }
    }

    private val sender = RecordingSender()
    private val commands = Commands()
    private lateinit var config: ConfigService
    private var stopped = false

    @BeforeTest
    fun setUp() {
        prepareRoot(root)
        Log.start(root)
        config = ConfigService(root, log = RecordingLog())
        config.load()
        registerCoreCommands(commands, config) { stopped = true }
    }

    @AfterTest
    fun tearDown() = Log.close()

    private fun run(line: String) = commands.run(sender, line)

    private fun latestLog() = root.text("logs/latest.log")

    @Test
    fun helpListsEveryRegisteredCommandWithItsDescription() {
        commands.register(FakeCommand("plugincmd"), "plugin-a")

        run("help")

        assertEquals(listOf("help", "plugincmd", "reload", "status", "stop", "version"), commands.all().map { it.name })
        assertEquals(commands.all().map { "${it.name} - ${it.description}" }, sender.messages)
    }

    @Test
    fun anUnknownCommandSaysSoAndPointsToHelp() {
        run("frobnicate now")

        assertEquals(listOf("Unknown command 'frobnicate' (try help)"), sender.messages)
    }

    @Test
    fun commandNamesIgnoreCaseAndBlankLinesDoNothing() {
        run("   ")
        run("VERSION")

        assertEquals(1, sender.messages.size)
        assertTrue(sender.messages[0].startsWith("ShopArchive "), sender.messages[0])
    }

    @Test
    fun versionAndStatusPrintTheirShape() {
        run("version")
        run("status")

        assertEquals(3, sender.messages.size)
        assertTrue(Regex("ShopArchive \\S+").matches(sender.messages[0]), sender.messages[0])
        assertTrue(Regex("Uptime: \\d+h \\d{2}m \\d{2}s").matches(sender.messages[1]), sender.messages[1])
        assertTrue(Regex("Heap: \\d+ MB used of \\d+ MB").matches(sender.messages[2]), sender.messages[2])
    }

    @Test
    fun statusAddsTheNetworkLines() {
        val other = Commands()
        registerCoreCommands(other, config, networkStatus = { listOf("HTTPS: https://x:1", "Certificate fingerprint: AAAA") }) { }

        other.run(sender, "status")

        assertEquals(listOf("HTTPS: https://x:1", "Certificate fingerprint: AAAA"), sender.messages.drop(2))
    }

    @Test
    fun uptimeIsFormattedWithZeroPaddedMinutesAndSeconds() {
        assertEquals("0h 00m 05s", formatUptime(5))
        assertEquals("1h 02m 03s", formatUptime(3723))
        assertEquals("100h 00m 00s", formatUptime(360_000))
    }

    @Test
    fun stopCallsTheStopAction() {
        run("stop")

        assertTrue(stopped)
    }

    @Test
    fun reloadReportsTheChangedKeys() {
        root.write("server.properties", "port=4321\nserver-name=Shop B\n")

        run("reload")

        assertEquals(1, sender.messages.size)
        assertTrue(sender.messages[0].startsWith("Reload done, changed: "), sender.messages[0])
        assertTrue("port" in sender.messages[0] && "server-name" in sender.messages[0], sender.messages[0])
        assertEquals(4321, config.port)
    }

    @Test
    fun reloadWithoutChangesSaysNothingChanged() {
        run("reload")

        assertEquals(listOf("Reload done: nothing changed"), sender.messages)
    }

    @Test
    fun aBrokenFileFailsTheReloadLogsTheErrorAndKeepsTheOldConfig() {
        val zone = config.timezone
        root.write("config/shoparchive.yml", "config-version: 1\ntimezone: [unclosed\n  : :\n")

        run("reload")

        assertEquals(emptyList(), sender.messages)
        assertEquals(zone, config.timezone)
        assertTrue("ERROR] Reload failed, keeping the current configuration: config/shoparchive.yml" in latestLog(), latestLog())
        // ...and the console still works afterwards.
        run("version")
        assertTrue(sender.messages.single().startsWith("ShopArchive "))
    }

    @Test
    fun aCommandThatThrowsIsLoggedAndDoesNotEscape() {
        commands.register(FakeCommand("boom", onExecute = { error("kaboom") }), "plugin-a")

        run("boom")

        assertTrue("Command 'boom' failed" in latestLog() && "kaboom" in latestLog(), latestLog())
    }

    @Test
    fun completionOffersCommandNamesForTheFirstWordAndTheCommandsOwnForTheRest() {
        commands.register(FakeCommand("color", completions = listOf("red", "green")), "plugin-a")

        assertEquals(listOf("color"), commands.complete(listOf("co")))
        assertEquals(listOf("reload"), commands.complete(listOf("re")))
        assertEquals(commands.all().map { it.name }, commands.complete(emptyList()))
        assertEquals(listOf("green"), commands.complete(listOf("color", "g")))
        assertEquals(emptyList(), commands.complete(listOf("version", "")))
        assertEquals(emptyList(), commands.complete(listOf("nope", "")))
    }

    // --- the log follows the config ---

    @Test
    fun changingLogLevelAndReloadingTakesEffect() {
        applyConfig(config)
        root.write("server.properties", "log-level=error\n")
        run("reload")
        Log.warn("warn-while-error")
        Log.error("error-while-error")
        root.write("server.properties", "log-level=debug\n")
        run("reload")
        Log.debug("debug-after-debug")

        val log = latestLog()
        assertTrue("warn-while-error" !in log, log)
        assertTrue("error-while-error" in log, log)
        assertTrue("debug-after-debug" in log, log)
    }

    @Test
    fun aChangedTimezoneIsAppliedOnReloadWithoutLosingLines() {
        Log.info("line-before")
        root.write("config/shoparchive.yml", "config-version: 1\ntimezone: UTC\n")

        run("reload")
        Log.info("line-after")

        assertEquals(ZoneId.of("UTC"), config.timezone)
        val log = latestLog()
        assertTrue("line-before" in log && "line-after" in log, log)
    }

    /** Stands in for the plugin manager: [plugins] are the enabled names; every reload is noted. */
    private class FakePluginReloads(val plugins: List<String>) : PluginReloads {
        val reloaded = mutableListOf<String>()
        override fun names() = plugins
        override fun reload(name: String) = (name in plugins).also { if (it) reloaded += name }
        override fun reloadAll() = plugins.onEach { reloaded += it }
    }

    private fun commandsWith(plugins: PluginReloads, core: Map<String, () -> Unit> = emptyMap()) = Commands().also {
        registerCoreCommands(it, config, core, plugins = plugins) { }
    }

    @Test
    fun reloadWithAPluginNameReloadsOnlyThatPluginAndATabCompletesEnabledPlugins() {
        val plugins = FakePluginReloads(listOf("Shop", "Mailer"))
        val commands = commandsWith(plugins)

        commands.run(sender, "reload Mailer")

        assertEquals(listOf("Mailer"), plugins.reloaded)
        assertEquals(listOf("Reload Mailer done"), sender.messages)
        assertEquals(listOf("Mailer"), commands.complete(listOf("reload", "Ma")))
        sender.messages.clear()
        commands.run(sender, "reload Nope")
        assertEquals(listOf("Usage: reload [Shop] [Mailer]"), sender.messages)
    }

    @Test
    fun plainReloadAlsoReloadsEveryPluginAfterTheCoreConfig() {
        val plugins = FakePluginReloads(listOf("Shop"))
        val commands = commandsWith(plugins)

        commands.run(sender, "reload")

        assertEquals(listOf("Shop"), plugins.reloaded)
        assertEquals(listOf("Reload done: nothing changed", "Plugin configs reloaded: Shop"), sender.messages)
    }

    @Test
    fun aCoreReloadTargetWinsOverAPluginOfTheSameName() {
        val plugins = FakePluginReloads(listOf("users"))
        var usersReloaded = 0
        val commands = commandsWith(plugins, mapOf("users" to { usersReloaded++ }))

        commands.run(sender, "reload users")

        assertEquals(1, usersReloaded)
        assertEquals(emptyList(), plugins.reloaded)
        assertEquals(listOf("users"), commands.complete(listOf("reload", "")))
    }
}
