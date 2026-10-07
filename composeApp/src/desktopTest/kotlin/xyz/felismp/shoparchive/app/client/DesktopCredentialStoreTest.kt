package xyz.felismp.shoparchive.app.client

import xyz.felismp.shoparchive.shared.DeviceMode
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCredentialStoreTest {
    private val server = StoredServer("sid", "Shop", "AB".repeat(32), listOf("10.0.0.2:8443"), "dev-1", listOf(StoredUser("alice", "cred-secret"), StoredUser("bob", "cred-2")), DeviceMode.SHARED)
    private val creds = StoredServers(listOf(server), lastServerId = "sid")

    @Test fun roundTripClearAndMissingFile() {
        val dir = Files.createTempDirectory("sa-cred").resolve("nested")
        val store = DesktopCredentialStore(dir, PlainProtector)
        assertNull(store.load())
        store.save(creds)
        assertEquals(creds, DesktopCredentialStore(dir, PlainProtector).load())
        store.save(StoredServers(listOf(server.copy(users = server.users.take(1))))) // overwrite
        assertEquals(listOf("alice"), store.load()?.servers?.single()?.users?.map { it.username })
        store.clear()
        assertNull(store.load())
    }

    @Test fun fileIsOwnerOnlyOnPosix() {
        if (!Files.getFileStore(Files.createTempDirectory("sa-cred")).supportsFileAttributeView("posix")) return
        val dir = Files.createTempDirectory("sa-cred")
        DesktopCredentialStore(dir, PlainProtector).save(creds)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("credentials.bin"))))
    }

    @Test fun protectorOutputIsWhatReachesTheDisk() {
        val dir = Files.createTempDirectory("sa-cred")
        val reversing = object : Protector {
            override fun protect(data: ByteArray) = data.reversedArray()
            override fun unprotect(data: ByteArray) = data.reversedArray()
        }
        val store = DesktopCredentialStore(dir, reversing)
        store.save(creds)
        assertFalse(String(Files.readAllBytes(dir.resolve("credentials.bin"))).contains("cred-secret"))
        assertEquals(creds, store.load())
    }

    @Test fun unreadableFileLoadsAsNull() {
        val dir = Files.createTempDirectory("sa-cred")
        Files.write(dir.resolve("credentials.bin"), byteArrayOf(1, 2, 3))
        assertNull(DesktopCredentialStore(dir, PlainProtector).load())
    }

    @Test fun twoServersRoundTrip() {
        val dir = Files.createTempDirectory("sa-cred")
        val two = StoredServers(listOf(server, StoredServer("sid-2", "Branch", "CD".repeat(32), listOf("shop.example.com:25655"), "dev-9", listOf(StoredUser("carol", "c")))), lastServerId = "sid-2")
        DesktopCredentialStore(dir, PlainProtector).save(two)
        assertEquals(two, DesktopCredentialStore(dir, PlainProtector).load())
    }

    @Test fun anOldSingleServerFileLoadsAsOneServerAndTheNextSaveWritesTheList() {
        val dir = Files.createTempDirectory("sa-cred")
        // What the app wrote before it kept a list of servers: one server's credentials at the top.
        val old = """{"deviceId":"dev-1","serverId":"sid","certPin":"${"AB".repeat(32)}","endpoints":["10.0.0.2:8443"],""" +
            """"users":[{"username":"alice","credential":"cred-secret"},{"username":"bob","credential":"cred-2"}],"mode":"shared"}"""
        Files.write(dir.resolve("credentials.bin"), old.toByteArray())
        val store = DesktopCredentialStore(dir, PlainProtector)
        val loaded = assertNotNull(store.load())
        assertEquals(StoredServers(listOf(server.copy(name = "")), lastServerId = "sid"), loaded)
        store.save(loaded)
        val written = String(Files.readAllBytes(dir.resolve("credentials.bin")))
        assertTrue(written.startsWith("{\"servers\":"), written)
        assertEquals(loaded, store.load())
    }

    @Test fun neverStoresTokenPinOrPassword() {
        listOf(StoredServers.serializer().descriptor, StoredServer.serializer().descriptor, StoredUser.serializer().descriptor).forEach { d ->
            val names = (0 until d.elementsCount).map { d.getElementName(it).lowercase() }
            assertTrue(names.none { "token" in it || "password" in it || it == "pin" }, names.toString())
        }
    }
}
