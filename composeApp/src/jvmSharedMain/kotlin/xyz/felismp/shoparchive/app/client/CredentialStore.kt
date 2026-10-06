package xyz.felismp.shoparchive.app.client

import kotlinx.serialization.Serializable
import xyz.felismp.shoparchive.shared.DeviceMode

/**
 * One user paired on this device, with the device credential the server issued for that user (each user gets their own).
 * [rejected] is set when the server last answered DEVICE_NOT_RECOGNIZED for the credential (the user may be disabled or locked for a while, or removed):
 * it is only tried last when adding a user, and never deleted. A successful unlock clears it. Files from before have no such field.
 */
@Serializable
data class StoredUser(val username: String, val credential: String, val rejected: Boolean = false)

/**
 * What this device keeps between runs. [certPin] is the server certificate pin (not the user's PIN).
 * There is deliberately no field for the access token, PIN or password: those are never stored.
 */
@Serializable
data class StoredCredentials(
    val deviceId: String,
    val serverId: String,
    val certPin: String,
    val endpoints: List<String>,
    /** One on a personal device; every user paired on a shared one. */
    val users: List<StoredUser>,
    val mode: DeviceMode = DeviceMode.PERSONAL,
)

interface CredentialStore {
    fun load(): StoredCredentials?
    fun save(credentials: StoredCredentials)
    fun clear()
}

/** What a platform needs to place its store: the Android `Context`; nothing on desktop. */
expect abstract class PlatformContext

/** The platform's store: Keystore-wrapped file on Android, DPAPI-protected file on Windows. */
expect fun createCredentialStore(context: PlatformContext): CredentialStore

internal fun StoredCredentials.encode(): ByteArray = clientJson.encodeToString(StoredCredentials.serializer(), this).toByteArray(Charsets.UTF_8)

internal fun decodeCredentials(bytes: ByteArray): StoredCredentials =
    clientJson.decodeFromString(StoredCredentials.serializer(), bytes.toString(Charsets.UTF_8))
