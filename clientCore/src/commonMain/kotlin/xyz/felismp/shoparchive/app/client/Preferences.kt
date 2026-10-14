package xyz.felismp.shoparchive.app.client

import kotlinx.serialization.Serializable

/** Settings that are not secret. [language] is `en`, `lo` or `th`; null follows the system. [branch] is the branch key last worked in on this device. */
@Serializable
data class Preferences(val language: String? = null, val branch: String? = null)

interface PreferencesStore {
    fun load(): Preferences
    fun save(preferences: Preferences)
}
