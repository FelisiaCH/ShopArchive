package xyz.felismp.shoparchive.server.plugins

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.plugin.PluginRouteService
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.PluginSettings
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Loads `plugins/template` as Gradle built it (the test task depends on its jar): a template that does not load is no template. */
class PluginTemplateTest {
    @TempDir
    lateinit var root: Path

    private val jar = Path.of(requireNotNull(System.getProperty("shoparchive.templateJar")) { "the test task does not name the template jar" })

    private val said = mutableListOf<String>()
    private val console = object : CommandSender {
        override val name = "console"
        override fun sendMessage(message: String) { said += message }
    }

    /** Lets the caller hold the permissions in [granted]. */
    private fun authGranting(granted: Set<String>) = Proxy.newProxyInstance(AuthService::class.java.classLoader, arrayOf(AuthService::class.java)) { _, method, args ->
        if (method.name == "hasPermission") args[1] in granted else error("${method.name} is not used here")
    } as AuthService

    @Test
    fun theTemplateJarHasNoKotlinInsideAndLoadsWithItsCommandRouteNodeAndConfig() {
        JarFile(jar.toFile()).use { jarFile ->
            assertTrue(jarFile.entries().asSequence().none { it.name.startsWith("kotlin/") || it.name.startsWith("kotlinx/") })
        }
        Files.createDirectories(root.resolve("plugins"))
        Files.copy(jar, root.resolve("plugins/template.jar"))
        val services = Services()
        val commands = Commands()
        val permissions = Permissions()
        val manager = PluginManager(root, PluginSettings(requireApproval = false), services, commands, permissions)
        services.register(PluginRouteService::class.java, DefaultPluginRouteService(manager.routes, services), 0, "core")
        services.register(AuthService::class.java, authGranting(setOf("template.greet")), 0, "test")

        manager.loadAll()
        manager.enableAll()

        assertEquals(PluginState.ENABLED, manager.entries.single().state, manager.entries.single().detail)
        assertTrue("template.greet" in permissions.all().map { it.node })
        assertContains(Files.readString(root.resolve("plugins/Template/config.yml")), "greeting: Hello")
        commands.run(console, "template Noy")
        assertEquals(listOf("Hello, Noy"), said)

        val route = services.get(PluginRouteService::class.java)!!
        val noy = Principal("u-noy", "noy", "dev", "session")
        val answer = route.handle(noy, "Template", "GET", listOf("greet"), mapOf("name" to listOf("A \"quoted\" name")), "")
        assertEquals(200, answer.status)
        assertEquals("""{"message":"Hello, A \"quoted\" name"}""", answer.body)

        Files.writeString(root.resolve("plugins/Template/config.yml"), "greeting: Sawasdee\nshout: true\n")
        assertTrue(manager.reload("Template"))
        said.clear()
        commands.run(console, "template Noy")
        assertEquals(listOf("SAWASDEE, NOY"), said)
        manager.disableAll()
    }

    @Test
    fun withoutThePermissionTheGreetingEndpointAnswers403() {
        Files.createDirectories(root.resolve("plugins"))
        Files.copy(jar, root.resolve("plugins/template.jar"))
        val services = Services()
        val manager = PluginManager(root, PluginSettings(requireApproval = false), services, Commands(), Permissions())
        services.register(PluginRouteService::class.java, DefaultPluginRouteService(manager.routes, services), 0, "core")
        services.register(AuthService::class.java, authGranting(emptySet()), 0, "test")
        manager.loadAll()
        manager.enableAll()

        val answer = services.get(PluginRouteService::class.java)!!.handle(Principal("u", "noy", "d", "s"), "Template", "GET", listOf("greet"), emptyMap(), "")

        assertEquals(403, answer.status)
        assertContains(answer.body, "template.greet")
        manager.disableAll()
    }
}
