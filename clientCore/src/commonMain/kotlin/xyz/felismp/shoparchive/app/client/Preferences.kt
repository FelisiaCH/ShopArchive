package xyz.felismp.shoparchive.app.client

import kotlinx.serialization.Serializable
import xyz.felismp.shoparchive.shared.EntryType

/** Settings that are not secret. [language] is `en`, `lo` or `th`; null follows the system. [branch] is the branch key last worked in on this device. [recordStart] is the type the Record screen opens on; [lastType] is the type of the last entry saved here, which `LAST` opens on. */
@Serializable
data class Preferences(
    val language: String? = null,
    val branch: String? = null,
    val recordStart: RecordStart = RecordStart.INCOME,
    val lastType: EntryType? = null,
)

/** What the Record screen starts on: income, expense, or the type of the last saved entry. */
@Serializable
enum class RecordStart { INCOME, EXPENSE, LAST }

interface PreferencesStore {
    fun load(): Preferences
    fun save(preferences: Preferences)
}
