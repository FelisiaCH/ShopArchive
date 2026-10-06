package xyz.felismp.shoparchive.app.flow

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

private val secure: Random = SecureRandom().asKotlinRandom()

/** A new UUID of version 7, lower case: the time in milliseconds first, then random bits. The client makes one per entry so a resend is the same entry. */
fun uuidV7(millis: Long, random: Random = secure): String {
    val bytes = random.nextBytes(16)
    for (i in 0 until 6) bytes[i] = (millis shr (8 * (5 - i))).toByte()
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
