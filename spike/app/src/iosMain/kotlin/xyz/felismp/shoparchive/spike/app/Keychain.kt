@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package xyz.felismp.shoparchive.spike.app

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

// T5: one generic-password item, fixed strings (never the bundle ID: AltStore rewrites it), no access group.
private const val SERVICE = "xyz.felismp.shoparchive.spike.keychain"
private const val ACCOUNT = "spike-item"

/** Runs [block] with a query for our item plus [extra] attributes; every CF object created here is released. */
private fun <T> query(extra: List<Pair<CFStringRef?, CFTypeRef?>> = emptyList(), block: (CFDictionaryRef?) -> T): T {
    val service = CFBridgingRetain(SERVICE)
    val account = CFBridgingRetain(ACCOUNT)
    val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    try {
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, service)
        CFDictionaryAddValue(dict, kSecAttrAccount, account)
        extra.forEach { (k, v) -> CFDictionaryAddValue(dict, k, v) }
        return block(dict)
    } finally {
        CFBridgingRelease(dict)
        CFBridgingRelease(service)
        CFBridgingRelease(account)
    }
}

/** The stored string, or null when the item does not exist. Throws on any other Keychain status. */
fun keychainRead(): String? = query(listOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) { q ->
    memScoped {
        val result = alloc<CFTypeRefVar>()
        when (val status = SecItemCopyMatching(q, result.ptr)) {
            errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
            errSecItemNotFound -> null
            else -> error("SecItemCopyMatching status=$status")
        }
    }
}

/** Replaces the item (delete + add) with kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly. Throws on failure. */
fun keychainWrite(value: String) {
    query { q ->
        val status = SecItemDelete(q)
        check(status == errSecSuccess || status == errSecItemNotFound) { "SecItemDelete status=$status" }
    }
    val data = CFBridgingRetain(value.encodeToByteArray().toNSData())
    try {
        query(listOf(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)) { q ->
            val status = SecItemAdd(q, null)
            check(status == errSecSuccess) { "SecItemAdd status=$status" }
        }
    } finally {
        CFBridgingRelease(data)
    }
}
