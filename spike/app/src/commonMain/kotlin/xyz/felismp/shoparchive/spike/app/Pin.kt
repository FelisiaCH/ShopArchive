package xyz.felismp.shoparchive.spike.app

import kotlin.io.encoding.Base64

/**
 * DER prefix of an EC P-256 SubjectPublicKeyInfo. iOS `SecKeyCopyExternalRepresentation` returns only the
 * 65-byte uncompressed point, so SPKI = this header + point (same bytes the JVM gets from `publicKey.encoded`).
 */
val P256_SPKI_HEADER: ByteArray = byteArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08,
    0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
)

/** The pin string the server prints: base64(SHA-256(SPKI DER)). Null when it is not exactly 32 bytes of base64. */
fun decodePin(pin: String): ByteArray? =
    try {
        Base64.decode(pin.trim()).takeIf { it.size == 32 }
    } catch (_: IllegalArgumentException) {
        null
    }

/** Constant-time compare of a certificate's SPKI SHA-256 against the pasted pin. An invalid pin never matches. */
fun pinMatches(spkiSha256: ByteArray, pin: String): Boolean {
    val expected = decodePin(pin) ?: return false
    if (spkiSha256.size != 32) return false
    var diff = 0
    for (i in 0 until 32) diff = diff or (expected[i].toInt() xor spkiSha256[i].toInt())
    return diff == 0
}
