package xyz.felismp.shoparchive.server.tls

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.FIXED_CLOCK
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.backups
import xyz.felismp.shoparchive.server.config.prepareRoot
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CertificateManagerTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()
    private val lan = CertificateHosts(listOf("192.168.1.20", "fe80:0:0:0:1:2:3:4"), emptyList())
    private val keystore get() = root.resolve("certs/keystore.p12")

    @BeforeTest
    fun setUp() = prepareRoot(root).also { Files.createDirectories(root.resolve("certs")) }

    private fun manager(clock: Clock = FIXED_CLOCK) = CertificateManager(root, log, clock)

    private fun certOf(cert: ServerCertificate) = cert.keyStore.getCertificate(cert.alias) as X509Certificate

    private fun sans(cert: ServerCertificate) = certOf(cert).subjectAlternativeNames.map { it[1] as String }.toSet()

    @Test
    fun firstStartCreatesAnEcP256KeystoreWithItsPasswordAndTheNames() {
        val cert = manager().loadOrCreate(CertificateHosts(listOf("192.168.1.20"), listOf("shop.example.com")), 825)

        assertTrue(Files.exists(keystore) && Files.exists(root.resolve("certs/keystore.pass")))
        val x509 = certOf(cert)
        assertEquals("EC", x509.publicKey.algorithm)
        assertEquals(setOf("localhost", "shop.example.com", "192.168.1.20"), sans(cert))
        assertTrue(Duration.between(x509.notBefore.toInstant(), x509.notAfter.toInstant()) <= Duration.ofDays(825))
        assertEquals(1, log.infos.count { "Certificate created" in it })
    }

    @Test
    fun fingerprintIsTheSpkiSha256InUppercaseGroupsOfFour() {
        val cert = manager().loadOrCreate(lan, 825)

        assertTrue(Regex("([0-9A-F]{4} ){15}[0-9A-F]{4}").matches(cert.fingerprint), cert.fingerprint)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(certOf(cert).publicKey.encoded)
        assertEquals(digest.joinToString("") { "%02X".format(it) }, cert.fingerprint.replace(" ", ""))
    }

    @Test
    fun theCertificateIsCreatedOnceAndReusedAcrossRestarts() {
        val first = manager().loadOrCreate(lan, 825)
        val bytes = Files.readAllBytes(keystore)

        val second = manager().loadOrCreate(lan, 825)

        assertEquals(first.fingerprint, second.fingerprint)
        assertContentEquals(bytes, Files.readAllBytes(keystore), "an unchanged certificate is not rewritten")
        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun aNewLanAddressGetsANewCertificateWithTheSameKeyAndTheOldKeystoreIsSaved() {
        val first = manager().loadOrCreate(lan, 825)
        val oldBytes = Files.readAllBytes(keystore)

        val moved = CertificateHosts(listOf("10.0.0.7"), listOf("shop.example.com"))
        val second = manager().loadOrCreate(moved, 825)

        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue("10.0.0.7" in sans(second) && "shop.example.com" in sans(second))
        assertNotEquals(certOf(first).serialNumber, certOf(second).serialNumber)
        assertEquals(listOf("20261002-030405/certs/keystore.p12"), root.backups())
        assertContentEquals(oldBytes, Files.readAllBytes(root.resolve("data/migration/20261002-030405/certs/keystore.p12")))
        assertEquals(1, log.warnings.count { "Certificate replaced" in it && "10.0.0.7" in it }, log.warnings.toString())
        // And it is what the next start reads.
        assertEquals(second.fingerprint, manager().loadOrCreate(moved, 825).fingerprint)
    }

    @Test
    fun ipv6AddressesAreComparedAsAddressesNotAsText() {
        val first = manager().loadOrCreate(CertificateHosts(listOf("2001:db8:0:0:0:0:0:1"), emptyList()), 825)

        manager().loadOrCreate(CertificateHosts(listOf("2001:db8:0:0:0:0:0:1"), emptyList()), 825)

        assertEquals(emptyList(), root.backups())
        assertEquals(first.fingerprint, manager().loadOrCreate(CertificateHosts(listOf("2001:db8:0:0:0:0:0:1"), emptyList()), 825).fingerprint)
    }

    @Test
    fun aCertificateAboutToExpireIsReplacedWithTheSameKey() {
        val first = manager().loadOrCreate(lan, 100)
        val later = Clock.fixed(Instant.parse("2026-10-02T03:04:05Z").plus(Duration.ofDays(80)), ZoneOffset.UTC)

        val second = manager(later).loadOrCreate(lan, 100)

        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(certOf(second).notAfter.after(certOf(first).notAfter))
        assertEquals(1, root.backups().size)
        assertEquals(1, log.warnings.count { "Certificate replaced" in it && "expires" in it }, log.warnings.toString())
    }

    @Test
    fun aShortValidityDoesNotMakeEveryStartRenew() {
        manager().loadOrCreate(lan, 30)

        manager().loadOrCreate(lan, 30)

        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun anUnreadableKeystoreIsRefusedAndLeftUntouched() {
        manager().loadOrCreate(lan, 825)
        val broken = ByteArray(200) { it.toByte() }
        Files.write(keystore, broken)
        val passBefore = Files.readAllBytes(root.resolve("certs/keystore.pass"))

        val error = assertFailsWith<CertificateException> { manager().loadOrCreate(lan, 825) }

        assertTrue("left untouched" in error.message.orEmpty(), error.message)
        assertContentEquals(broken, Files.readAllBytes(keystore))
        assertContentEquals(passBefore, Files.readAllBytes(root.resolve("certs/keystore.pass")))
        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun aKeystoreWithoutItsPasswordFileIsRefusedToo() {
        manager().loadOrCreate(lan, 825)
        val before = Files.readAllBytes(keystore)
        Files.delete(root.resolve("certs/keystore.pass"))

        assertFailsWith<CertificateException> { manager().loadOrCreate(lan, 825) }

        assertContentEquals(before, Files.readAllBytes(keystore))
        assertTrue(!Files.exists(root.resolve("certs/keystore.pass")))
    }

    @Test
    fun theKeystoreOnDiskOpensWithThePasswordFile() {
        val cert = manager().loadOrCreate(lan, 825)
        val password = Files.readString(root.resolve("certs/keystore.pass")).trim().toCharArray()

        val ks = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keystore).use { load(it, password) } }

        assertEquals(cert.alias, ks.aliases().toList().single())
    }
}
