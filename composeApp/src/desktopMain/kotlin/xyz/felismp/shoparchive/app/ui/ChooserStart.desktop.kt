package xyz.felismp.shoparchive.app.ui

import java.io.File

// Start in the user's folders, not the app's working directory (C:\Program Files\ShopArchive on Windows).
internal fun chooserStart(home: File, vararg preferred: String): String =
    (preferred.map { File(home, it) }.firstOrNull { it.isDirectory } ?: home).path
