package xyz.felismp.shoparchive.server.plugins

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.config.PluginSettings
import xyz.felismp.shoparchive.server.notify.Draft
import xyz.felismp.shoparchive.shared.NotificationState
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The P11 verify list with the real channel jars as Gradle built them (Telegram and the example Discord) against a fake endpoint on localhost:
 * which channel takes the messages, what happens when a webhook is blank, and that a channel that is down for a while loses and duplicates nothing.
 */
class ChannelPluginsTest {
    @TempDir
    lateinit var root: Path

    private val telegramJar = Path.of(System.getProperty("shoparchive.telegramJar"))
    private val discordJar = Path.of(System.getProperty("shoparchive.discordJar"))

    /** A fake Telegram + Discord on one port: what each got, and whether it is down (503). */
    private class Fake {
        val telegram = CopyOnWriteArrayList<String>()
        val discord = CopyOnWriteArrayList<String>()
        @Volatile var down = false
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = String(ex.requestBody.readAllBytes(), Charsets.UTF_8)
                val ok = !down
                if (ok) (if (ex.requestURI.path.startsWith("/hook")) discord else telegram) += ex.requestURI.path + " " + body
                val out = "{}".toByteArray()
                ex.sendResponseHeaders(if (ok) (if (ex.requestURI.path.startsWith("/hook")) 204 else 200) else 503, if (ok && ex.requestURI.path.startsWith("/hook")) -1 else out.size.toLong())
                ex.responseBody.use { if (ok && ex.requestURI.path.startsWith("/hook")) Unit else it.write(out) }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    private val fake = Fake()

    @AfterEach
    fun tearDown() {
        fake.server.stop(0)
        Log.close()
    }

    private fun shippedConfig(jar: Path) = JarFile(jar.toFile()).use { it.getInputStream(it.getEntry("config.yml")).readBytes().toString(Charsets.UTF_8) }

    private fun installTelegram(token: String = "111:TELEGRAMSECRET") {
        Files.createDirectories(root.resolve("plugins/Telegram"))
        Files.copy(telegramJar, root.resolve("plugins/shoparchive-telegram.jar"))
        Files.writeString(
            root.resolve("plugins/Telegram/config.yml"),
            shippedConfig(telegramJar).replace("bot-token: \"\"", "bot-token: \"$token\"").replace("chat-ids: []", "chat-ids: [\"111\", \"-100222\"]")
                .replace("https://api.telegram.org", fake.url).replace("language: lo", "language: en"),
        )
    }

    private fun installDiscord(hook: String = "") {
        Files.createDirectories(root.resolve("plugins/ExampleDiscord"))
        Files.copy(discordJar, root.resolve("plugins/example-discord.jar"))
        Files.writeString(root.resolve("plugins/ExampleDiscord/config.yml"), shippedConfig(discordJar).replace("webhook-url: \"\"", "webhook-url: \"$hook\"").replace("language: lo", "language: en"))
    }

    private fun AuthEnv.start(): PluginManager {
        val manager = PluginManager(root, PluginSettings(requireApproval = false), services, Commands(), Permissions())
        manager.loadAll()
        manager.enableAll()
        return manager
    }

    private fun AuthEnv.send(id: String) = notify!!.outbox.enqueue(
        Draft(
            "20261003-$id", "entry.created", "2026-10-03T15:00:00+07:00", "main", "noy", "lo",
            mapOf("type" to "income", "category" to "", "item" to "coffee", "amount" to "150000", "currency" to "LAK", "branch" to "Main Shop", "user" to "noy", "time" to "2026-10-03T15:00:00+07:00", "date" to "2026-10-03"),
        ),
    )

    private fun env(): AuthEnv = AuthEnv(root, NO_BACKOFF, withNotify = true).also { Log.start(root) }

    private fun log(): String { Log.close(); return Files.readString(root.resolve("logs/latest.log")) }

