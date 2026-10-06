package xyz.felismp.shoparchive.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkConfigTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun loaded(network: String? = null): ConfigService {
        if (network != null) root.write("config/shoparchive.yml", "config-version: 1\n\nnetwork:\n$network")
        return ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }
    }

    @Test
    fun defaultsAreWrittenToTheTemplate() {
        val config = loaded()

        assertEquals(emptyList(), config.networkDomains)
        assertEquals(825, config.certValidityDays)
        assertEquals(1024, config.maxBodyKb)
        assertEquals(30, config.requestTimeoutSeconds)
        val text = root.text("config/shoparchive.yml")
        for (key in listOf("domains: []", "cert-validity-days: 825", "max-body-kb: 1024", "request-timeout-seconds: 30")) assertTrue(key in text, key)
    }

    @Test
    fun outOfRangeNumbersAreClampedWithAWarning() {
        val config = loaded("  cert-validity-days: 9999\n  max-body-kb: 1\n  request-timeout-seconds: 100000\n")

        assertEquals(825, config.certValidityDays)
        assertEquals(16, config.maxBodyKb)
        assertEquals(300, config.requestTimeoutSeconds)
        assertEquals(1, log.warningsWith("network.cert-validity-days", "'9999'", "'825'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("network.max-body-kb", "'1'", "'16'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("network.request-timeout-seconds", "'300'").size, log.warnings.toString())

        val low = loaded("  cert-validity-days: 5\n  max-body-kb: 99999999\n  request-timeout-seconds: 1\n")
        assertEquals(30, low.certValidityDays)
        assertEquals(51_200, low.maxBodyKb)
        assertEquals(5, low.requestTimeoutSeconds)
    }

    @Test
    fun domainsAreReadAsALowerCaseListAndKeptOnRewrite() {
        val config = loaded("  domains: [Shop.Example.com, shop.example.com, nas.local]\n")

        assertEquals(listOf("shop.example.com", "nas.local"), config.networkDomains)
        assertTrue("domains: [shop.example.com, nas.local]" in root.text("config/shoparchive.yml"))
        assertEquals(listOf("shop.example.com", "nas.local"), ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }.networkDomains)
    }

    @Test
    fun aDomainThatIsNotAHostNameMakesTheListInvalidSoTheDefaultIsUsed() {
        val config = loaded("  domains: [shop.example.com, \"not a host\"]\n")

        assertEquals(emptyList(), config.networkDomains)
        assertEquals(1, log.warningsWith("network.domains", "invalid value").size, log.warnings.toString())
    }
}
