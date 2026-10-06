package xyz.felismp.shoparchive.spike.server

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
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date

/** Spike-only keystore password: the keystore is a throwaway self-signed key, not a secret worth hiding. */
const val KEYSTORE_PASSWORD = "spike"
const val KEY_ALIAS = "spike"

class Identity(val keyStore: KeyStore, val cert: X509Certificate, val created: Boolean) {
    private val spkiSha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded) // X.509 SubjectPublicKeyInfo DER

    /** The exact string clients paste as the pin. */
    val pin: String = Base64.getEncoder().encodeToString(spkiSha256)
    val fingerprint: String = spkiSha256.joinToString(":") { "%02X".format(it) }
}

fun lanIpv4(): List<Inet4Address> = NetworkInterface.getNetworkInterfaces().asSequence()
    .filter { it.isUp && !it.isLoopback }
    .flatMap { it.inetAddresses.asSequence() }
    .filterIsInstance<Inet4Address>()
    .filter { !it.isLinkLocalAddress }
    .toList()

/** Reuses `<certs>/keystore.p12` if present, otherwise creates an ECDSA P-256 self-signed cert (800 days). */
fun loadOrCreateIdentity(certsDir: Path): Identity {
    Files.createDirectories(certsDir)
    val file = certsDir.resolve("keystore.p12")
    val pw = KEYSTORE_PASSWORD.toCharArray()
    val ks = KeyStore.getInstance("PKCS12")
    if (Files.exists(file)) {
        Files.newInputStream(file).use { ks.load(it, pw) }
        return Identity(ks, ks.getCertificate(KEY_ALIAS) as X509Certificate, created = false)
    }

    val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val name = X500Name("CN=ShopArchive spike")
    val notBefore = Instant.now().minus(1, ChronoUnit.DAYS)
    val builder = JcaX509v3CertificateBuilder(
        name, BigInteger(63, SecureRandom()).add(BigInteger.ONE),
        Date.from(notBefore), Date.from(notBefore.plus(800, ChronoUnit.DAYS)), // <= 825 days
        name, kp.public,
    )
    val sans = listOf(GeneralName(GeneralName.dNSName, "localhost"), GeneralName(GeneralName.iPAddress, "127.0.0.1")) +
        lanIpv4().map { GeneralName(GeneralName.iPAddress, it.hostAddress) }
    builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
    builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
    val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)))

    ks.load(null, null)
    ks.setKeyEntry(KEY_ALIAS, kp.private, pw, arrayOf(cert))
    Files.newOutputStream(file).use { ks.store(it, pw) }
    return Identity(ks, cert, created = true)
}
