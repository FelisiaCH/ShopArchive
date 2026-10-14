package xyz.felismp.shoparchive.app.client

import platform.Foundation.NSUserDefaults

private const val KEY = "preferences"

/** The platform's store: the app's `UserDefaults`. */
fun createPreferencesStore(): PreferencesStore = UserDefaultsPreferencesStore(NSUserDefaults.standardUserDefaults)

/** The same JSON the other platforms keep in `preferences.json`, as one `UserDefaults` value. A missing or damaged value reads as the defaults. */
internal class UserDefaultsPreferencesStore(private val defaults: NSUserDefaults, private val key: String = KEY) : PreferencesStore {
    override fun load(): Preferences = try {
        defaults.stringForKey(key)?.let { clientJson.decodeFromString(Preferences.serializer(), it) } ?: Preferences()
    } catch (_: Exception) {
        Preferences()
    }

    override fun save(preferences: Preferences) {
        defaults.setObject(clientJson.encodeToString(Preferences.serializer(), preferences), forKey = key)
    }
}
