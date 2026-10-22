package xyz.felismp.shoparchive.app.client

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOutboxStoreTest {
    private val dir = Files.createTempDirectory("sa-outbox").resolve("sub").toFile()
    private val store = FileOutboxStore(dir)

    @Test fun writeReadOverwriteAndRemoveRoundTrip() {
        assertNull(store.read("s1", "alice"))
        store.write("s1", "alice", "one")
        assertEquals("one", store.read("s1", "alice"))
        store.write("s1", "alice", "two")
        assertEquals("two", store.read("s1", "alice"))
        assertNull(store.read("s1", "bob"))
        assertNull(store.read("s2", "alice"))
        store.write("s1", "alice", null)
        assertNull(store.read("s1", "alice"))
        store.write("s1", "alice", null) // removing what is not there is fine
    }

    @Test fun removeServerTakesOnlyThatServersFilesAndNoUserNameIsOnDisk() {
        store.write("s1", "alice", "a")
        store.write("s1", "bob", "b")
        store.write("s2", "alice", "c")
        assertTrue(dir.listFiles()!!.none { "alice" in it.name || "bob" in it.name })
        store.removeServer("s1")
        assertNull(store.read("s1", "alice"))
        assertNull(store.read("s1", "bob"))
        assertEquals("c", store.read("s2", "alice"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
