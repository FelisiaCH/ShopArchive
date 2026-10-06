@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package xyz.felismp.shoparchive.spike.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.appendBytes
import platform.posix.memcpy

// Same conversions Ktor's Darwin engine uses internally (they are internal there).
internal fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    if (result.isEmpty()) return result
    result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}

internal fun ByteArray.toNSData(): NSData = NSMutableData().apply {
    if (isEmpty()) return@apply
    this@toNSData.usePinned { appendBytes(it.addressOf(0), size.convert()) }
}
