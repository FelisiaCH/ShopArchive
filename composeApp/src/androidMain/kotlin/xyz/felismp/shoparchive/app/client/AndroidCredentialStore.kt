package xyz.felismp.shoparchive.app.client

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual typealias PlatformContext = android.content.Context

actual fun createCredentialStore(context: PlatformContext): CredentialStore =
    AndroidCredentialStore(File(context.filesDir, "credentials.bin"))

actual fun createPreferencesStore(context: PlatformContext): PreferencesStore =
    FilePreferencesStore(File(context.filesDir, "preferences.json"))

private const val KEY_ALIAS = "shoparchive_credentials"

/** AES-GCM with a non-exportable Android Keystore key; the file holds IV (12 bytes) + ciphertext in app-private storage. */
class AndroidCredentialStore(private val file: File) : CredentialStore {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun load(): StoredServers? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            decodeServers(cipher.doFinal(bytes, 12, bytes.size - 12))
        } catch (_: Exception) {
            null // key lost (e.g. restored backup) or file damaged: pair again
        }
    }

    override fun save(servers: StoredServers) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.iv + cipher.doFinal(servers.encode()))
        check(tmp.renameTo(file)) { "could not replace ${file.name}" }
    }

    override fun clear() {
        file.delete()
    }
}
