package xyz.felismp.shoparchive.app.client

import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import xyz.felismp.shoparchive.shared.DeviceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class PreferencesStoreIosTest {
    private val suite = "shoparchive-test-" + NSUUID().UUIDString
    private val defaults = NSUserDefaults(suiteName = suite)
    private val store = UserDefaultsPreferencesStore(defaults)

    private fun cleanUp() = NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)

    @Test fun nothingSavedReadsAsTheDefaults() {
        try {
            assertEquals(Preferences(), store.load())
        } finally {
            cleanUp()
        }
    }

    @Test fun savedPreferencesComeBackAndTheLastSaveWins() {
        try {
            store.save(Preferences(language = "lo", branch = "main"))
            assertEquals(Preferences(language = "lo", branch = "main"), store.load())
            store.save(Preferences(language = null, branch = "second"))
            assertEquals(Preferences(language = null, branch = "second"), UserDefaultsPreferencesStore(NSUserDefaults(suiteName = suite)).load())
        } finally {
            cleanUp()
        }
    }

    @Test fun aDamagedValueReadsAsTheDefaults() {
        try {
            defaults.setObject("{not json", forKey = "preferences")
            assertEquals(Preferences(), store.load())
        } finally {
            cleanUp()
        }
    }
}

class KeychainCredentialStoreIosTest {
    private val servers = StoredServers(
        listOf(
            StoredServer(
                serverId = "sid-1", name = "Shop", certPin = "AB".repeat(32), endpoints = listOf("192.168.1.10:8443"), deviceId = "dev-1",
                users = listOf(StoredUser("alice", "cred-1")), mode = DeviceMode.SHARED, unlockWithoutPin = true,
            ),
        ),
        lastServerId = "sid-1",
    )

    // A command-line test process on a headless simulator has no keychain: errSecNotAvailable (-25291) on the CI runner, errSecMissingEntitlement
    // (-34018) where there is one but no entitlement. The tests then have nothing to check here; the app on a device or simulator does have one.
    private fun withStore(test: (KeychainCredentialStore) -> Unit) {
        val store = KeychainCredentialStore("xyz.felismp.shoparchive.test", "servers-" + NSUUID().UUIDString)
        try {
            test(store)
        } catch (e: KeychainException) {
            if (e.status !in NO_KEYCHAIN_HERE) fail("${e.message}")
        } finally {
            try { store.clear() } catch (_: KeychainException) { }
        }
    }

    @Test fun nothingStoredReadsAsNull() = withStore { store ->
        assertNull(store.load())
    }

    @Test fun savedServersComeBackAndTheLastSaveWins() = withStore { store ->
        store.save(servers)
        assertEquals(servers, store.load())
        val changed = servers.copy(lastServerId = null)
        store.save(changed)
        assertEquals(changed, store.load())
    }

    @Test fun clearForgetsEverythingAndIsRepeatable() = withStore { store ->
        store.save(servers)
        store.clear()
        assertNull(store.load())
        store.clear()
    }

    @Test fun itKeepsTheBytesTheOtherPlatformsKeep() = withStore { store ->
        store.save(servers)
        assertEquals(servers, decodeServers(servers.encode()))
        assertEquals(servers, store.load())
    }

    private companion object {
        val NO_KEYCHAIN_HERE = setOf(-25291, -34018)
    }
}
