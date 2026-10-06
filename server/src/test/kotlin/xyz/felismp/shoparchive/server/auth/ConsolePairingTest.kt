package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsolePairingTest {
    @TempDir
    lateinit var root: Path

    @AfterTest
    fun tearDown() = Log.close()

    private fun env(config: String? = null) = AuthEnv(root, config)

    @Test
    fun userPairShowsTheQrTheLinkTheCodeAndTheFingerprintAndTellsTheSenderOnlyThatItDid() = env().run {
        addUser("mali")

        val replies = console("user pair mali")

        assertEquals(1, replies.size)
        assertFalse(replies.single().contains("shoparchive://") || replies.single().contains("-"), replies.single())
        val text = terminal.joinToString("\n")
        assertTrue(terminal.first().startsWith("Pairing for 'mali', valid for 10 minutes (until "), terminal.first())
        assertTrue(terminal.any { it.startsWith("Link: shoparchive://pair?d=") })
        assertTrue(terminal.any { Regex("Manual code: [BCDFGHJKLMNPQRSTVWXZ]{5}-[BCDFGHJKLMNPQRSTVWXZ]{5}").containsMatchIn(it) })
        assertContains(text, "Server fingerprint: $FINGERPRINT")
        assertTrue(terminal.any { '█' in it }, "the QR is drawn with half blocks")
    }

    @Test
    fun userAddCreatesTheUserAndThenShowsItsPairing() = env().run {
        val replies = console("user add noy")

        assertEquals(listOf("User 'noy' created", "Pairing for 'noy' is shown on the console only, not in the log."), replies)
        assertTrue(terminal.any { it.startsWith("Link: shoparchive://pair?d=") })
        assertEquals(1, auth.pairing.pendingCount())
    }

    @Test
    fun userPairNeedsAnExistingEnabledUserAndTheConsoleSource() = env().run {
        assertEquals(listOf("No user 'nobody'"), console("user pair nobody"))
        assertEquals(listOf("Usage: user pair <name> [--png]"), console("user pair"))
        assertEquals(listOf("Usage: user pair <name> [--png]"), console("user pair mali --jpg"))
        addUser("mali")
        users.setEnabled("mali", false)
        assertEquals(listOf("That user is disabled."), console("user pair mali"))
        assertTrue(terminal.isEmpty())
    }

    @Test
    fun theConsoleCanBeTurnedOffAsASource() = env("config-version: 1\nauth:\n  pairing:\n    sources: [admin, self]\n").run {
        addUser("mali")

        assertContains(console("user pair mali").single(), "turned off")
        assertTrue(terminal.isEmpty())
        // user add still makes the user; it just does not pair.
        assertContains(console("user add noy").last(), "turned off")
        assertTrue(users.userNames().contains("noy"))
    }

    @Test
    fun statusShowsPendingPairingsAndWebSocketConnections() = env().run {
        addUser("mali")
        pair("mali")

        assertEquals(listOf("Pending pairings: 1", "WebSocket connections: 0"), auth.statusLines())
    }

    @Test
    fun sayWithNobodyConnectedSaysSo() = env().run {
        assertEquals(listOf("Sent to 0 connected apps"), console("say hello"))
        assertEquals(listOf("Usage: say <message>"), console("say"))
    }

    @Test
    fun theFirstRunHintNamesTheThreeSteps() {
        val env = env()

        assertTrue(env.log.infos.any { "user add <name>" in it && "op <name>" in it && "user pair <name>" in it }, env.log.infos.toString())
    }

    @Test
    fun whatUserPairPrintsIsNeverWrittenToLogsOrTheAuditLog() {
        val env = env()
        env.addUser("noy", op = true)
        Log.start(root)
        // The real console output: terminal only (and a copy kept here to know what the secrets were).
        val captured = mutableListOf<String>()
        val real = PairingConsole(root, env.settings, env.auth.pairing) { captured += it; Log.terminalOnly(it) }
        val sender = RecordingSender()

        real.show(sender, "noy", png = true)
        Log.info("a line after the pairing")
        sender.messages.forEach { Log.output(it) }
        val link = captured.first { it.startsWith("Link: ") }.removePrefix("Link: ")
        val code = Regex("Manual code: (\\S+)").find(captured.joinToString("\n"))!!.groupValues[1]
        val secrets = listOf(Pairing(link, null).secret, code, code.replace("-", ""), link.substringAfter("?d="))
        Log.close()

        val logs = Files.list(root.resolve("logs")).use { files ->
            files.toList().joinToString("\n") { f ->
                String(if (f.fileName.toString().endsWith(".gz")) GZIPInputStream(Files.newInputStream(f)).use { it.readBytes() } else Files.readAllBytes(f))
            }
        }
        assertContains(logs, "a line after the pairing")
        assertContains(logs, "shown on the console only")
        assertFalse("shoparchive://" in logs || "Manual code" in logs || "█" in logs, logs)
        for (secret in secrets) assertFalse(secret in logs)
        // The image written by --png is a file in tmp/ that holds the secret; that is what tmp/ is for, and it is deleted with the pairing.
        assertEquals(1, Files.list(root.resolve("tmp")).use { it.filter { f -> f.fileName.toString().startsWith("pair-") }.count() }.toInt())
    }
}
