package xyz.felismp.shoparchive.web

import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.Preferences
import xyz.felismp.shoparchive.app.client.RecordStart
import xyz.felismp.shoparchive.app.client.StoredServer
import xyz.felismp.shoparchive.app.client.StoredServers
import xyz.felismp.shoparchive.app.client.StoredUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeStorage : KeyValueStorage {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class StoresTest {
    private val server = StoredServer(
        serverId = "s1", name = "Shop", certPin = "AB12", endpoints = listOf("a:1"), deviceId = "d1",
        users = listOf(StoredUser("ann", "cred-1")),
    )

    @Test fun credentialsRoundTripAndClear() {
        val store = WebCredentialStore(FakeStorage())
        assertNull(store.load())
        store.save(StoredServers(listOf(server), lastServerId = "s1"))
        assertEquals(StoredServers(listOf(server), "s1"), store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test fun damagedCredentialsReadAsNothing() {
        val storage = FakeStorage()
        for (bad in listOf("", "{", "not json", "[1,2]", """{"servers":"x"}""")) {
            storage.map[CREDENTIALS_KEY] = bad
            assertNull(WebCredentialStore(storage).load(), bad)
        }
    }

    @Test fun credentialsNeverHoldAToken() {
        val storage = FakeStorage()
        WebCredentialStore(storage).save(StoredServers(listOf(server), "s1"))
        WebPreferencesStore(storage).save(Preferences(branch = "b"))
        WebOutboxStore(storage).write("s1", "ann", "{}")
        assertFalse(storage.map.values.any { it.contains("token", ignoreCase = true) })
    }

    @Test fun preferencesRoundTripAndDefaults() {
        val storage = FakeStorage()
        val store = WebPreferencesStore(storage)
        assertEquals(Preferences(), store.load())
        val p = Preferences(language = "th", branch = "main", recordStart = RecordStart.LAST)
        store.save(p)
        assertEquals(p, store.load())
        storage.map[PREFERENCES_KEY] = "{broken"
        assertEquals(Preferences(), store.load())
    }

    @Test fun outboxReadWriteRemove() {
        val store = WebOutboxStore(FakeStorage())
        assertNull(store.read("s1", "ann"))
        store.write("s1", "ann", "a")
        store.write("s1", "bob", "b")
        store.write("s2", "ann", "c")
        assertEquals("a", store.read("s1", "ann"))
        assertEquals("b", store.read("s1", "bob"))
        assertEquals("c", store.read("s2", "ann"))
        store.write("s1", "ann", "a2")
        assertEquals("a2", store.read("s1", "ann"))
        store.write("s1", "ann", null)
        assertNull(store.read("s1", "ann"))
        assertEquals("b", store.read("s1", "bob"))
        store.removeServer("s1")
        assertNull(store.read("s1", "bob"))
        assertEquals("c", store.read("s2", "ann"))
    }

    @Test fun outboxLastRemovalLeavesNoKey() {
        val storage = FakeStorage()
        val store = WebOutboxStore(storage)
        store.write("s1", "ann", "a")
        assertTrue(storage.map.containsKey(OUTBOX_KEY))
        store.write("s1", "ann", null)
        assertFalse(storage.map.containsKey(OUTBOX_KEY))
    }

    @Test fun damagedOutboxReadsAsEmptyAndCanBeWrittenAgain() {
        val storage = FakeStorage()
        storage.map[OUTBOX_KEY] = "[[["
        val store = WebOutboxStore(storage)
        assertNull(store.read("s1", "ann"))
        store.write("s1", "ann", "a")
        assertEquals("a", store.read("s1", "ann"))
        storage.map[OUTBOX_KEY] = """{"s1":"x"}"""
        assertNull(store.read("s1", "ann"))
    }

    @Test fun slipCompressorUnderNodeFailsReadably() = runTest {
        val e = assertFailsWith<IllegalStateException> { webSlipCompressor.compress(byteArrayOf(1, 2, 3)) }
        assertNotNull(e.message)
        assertTrue(e.message!!.contains("browser"))
    }
}
