package xyz.felismp.shoparchive.app.client

import io.ktor.http.fromHttpToGmtDate
import kotlin.math.abs

/** The most the server's clock may differ from the device's before the app warns about it. */
private const val TOLERANCE_MILLIS = 5 * 60_000L

/** How far the server's clock is ahead of the device's (negative: behind), from the `Date` header of an answer received at [deviceMillis]; null when the header is missing or unreadable. */
fun clockOffsetMillis(dateHeader: String?, deviceMillis: Long): Long? =
    dateHeader?.let { runCatching { it.fromHttpToGmtDate().timestamp - deviceMillis }.getOrNull() }

/** The whole minutes (rounded up) the server's clock is off when that is more than five; null when it is close enough or not known. */
fun clockWarningMinutes(offsetMillis: Long?): Int? =
    offsetMillis?.let { abs(it) }?.takeIf { it > TOLERANCE_MILLIS }?.let { ((it + 59_999) / 60_000).toInt() }
