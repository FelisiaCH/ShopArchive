package xyz.felismp.shoparchive.server.net

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.FIXED_CLOCK
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.backups
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.text
import xyz.felismp.shoparchive.server.config.write
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpBansTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun bans() = IpBans(root, log, FIXED_CLOCK).also { it.load() }

    @Test
    fun aBanAppliesAtOnceIsKeptInTheFileAndSurvivesARestart() {
        val bans = bans()
        assertFalse(bans.isBanned("203.0.113.9"))

        assertTrue(bans.ban("203.0.113.9", "scanning", "Console"))

        assertTrue(bans.isBanned("203.0.113.9"))
        assertFalse(bans.isBanned("203.0.113.10"))
        val text = root.text("banned-ips.json")
        for (part in listOf("\"ip\": \"203.0.113.9\"", "\"created\": \"2026-10-02T03:04:05Z\"", "\"source\": \"Console\"", "\"reason\": \"scanning\"")) assertTrue(part in text, part)
        assertTrue(bans().isBanned("203.0.113.9"))
    }

    @Test
    fun pardonLiftsTheBanAndRewritesTheFile() {
        val bans = bans()
        bans.ban("203.0.113.9", "x", "Console")

        assertTrue(bans.pardon("203.0.113.9"))

        assertFalse(bans.isBanned("203.0.113.9"))
        assertFalse(bans().isBanned("203.0.113.9"))
        assertFalse(bans.pardon("203.0.113.9"))
    }

    @Test
    fun banningTwiceKeepsTheFirstEntry() {
        val bans = bans()
        assertTrue(bans.ban("203.0.113.9", "first", "Console"))

        assertFalse(bans.ban("203.0.113.9", "second", "Console"))

        assertEquals(listOf("first"), bans.list().map { it.reason })
    }

    @Test
    fun differentSpellingsOfOneAddressMatch() {
        val bans = bans()
        bans.ban(normalizeIp("::ffff:203.0.113.9")!!, "mapped", "Console")

        assertTrue(bans.isBanned("203.0.113.9"))
        assertEquals("0:0:0:0:0:0:0:1", normalizeIp("::1"))
        assertNull(normalizeIp("example.com"))
        assertNull(normalizeIp("999.1.1.1"))
        assertEquals("fe80:0:0:0:0:0:0:1", normalizeIp("fe80::1%eth0"))
    }

    @Test
    fun anUnreadableFileMeansNoBansAndIsNotTouchedUntilABanIsMade() {
        val broken = "this is {not json".toByteArray()
        Files.write(root.resolve("banned-ips.json"), broken)

        val bans = bans()

        assertEquals(1, log.warningsWith("banned-ips.json", "cannot be read").size, log.warnings.toString())
        assertFalse(bans.isBanned("203.0.113.9"))
        assertFalse(bans.pardon("203.0.113.9"), "nothing to pardon, nothing written")
        assertContentEquals(broken, Files.readAllBytes(root.resolve("banned-ips.json")))

        assertTrue(bans.ban("203.0.113.9", "first ban", "Console"))

        assertEquals(listOf("20261002-030405/banned-ips.json"), root.backups())
        assertContentEquals(broken, Files.readAllBytes(root.resolve("data/migration/20261002-030405/banned-ips.json")))
        assertTrue(bans().isBanned("203.0.113.9"))
    }

    @Test
    fun entriesWithoutAValidIpAreIgnoredWithAWarning() {
        root.write("banned-ips.json", """[{"ip":"nope","created":"c","source":"s","reason":"r"},{"ip":"203.0.113.9","created":"c","source":"s","reason":"r"}]""")

        val bans = bans()

        assertTrue(bans.isBanned("203.0.113.9"))
        assertEquals(1, log.warningsWith("banned-ips.json", "1 entry").size, log.warnings.toString())
    }
}
