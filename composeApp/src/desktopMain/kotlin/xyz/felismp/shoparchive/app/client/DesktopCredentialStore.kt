package xyz.felismp.shoparchive.app.client

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

actual abstract class PlatformContext

/** The desktop has nothing to pass; use this. */
object DesktopContext : PlatformContext()

internal val isWindows get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal fun appDir(): Path = if (isWindows) {
    Path.of(System.getenv("APPDATA") ?: (System.getProperty("user.home") + "\\AppData\\Roaming"), "ShopArchive")
} else {
    Path.of(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"), "ShopArchive")
}

// Dev only off Windows: there the file is NOT encrypted, only owner-readable.
actual fun createCredentialStore(context: PlatformContext): CredentialStore =
    DesktopCredentialStore(appDir(), if (isWindows) DpapiProtector else PlainProtector)

actual fun createPreferencesStore(context: PlatformContext): PreferencesStore = FilePreferencesStore(appDir().resolve("preferences.json").toFile())

/** Turns the credentials into the bytes that go to disk and back. */
interface Protector {
    fun protect(data: ByteArray): ByteArray
    fun unprotect(data: ByteArray): ByteArray
}

/** Windows DPAPI: only this Windows user on this machine can decrypt. */
object DpapiProtector : Protector {
    override fun protect(data: ByteArray): ByteArray = Crypt32Util.cryptProtectData(data)
    override fun unprotect(data: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(data)
}

/** No encryption (non-Windows development only); relies on the owner-only file mode set by the store. */
object PlainProtector : Protector {
    override fun protect(data: ByteArray) = data
    override fun unprotect(data: ByteArray) = data
}

class DesktopCredentialStore(private val dir: Path, private val protector: Protector) : CredentialStore {
    private val file: Path = dir.resolve("credentials.bin")

    override fun load(): StoredCredentials? {
        if (!Files.exists(file)) return null
        return try {
            decodeCredentials(protector.unprotect(Files.readAllBytes(file)))
        } catch (_: Exception) {
            null // unreadable (other user, damaged): the device has to be paired again
        }
    }

    override fun save(credentials: StoredCredentials) {
        Files.createDirectories(dir)
        val tmp = dir.resolve("credentials.tmp")
        Files.deleteIfExists(tmp)
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(tmp) // not POSIX (Windows): DPAPI protects the content instead
        }
        Files.write(tmp, protector.protect(credentials.encode()))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun clear() {
        Files.deleteIfExists(file)
    }
}
