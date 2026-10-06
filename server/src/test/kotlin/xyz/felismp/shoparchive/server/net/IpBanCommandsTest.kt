package xyz.felismp.shoparchive.server.net

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.config.FIXED_CLOCK
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.text
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpBanCommandsTest {
    @TempDir
    lateinit var root: Path

    private val messages = mutableListOf<String>()
    private val sender = object : CommandSender {
        override val name = "Console"
        override fun sendMessage(message: String) {
            messages += message
        }
    }
    private val commands = Commands()
    private lateinit var bans: IpBans

    @BeforeTest
    fun setUp() {
        prepareRoot(root)
        bans = IpBans(root, RecordingLog(), FIXED_CLOCK).also { it.load() }
        registerIpBanCommands(commands, bans)
    }

    @Test
    fun banIpBansAtOnceWithTheReasonAndTheSenderAsSource() {
        commands.run(sender, "ban-ip 203.0.113.9 too many wrong codes")

        assertEquals(listOf("Banned 203.0.113.9"), messages)
        assertTrue(bans.isBanned("203.0.113.9"))
        val entry = bans.list().single()
        assertEquals("too many wrong codes", entry.reason)
        assertEquals("Console", entry.source)
        assertTrue("too many wrong codes" in root.text("banned-ips.json"))
    }

    @Test
    fun pardonIpLiftsTheBan() {
        commands.run(sender, "ban-ip 203.0.113.9")
        messages.clear()

        commands.run(sender, "pardon-ip 203.0.113.9")
        commands.run(sender, "pardon-ip 203.0.113.9")

        assertEquals(listOf("Pardoned 203.0.113.9", "203.0.113.9 is not banned"), messages)
        assertFalse(bans.isBanned("203.0.113.9"))
    }

    @Test
    fun badInputIsExplainedAndBansNothing() {
        commands.run(sender, "ban-ip")
        commands.run(sender, "ban-ip not-an-ip")
        commands.run(sender, "pardon-ip")

        assertEquals(listOf("Usage: ban-ip <ip> [reason]", "'not-an-ip' is not an IP address", "Usage: pardon-ip <ip>"), messages)
        assertEquals(emptyList(), bans.list())
    }

    @Test
    fun pardonIpCompletesBannedAddresses() {
        commands.run(sender, "ban-ip 203.0.113.9")
        commands.run(sender, "ban-ip 198.51.100.1")

        assertEquals(listOf("203.0.113.9"), commands.complete(listOf("pardon-ip", "20")))
    }
}
