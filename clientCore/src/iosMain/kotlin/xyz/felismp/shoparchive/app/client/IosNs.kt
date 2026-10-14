@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package xyz.felismp.shoparchive.app.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.appendBytes
import platform.posix.memcpy

// The same conversions Ktor's Darwin engine uses inside (they are internal there).
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
