@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package xyz.felismp.shoparchive.app.client

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
import platform.Security.SecItemUpdate
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

// Fixed strings, never the bundle ID (a sideloading tool such as AltStore rewrites it), and no access group.
private const val SERVICE = "xyz.felismp.shoparchive.credentials"
private const val ACCOUNT = "servers"

/** The platform's store: the Keychain. Same as the other platforms, but with the system's own protection instead of an app-made key. */
fun createCredentialStore(): CredentialStore = KeychainCredentialStore()

/** A Keychain call that failed; [status] is the `OSStatus`. */
internal class KeychainException(val status: Int, what: String) : Exception("$what failed (Keychain status $status)")

/**
 * One generic-password item holding the same bytes the other platforms store ([StoredServers.encode]). It is readable once the device has
 * been unlocked after start, so a background wake-up can read it too, as the Android store can; it never leaves this device (no backup,
 * no iCloud). A missing or unreadable item reads as null, so the server has to be added again; a Keychain that cannot answer throws.
 */
internal class KeychainCredentialStore(private val service: String = SERVICE, private val account: String = ACCOUNT) : CredentialStore {
    override fun load(): StoredServers? {
        val bytes = query(listOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)) { q ->
            memScoped {
                val result = alloc<CFTypeRefVar>()
                when (val status = SecItemCopyMatching(q, result.ptr)) {
                    errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()
                    errSecItemNotFound -> null
                    else -> throw KeychainException(status, "SecItemCopyMatching")
                }
            }
        } ?: return null
        return try {
            decodeServers(bytes)
        } catch (_: Exception) {
            null // damaged: add the server again
        }
    }

    /** Updates the item in place, so there is never a moment with no item; adds it the first time. */
    override fun save(servers: StoredServers) {
        val data = CFBridgingRetain(servers.encode().toNSData())
        try {
            val updated = query { q ->
                withDictionary(listOf(kSecValueData to data)) { changes -> SecItemUpdate(q, changes) }
            }
            when (updated) {
                errSecSuccess -> Unit
                errSecItemNotFound -> {
                    val status = query(listOf(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)) { q -> SecItemAdd(q, null) }
                    if (status != errSecSuccess) throw KeychainException(status, "SecItemAdd")
                }
                else -> throw KeychainException(updated, "SecItemUpdate")
            }
        } finally {
            CFBridgingRelease(data)
        }
    }

    override fun clear() {
        val status = query { q -> SecItemDelete(q) }
        if (status != errSecSuccess && status != errSecItemNotFound) throw KeychainException(status, "SecItemDelete")
    }

    /** Runs [block] with a query for this item plus [extra] attributes; every CF object made here is released. */
    private fun <T> query(extra: List<Pair<CFStringRef?, CFTypeRef?>> = emptyList(), block: (CFDictionaryRef?) -> T): T {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(account)
        try {
            return withDictionary(listOf(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef, kSecAttrAccount to accountRef) + extra, block)
        } finally {
            CFBridgingRelease(serviceRef)
            CFBridgingRelease(accountRef)
        }
    }

    private fun <T> withDictionary(items: List<Pair<CFStringRef?, CFTypeRef?>>, block: (CFDictionaryRef?) -> T): T {
        val dict = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            items.forEach { (key, value) -> CFDictionaryAddValue(dict, key, value) }
            return block(dict)
        } finally {
            CFBridgingRelease(dict)
        }
    }
}
