package xyz.felismp.shoparchive.app.client

import xyz.felismp.shoparchive.shared.DeviceMode
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCredentialStoreTest {
    private val creds = StoredCredentials("dev-1", "sid", "AB".repeat(32), listOf("10.0.0.2:8443"), listOf(StoredUser("alice", "cred-secret"), StoredUser("bob", "cred-2")), DeviceMode.SHARED)

    @Test fun roundTripClearAndMissingFile() {
        val dir = Files.createTempDirectory("sa-cred").resolve("nested")
        val store = DesktopCredentialStore(dir, PlainProtector)
        assertNull(store.load())
        store.save(creds)
        assertEquals(creds, DesktopCredentialStore(dir, PlainProtector).load())
        store.save(creds.copy(users = creds.users.take(1))) // overwrite
        assertEquals(listOf("alice"), store.load()?.users?.map { it.username })
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

    @Test fun neverStoresTokenPinOrPassword() {
        val names = (0 until StoredCredentials.serializer().descriptor.elementsCount).map { StoredCredentials.serializer().descriptor.getElementName(it).lowercase() }
        assertTrue(names.none { "token" in it || "password" in it || it == "pin" }, names.toString())
    }
}
