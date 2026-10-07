package xyz.felismp.shoparchive.app.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.felismp.shoparchive.shared.DeviceMode

/**
 * One user logged in on this device, with the device credential the server issued for that user (each user gets their own).
 * [rejected] is set when the server last answered DEVICE_NOT_RECOGNIZED for the credential (the user may be disabled or locked for a while, or removed):
 * it is only tried last when adding a user, and never deleted. A successful unlock clears it. Files from before have no such field.
 */
@Serializable
data class StoredUser(val username: String, val credential: String, val rejected: Boolean = false)

/**
 * One server this device knows. [certPin] is the server certificate pin (not the user's PIN). [deviceId] is null until a user has logged in here.
 * There is deliberately no field for the access token, PIN or password: those are never stored.
 */
@Serializable
data class StoredServer(
    val serverId: String,
    /** The name the server gave when it was added; blank for a server added before names were kept. */
    val name: String,
    val certPin: String,
    val endpoints: List<String>,
    val deviceId: String? = null,
    /** One on a personal device; every user who logged in on a shared one. */
    val users: List<StoredUser> = emptyList(),
    val mode: DeviceMode = DeviceMode.PERSONAL,
    /** The server's [xyz.felismp.shoparchive.shared.AuthPolicy.unlockWithoutPin] at the last config read: one user here then opens with the credential alone. Files from before have no such field. */
    val unlockWithoutPin: Boolean = false,
)

/** What this device keeps between runs: every server it knows, and the one opened last. */
@Serializable
data class StoredServers(val servers: List<StoredServer>, val lastServerId: String? = null)

interface CredentialStore {
    fun load(): StoredServers?
    fun save(servers: StoredServers)
    fun clear()
}

/** What a platform needs to place its store: the Android `Context`; nothing on desktop. */
expect abstract class PlatformContext

/** The platform's store: Keystore-wrapped file on Android, DPAPI-protected file on Windows. */
expect fun createCredentialStore(context: PlatformContext): CredentialStore

internal fun StoredServers.encode(): ByteArray = clientJson.encodeToString(StoredServers.serializer(), this).toByteArray(Charsets.UTF_8)

/** Also reads the file from before the list: one server's credentials at the top (with a `deviceId`), which becomes a list of one. The next save writes the list. */
internal fun decodeServers(bytes: ByteArray): StoredServers {
    val tree = clientJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    if ("deviceId" !in tree) return clientJson.decodeFromJsonElement(StoredServers.serializer(), tree)
    val old = clientJson.decodeFromJsonElement(StoredServer.serializer(), JsonObject(tree + ("name" to JsonPrimitive(""))))
    return StoredServers(listOf(old), lastServerId = old.serverId)
}
