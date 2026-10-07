package xyz.felismp.shoparchive.server.plugins

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.FIXED_CLOCK
import xyz.felismp.shoparchive.server.config.PluginSettings
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.text
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.server.users.registerUserCommands
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The order Main uses: plugins load (and register their nodes) first, then the user files are read. */
class PluginPermissionsTest {
    @TempDir
    lateinit var root: Path

    private val messages = mutableListOf<String>()
    private val sender = object : CommandSender {
        override val name = "test"
        override fun sendMessage(message: String) { messages += message }
    }

    /** Disabled at the end, which closes the plugin jars (on Windows the temp folder cannot be deleted while they are open). */
    private var manager: PluginManager? = null

    @AfterTest
    fun tearDown() {
        manager?.disableAll()
    }

    private fun startServerPartsWith(vararg jars: Pair<String, String>): Pair<UserStore, Commands> {
        prepareRoot(root)
        // (plugin name, main class of a fixture under testplugins/<package>/)
        jars.forEach { (name, main) -> buildPluginJar(root.resolve("plugins/$name.jar"), main.split('.')[1], pluginYml(name, main)) }
        val nodes = Permissions()
        val commands = Commands()
        manager = PluginManager(root, PluginSettings(requireApproval = false), Services(), commands, nodes).also { it.loadAll() }
        val config = ConfigService(root, log = RecordingLog(), clock = FIXED_CLOCK).also { it.load() }
        val users = UserStore(root, nodes, config, RecordingLog(), FIXED_CLOCK).also { it.load() }
        registerUserCommands(commands, users)
        return users to commands
    }

    @Test
    fun aPluginNodeRegisteredInOnLoadIsInPermissionsTxtAndInUserFilesAndCanBeGranted() {
        val (users, commands) = startServerPartsWith("Hello" to "testplugins.hello.HelloPlugin")

        assertTrue("hello.use | default: true | Use the hello plugin" in root.text("user/permissions.txt"), root.text("user/permissions.txt"))
        commands.run(sender, "user add lek")
        assertTrue("  hello.use: true" in root.text("user/lek.yml"), root.text("user/lek.yml"))
        assertTrue(users.hasPermission("lek", "hello.use"))

        commands.run(sender, "perm lek hello.use false")
        assertFalse(users.hasPermission("lek", "hello.use"))
        assertTrue("  hello.use: false" in root.text("user/lek.yml"))
        commands.run(sender, "perm lek hello.use true")
        assertTrue(users.hasPermission("lek", "hello.use"))
    }

    @Test
    fun aNodeOfAPluginThatFailedToLoadIsNotListed() {
        startServerPartsWith("Hello" to "testplugins.hello.HelloPlugin", "Boom" to "testplugins.loadboom.LoadBoomPlugin")

        val txt = root.text("user/permissions.txt")
        assertTrue("hello.use" in txt)
        assertFalse("loadboom.use" in txt, txt)
    }
}
