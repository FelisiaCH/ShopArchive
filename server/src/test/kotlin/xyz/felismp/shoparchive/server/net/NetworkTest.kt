package xyz.felismp.shoparchive.server.net

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.CertificateService
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.write
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import java.net.ServerSocket
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkTest {
    @TempDir
    lateinit var root: Path

    private val serverId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private var services = Services()
    private val stopHooks = mutableListOf<() -> Unit>()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    @AfterTest
    fun tearDown() = stopHooks.asReversed().forEach { it() }

    private fun network(port: Int): Network {
        root.write("server.properties", "port=$port\nbind-address=127.0.0.1\nlan-discovery=false\nserver-name=Test Shop\n")
        val config = ConfigService(root, log = RecordingLog()).also { it.load() }
        return Network(root, config, serverId, services) { _, hook -> stopHooks += hook }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test
    fun startRegistersTheCoreServicesAndStatusShowsTheAddressFingerprintAndExpiry() {
        val port = freePort()
        val network = network(port)
        assertEquals(listOf("HTTPS: not running"), network.statusLines())

        network.start()

        val info = assertNotNull(services.get(InfoService::class.java)).info()
        assertEquals("Test Shop", info.name)
        assertEquals(serverId.toString(), info.serverId)
        assertEquals(PROTOCOL_VERSION, info.protocol)
        assertNotNull(services.get(IpBanService::class.java))
        val certificate = assertNotNull(services.get(CertificateService::class.java))
        val status = network.statusLines()
        assertEquals("HTTPS: https://127.0.0.1:$port", status[0])
        assertEquals("Certificate fingerprint: ${certificate.fingerprint}", status[1])
        assertTrue(Regex("Certificate expires: [0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2} UTC").matches(status[2]), status[2])
        assertEquals("Open connections: 0", status[3])
    }

    @Test
    fun theSameFingerprintComesBackAfterARestart() {
        val first = network(freePort()).also { it.start() }.statusLines()[1]
        tearDown()
        stopHooks.clear()
        services = Services()

        assertEquals(first, network(freePort()).also { it.start() }.statusLines()[1])
    }

    @Test
    fun aPortInUseFailsStartWithTheAddress() {
        ServerSocket(0).use { occupied ->
            val network = network(occupied.localPort)

            val error = assertFailsWith<HttpStartException> { network.start() }

            assertTrue("127.0.0.1:${occupied.localPort}" in error.message.orEmpty(), error.message)
        }
    }
}
