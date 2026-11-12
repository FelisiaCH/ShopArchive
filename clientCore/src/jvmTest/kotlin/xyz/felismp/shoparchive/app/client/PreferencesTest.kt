package xyz.felismp.shoparchive.app.client

import java.nio.file.Files
import xyz.felismp.shoparchive.shared.EntryType
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferencesTest {
    @Test fun roundTripAndMissingOrDamagedFileReadAsDefaults() {
        val file = Files.createTempDirectory("sa-prefs").resolve("sub/preferences.json").toFile()
        val store = FilePreferencesStore(file)
        assertEquals(Preferences(), store.load())
        store.save(Preferences("lo"))
        assertEquals(Preferences("lo"), store.load())
        file.writeText("{not json")
        assertEquals(Preferences(), store.load())
    }

    @Test fun recordStartAndLastTypeRoundTripAndAnOldFileReadsAsIncome() {
        val file = Files.createTempDirectory("sa-prefs").resolve("preferences.json").toFile()
        val store = FilePreferencesStore(file)
        store.save(Preferences("th", recordStart = RecordStart.LAST, lastType = EntryType.EXPENSE))
        assertEquals(Preferences("th", recordStart = RecordStart.LAST, lastType = EntryType.EXPENSE), store.load())
        file.writeText("""{"language":"lo"}""")
        assertEquals(Preferences("lo", recordStart = RecordStart.INCOME, lastType = null), store.load())
    }
}
