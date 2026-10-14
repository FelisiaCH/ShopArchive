package xyz.felismp.shoparchive.app.client

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Checks on the JVM that the vector the iOS test relies on (see SpkiVector.kt) is what the JVM computes from the same certificate. */
class SpkiVectorTest {
    private val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(VECTOR_CERT_DER_BASE64))) as X509Certificate

    @Test fun jvmEncodesTheKeyAsTheVectorSays() {
        assertContentEquals(VECTOR_SPKI_HEX.hexToBytes(), cert.publicKey.encoded)
    }

    @Test fun jvmPinOfTheCertificateIsTheVectorPin() {
        assertEquals(VECTOR_PIN, MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).toHexUpper())
    }

    @Test fun thePointIsTheKeyAndTheSpkiBuiltFromItIsWhatTheJvmEncodes() {
        val w = (cert.publicKey as ECPublicKey).w
        fun ByteArray.fixed32() = ByteArray(32).also { out -> copyOfRange(maxOf(0, size - 32), size).copyInto(out, 32 - minOf(32, size)) }
        val point = byteArrayOf(0x04) + w.affineX.toByteArray().fixed32() + w.affineY.toByteArray().fixed32()
        assertContentEquals(VECTOR_POINT_HEX.hexToBytes(), point)
        assertContentEquals(cert.publicKey.encoded, p256Spki(point))
    }
}
