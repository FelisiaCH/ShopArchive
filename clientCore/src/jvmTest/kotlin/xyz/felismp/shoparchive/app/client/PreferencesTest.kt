package xyz.felismp.shoparchive.app.client

import java.nio.file.Files
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
}
