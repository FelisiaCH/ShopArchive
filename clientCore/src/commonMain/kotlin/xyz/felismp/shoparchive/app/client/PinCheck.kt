package xyz.felismp.shoparchive.app.client

/** 32 bytes from [pin] (hex, any case; spaces tolerated), or null when it is not exactly that. */
internal fun decodePin(pin: String): ByteArray? {
    val hex = pin.filterNot { it.isWhitespace() }
    if (hex.length != 64) return null
    return ByteArray(32) { i ->
        val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
        val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
        ((hi shl 4) or lo).toByte()
    }
}

/** True when both hold the same bytes; takes as long for a wrong guess as for the right one. */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** Uppercase hex without spaces, the form a pin is stored in. */
internal fun ByteArray.toHexUpper(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()

/** DER prefix of the SubjectPublicKeyInfo of an EC P-256 key (id-ecPublicKey, prime256v1, then a 66-byte BIT STRING). */
private val P256_SPKI_HEADER = byteArrayOf(
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08,
    0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
)

/**
 * The SubjectPublicKeyInfo DER the server hashes (what the JVM's `publicKey.encoded` is) for a P-256 key whose uncompressed point
 * (`04 || X || Y`, 65 bytes) is [point], which is all Apple's Security framework gives back. Null for anything else: the server's key is always P-256.
 */
internal fun p256Spki(point: ByteArray): ByteArray? =
    if (point.size == 65 && point[0] == 0x04.toByte()) P256_SPKI_HEADER + point else null
