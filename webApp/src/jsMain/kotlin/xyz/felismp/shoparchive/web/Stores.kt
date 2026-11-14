package xyz.felismp.shoparchive.web

import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.felismp.shoparchive.app.client.CredentialStore
import xyz.felismp.shoparchive.app.client.OutboxStore
import xyz.felismp.shoparchive.app.client.Preferences
import xyz.felismp.shoparchive.app.client.PreferencesStore
import xyz.felismp.shoparchive.app.client.StoredServers
import xyz.felismp.shoparchive.app.client.decodeServers
import xyz.felismp.shoparchive.app.client.encode

/** What the stores below keep their text in: the browser's localStorage, or a map in tests. */
interface KeyValueStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

class LocalStorageKeyValue : KeyValueStorage {
    private val storage get() = window.localStorage
    override fun get(key: String): String? = storage.getItem(key)
    override fun set(key: String, value: String) = storage.setItem(key, value)
    override fun remove(key: String) = storage.removeItem(key)
}

internal const val CREDENTIALS_KEY = "shoparchive.servers"
internal const val PREFERENCES_KEY = "shoparchive.preferences"
internal const val OUTBOX_KEY = "shoparchive.outbox"

// clientCore's own Json is internal to it; the same two switches.
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The servers and users this browser knows. The access token, PIN and password are never here (StoredServer has no field for them). A damaged value reads as nothing saved. */
class WebCredentialStore(private val storage: KeyValueStorage) : CredentialStore {
    override fun load(): StoredServers? = try {
        storage.get(CREDENTIALS_KEY)?.let { decodeServers(it.encodeToByteArray()) }
    } catch (_: Exception) {
        null
    }

    override fun save(servers: StoredServers) = storage.set(CREDENTIALS_KEY, servers.encode().decodeToString())

    override fun clear() = storage.remove(CREDENTIALS_KEY)
}

/** A damaged or missing value reads as the defaults. */
class WebPreferencesStore(private val storage: KeyValueStorage) : PreferencesStore {
    override fun load(): Preferences = try {
        storage.get(PREFERENCES_KEY)?.let { json.decodeFromString(Preferences.serializer(), it) } ?: Preferences()
    } catch (_: Exception) {
        Preferences()
    }

    override fun save(preferences: Preferences) = storage.set(PREFERENCES_KEY, json.encodeToString(Preferences.serializer(), preferences))
}

/** One value holding `{serverId: {user: text}}`, so a server's entries go together. Damaged: reads as empty. */
class WebOutboxStore(private val storage: KeyValueStorage) : OutboxStore {
    private fun load(): Map<String, Map<String, String>> = try {
        storage.get(OUTBOX_KEY)?.let { raw ->
            json.parseToJsonElement(raw).jsonObject.mapValues { (_, users) ->
                users.jsonObject.mapNotNull { (user, text) -> text.jsonPrimitive.contentOrNull?.let { user to it } }.toMap()
            }
        } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun save(all: Map<String, Map<String, String>>) {
        val kept = all.filterValues { it.isNotEmpty() }
        if (kept.isEmpty()) storage.remove(OUTBOX_KEY)
        else storage.set(OUTBOX_KEY, JsonObject(kept.mapValues { (_, users) -> JsonObject(users.mapValues { JsonPrimitive(it.value) }) }).toString())
    }

    override fun read(serverId: String, user: String): String? = load()[serverId]?.get(user)

    override fun write(serverId: String, user: String, text: String?) {
        val all = load().toMutableMap()
        val users = (all[serverId] ?: emptyMap()).toMutableMap()
        if (text == null) users.remove(user) else users[user] = text
        all[serverId] = users
        save(all)
    }

    override fun removeServer(serverId: String) = save(load() - serverId)
}