    @Test
    fun theJarsHaveNoKotlinInsideAndAreNamedAsShipped() {
        for (jar in listOf(telegramJar, discordJar)) JarFile(jar.toFile()).use { j ->
            assertTrue(j.entries().asSequence().none { it.name.startsWith("kotlin/") || it.name.startsWith("kotlinx/") }, jar.toString())
        }
        assertEquals("shoparchive-telegram.jar", telegramJar.fileName.toString())
    }

    @Test
    fun telegramAloneTakesTheMessagesAndTheTokenStaysOutOfTheLogAndTheOutbox() = env().run {
        installTelegram()
        val manager = start()
        assertEquals("TelegramNotifier", services.get(Notifier::class.java)!!.javaClass.simpleName)

        send("A3F9C")
        notify!!.outbox.processDue()

        assertEquals(2, fake.telegram.size)
        assertContains(fake.telegram[0], "/bot111:TELEGRAMSECRET/sendMessage")
        assertContains(fake.telegram[0], "A3F9C")
        assertEquals(NotificationState.SENT, notify.outbox.items().single().state)
        manager.disableAll()
        assertFalse("TELEGRAMSECRET" in log())
    }

    @Test
    fun discordInstalledTakesTheMessagesNotTelegramAndRemovingItGoesBackToTelegram() = env().run {
        installTelegram()
        installDiscord("${fake.url}/hook/1/SECRETPART")
        val manager = start()
        assertEquals("DiscordNotifier", services.get(Notifier::class.java)!!.javaClass.simpleName)

        send("D0001")
        notify!!.outbox.processDue()
        assertEquals(1, fake.discord.size)
        assertContains(fake.discord[0], "D0001")
        assertTrue(fake.telegram.isEmpty())

        manager.disableAll()
        Files.delete(root.resolve("plugins/example-discord.jar"))
        val again = start()
        assertEquals("TelegramNotifier", services.get(Notifier::class.java)!!.javaClass.simpleName)
        send("D0002")
        notify.outbox.processDue()
        assertEquals(1, fake.discord.size)
        assertEquals(2, fake.telegram.size)
        again.disableAll()
    }

    @Test
    fun aBlankWebhookWarnsAndTelegramIsUsed() = env().run {
        installTelegram()
        installDiscord("")
        val manager = start()

        assertEquals("TelegramNotifier", services.get(Notifier::class.java)!!.javaClass.simpleName)
        send("B0001")
        notify!!.outbox.processDue()
        assertEquals(2, fake.telegram.size)
        assertTrue(fake.discord.isEmpty())
        manager.disableAll()
        val text = log()
        assertContains(text, "[ExampleDiscord]")
        assertContains(text, "webhook-url is empty")
    }

    @Test
    fun withoutAnyChannelConfigTheCoreLogsAndNothingBreaks() = env().run {
        installTelegram(token = "")
        val manager = start()
        assertEquals("LogNotifier", services.get(Notifier::class.java)!!.javaClass.simpleName)
        send("C0001")
        notify!!.outbox.processDue()
        assertEquals(NotificationState.SENT, notify.outbox.items().single().state)
        manager.disableAll()
        assertContains(log(), "bot-token is empty")
    }

    @Test
    fun aChannelThatIsDownForAWhileDeliversEverythingOnceItIsBackWithoutDuplicates() = env().run {
        installTelegram()
        val manager = start()
        fake.down = true
        send("E0001")
        send("E0002")
        notify!!.outbox.processDue()
        assertTrue(fake.telegram.isEmpty())
        assertTrue(notify.outbox.items().all { it.state == NotificationState.QUEUED })

        fake.down = false
        clock.advance(Duration.ofHours(2))
        notify.outbox.processDue()

        assertEquals(4, fake.telegram.size) // two messages, two chats each, once
        assertEquals(4, fake.telegram.map { it.substringAfter("{") }.toSet().size)
        assertTrue(notify.outbox.items().all { it.state == NotificationState.SENT })
        clock.advance(Duration.ofHours(2))
        notify.outbox.processDue()
        assertEquals(4, fake.telegram.size)
        manager.disableAll()
    }
}
