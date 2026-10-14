package xyz.felismp.shoparchive.app.client

import java.io.File

/** A small JSON file next to the credential file. A missing or damaged file reads as the defaults. */
class FilePreferencesStore(private val file: File) : PreferencesStore {
    override fun load(): Preferences = try {
        if (file.exists()) clientJson.decodeFromString(Preferences.serializer(), file.readText()) else Preferences()
    } catch (_: Exception) {
        Preferences()
    }

    override fun save(preferences: Preferences) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(clientJson.encodeToString(Preferences.serializer(), preferences))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
