package xyz.felismp.shoparchive.server.plugins

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.PluginSettings
import xyz.felismp.shoparchive.server.notify.DefaultShopEvents
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What plugins get of [ShopEvents]: their own subscriptions, taken back when they fail or are disabled. */
class PluginEventsTest {
    @TempDir
    lateinit var root: Path

    private val events = DefaultShopEvents(Runnable::run)
    private val services = Services().also { it.register(ShopEvents::class.java, events, 0, "core") }
    private val manager get() = PluginManager(root, PluginSettings(requireApproval = false, disableTimeoutMs = 2_000), services, Commands(), Permissions())

    private fun install(name: String, fixture: String, main: String) {
        buildPluginJar(root.resolve("plugins/$name.jar"), fixture, pluginYml(name, main))
    }

    private fun publish() = events.publish(object : ShopEvent {
        override val type = "entry.created"
        override val branch = "main"
        override val at = "2026-10-03T15:00:00+07:00"
    })

    private fun lines(plugin: String): List<String> {
        val file = root.resolve("plugins/$plugin/events.txt")
        return if (Files.exists(file)) Files.readAllLines(file) else emptyList()
    }

    @Test
    fun aPluginHearsEventsEvenWhenItsOtherListenerThrowsAndTheFailureIsLoggedUnderItsOwnName() {
        install("Eventful", "eventful", "testplugins.eventful.EventfulPlugin")
        val m = manager.also { it.loadAll(); it.enableAll() }

        publish()
        publish()

        assertEquals(listOf("entry.created", "entry.created"), lines("Eventful"))
        assertEquals(2, events.subscriberCount())
        m.disableAll()
    }

    @Test
    fun theSubscriptionsOfAPluginThatFailedToEnableAreTakenBack() {
        install("EventBoom", "eventboom", "testplugins.eventboom.EventBoomPlugin")
        val m = manager.also { it.loadAll(); it.enableAll() }

        assertEquals(PluginState.FAILED, m.entries.single().state)
        assertEquals(0, events.subscriberCount())
        publish()
        assertEquals(emptyList(), lines("EventBoom"), "the failed plugin's listener is not called")
    }

    @Test
    fun theSubscriptionsOfAPluginThatIsDisabledAreTakenBackEvenThoughItNeverClosedThem() {
        install("Eventful", "eventful", "testplugins.eventful.EventfulPlugin")
        val m = manager.also { it.loadAll(); it.enableAll() }
        publish()

        m.disableAll()
        publish()

        assertEquals(1, lines("Eventful").size)
        assertEquals(0, events.subscriberCount())
    }

    @Test
    fun aPluginsNotifierIsTakenBackWithItsOtherServices() {
        services.register(Notifier::class.java, Notifier { DeliveryResult.Sent }, 0, "core")
        val plugin = Notifier { DeliveryResult.Failed("plugin") }
        services.register(Notifier::class.java, plugin, 5, "plugin:telegram")
        assertEquals(DeliveryResult.Failed::class, services.get(Notifier::class.java)!!.deliver(sample()).let { it::class })

        services.unregisterOwner("plugin:telegram")

        assertEquals(DeliveryResult.Sent, services.get(Notifier::class.java)!!.deliver(sample()))
        assertFalse(services.ownedBy("plugin:telegram").isNotEmpty())
        assertTrue(true)
    }

    private fun sample() = Notification("i", "c", "entry.created", "t", "main", "noy", "lo", emptyMap())
}
