package xyz.felismp.shoparchive.server.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import xyz.felismp.shoparchive.api.CertificateService
import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import xyz.felismp.shoparchive.server.config.backUp
import xyz.felismp.shoparchive.server.writeAtomically
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Locale

/** The keystore in `certs/` exists but cannot be used. Never "fixed" by making a new key: that would break every paired device. */
internal class CertificateException(message: String) : Exception(message)

/** The names a certificate has to be valid for: every address clients may use (as text) and the configured domains. `localhost` is always added. */
internal class CertificateHosts(val addresses: List<String>, val domains: List<String>) {
    val names: Set<String> = (listOf("localhost") + domains + addresses).toSet()
}

/** The certificate in use, with the keystore the HTTPS connector is built from. */
internal class ServerCertificate(
    val keyStore: KeyStore,
    val alias: String,
    val password: CharArray,
    private val certificate: X509Certificate,
) : CertificateService {
    override val fingerprint: String = fingerprintOf(certificate)
    override val expiresAt: Instant = certificate.notAfter.toInstant()
}

/** SHA-256 of the certificate's SubjectPublicKeyInfo (what `PublicKey.encoded` is), uppercase hex in groups of four. */
internal fun fingerprintOf(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        .joinToString("") { "%02X".format(it) }
        .chunked(4).joinToString(" ")

private const val ALIAS = "shoparchive"

/**
 * The server's one certificate: `certs/keystore.p12` (ECDSA P-256 key + self-signed certificate).
 * The keystore password in `certs/keystore.pass` sits next to the keystore, so it only keeps the file from being
 * read by accident or by a casual look; whoever can read `certs/` has the key. The key is the identity every
 * client pins, so it is created once and then only ever put into a new certificate.
 */
internal class CertificateManager(
    private val root: Path,
    private val log: ConfigLog = ConsoleConfigLog,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val keystoreFile = root.resolve("certs").resolve("keystore.p12")
    private val passwordFile = root.resolve("certs").resolve("keystore.pass")

    /**
     * Reads the keystore (creating it on first start) and replaces the certificate if it is about to expire or lacks a name.
     * @throws CertificateException if the keystore exists but cannot be read; nothing is written then.
     */
    fun loadOrCreate(hosts: CertificateHosts, validityDays: Int): ServerCertificate {
        if (!Files.exists(keystoreFile)) return create(hosts, validityDays)

        val oldBytes: ByteArray
        val password: CharArray
        val key: PrivateKey
        val certificate: X509Certificate
        try {
            oldBytes = Files.readAllBytes(keystoreFile)
            password = String(Files.readAllBytes(passwordFile), StandardCharsets.UTF_8).trim().toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(oldBytes), password) }
            key = keyStore.getKey(ALIAS, password) as PrivateKey
            certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        } catch (e: Exception) {
            if (e !is IOException && e !is GeneralSecurityException && e !is ClassCastException && e !is NullPointerException) throw e
            throw CertificateException(
                "certs/keystore.p12 cannot be read (${e.message ?: e.javaClass.simpleName}). It holds the key every paired device " +
                    "trusts, so it is left untouched and no new key is made. Restore certs/keystore.p12 and certs/keystore.pass from a backup."
            )
        }

        val reason = renewalReason(certificate, hosts, validityDays)
            ?: return ServerCertificate(reload(password), ALIAS, password, certificate)
        val backup = backUp(root, "certs/keystore.p12", oldBytes, clock)
        val renewed = sign(KeyPair(certificate.publicKey, key), hosts, validityDays)
        store(renewed, key, password)
        val result = ServerCertificate(reload(password), ALIAS, password, renewed)
        log.warn("Certificate replaced ($reason). Same key, so the fingerprint ${result.fingerprint} and paired devices stay valid; the old keystore is saved as $backup")
        return result
    }

    private fun create(hosts: CertificateHosts, validityDays: Int): ServerCertificate {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }).toCharArray()
        // Password first: a crash between the two writes leaves a password without a keystore, which the next start just redoes.
        writePassword(password)
        val certificate = sign(pair, hosts, validityDays)
        store(certificate, pair.private, password)
        val result = ServerCertificate(reload(password), ALIAS, password, certificate)
        log.info("Certificate created in certs/ (fingerprint ${result.fingerprint})")
        return result
    }

    private fun renewalReason(certificate: X509Certificate, hosts: CertificateHosts, validityDays: Int): String? {
        // At most half the validity, or a 30-day certificate would be replaced at every start.
        val renewBefore = Duration.ofDays(minOf(30L, validityDays / 2L))
        val expiresAt = certificate.notAfter.toInstant()
        if (Duration.between(clock.instant(), expiresAt) < renewBefore) return "it expires on $expiresAt"
        val covered = certificate.subjectAlternativeNames.orEmpty().mapNotNull { it[1] as? String }.toSet()
        val missing = hosts.names.filter { !covers(covered, it) }
        if (missing.isNotEmpty()) return "it does not cover ${missing.joinToString()}"
        return null
    }

    /** IPv6 text differs between writers (`::1` vs `0:0:0:0:0:0:0:1`), so addresses are compared as addresses. */
    private fun covers(covered: Set<String>, name: String): Boolean {
        if (covered.any { it.equals(name, ignoreCase = true) }) return true
        if (!name.contains(':')) return false
        val wanted = InetAddress.getByName(name)
        return covered.any { it.contains(':') && InetAddress.getByName(it) == wanted }
    }

    private fun sign(pair: KeyPair, hosts: CertificateHosts, validityDays: Int): X509Certificate {
        val subject = X500Name("CN=ShopArchive")
        // An hour back, so a client whose clock runs a little slow does not see a certificate from the future.
        val notBefore = clock.instant().minus(Duration.ofHours(1))
        val notAfter = notBefore.plus(Duration.ofDays(validityDays.toLong()))
        val names = hosts.names.map {
            if (it.contains(':') || it.matches(IPV4)) GeneralName(GeneralName.iPAddress, it) else GeneralName(GeneralName.dNSName, it.lowercase(Locale.ROOT))
        }
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(63, SecureRandom()).add(BigInteger.ONE), Date.from(notBefore), Date.from(notAfter), subject, pair.public,
        )
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)))
    }

    private fun store(certificate: X509Certificate, key: PrivateKey, password: CharArray) {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, key, password, arrayOf(certificate))
        }
        val out = ByteArrayOutputStream()
        keyStore.store(out, password)
        writeAtomically(keystoreFile, out.toByteArray())
    }

    /** The keystore as written to disk, so what is served is what the next start reads. */
    private fun reload(password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keystoreFile).use { load(it, password) } }

    private fun writePassword(password: CharArray) {
        Files.createDirectories(passwordFile.parent)
        writeAtomically(passwordFile, String(password).toByteArray(StandardCharsets.UTF_8))
        try {
            Files.setPosixFilePermissions(passwordFile, PosixFilePermissions.fromString("rw-------"))
        } catch (e: UnsupportedOperationException) {
            // Windows: the file keeps the folder's access rules.
        }
    }

    private companion object {
        val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    }
}
