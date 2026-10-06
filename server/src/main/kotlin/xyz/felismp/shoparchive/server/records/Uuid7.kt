package xyz.felismp.shoparchive.server.records

import java.security.SecureRandom
import java.util.UUID

private val random = SecureRandom()
private var lastMillis = 0L
private var counter = 0

/**
 * A UUID version 7 (48 bits of time in milliseconds, then random bits), as lower case text. Ids made in one run sort in the order
 * they were made, even within a millisecond: the 12 bits after the version count up (RFC 9562, method 1).
 */
@Synchronized
internal fun newUuid7(millis: Long = System.currentTimeMillis()): String {
    var time = maxOf(millis, lastMillis)
    if (time == lastMillis) {
        counter++
        if (counter > 0xFFF) { // 4096 ids in one millisecond: borrow the next one
            time++
            counter = 0
        }
    } else {
        counter = random.nextInt(0x800) // room to count up
    }
    lastMillis = time
    val bytes = ByteArray(16).also(random::nextBytes)
    for (i in 0 until 6) bytes[i] = (time shr (8 * (5 - i))).toByte()
    bytes[6] = (0x70 or (counter shr 8)).toByte()
    bytes[7] = counter.toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    var high = 0L
    var low = 0L
    for (i in 0 until 8) high = (high shl 8) or (bytes[i].toLong() and 0xFF)
    for (i in 8 until 16) low = (low shl 8) or (bytes[i].toLong() and 0xFF)
    return UUID(high, low).toString()
}
