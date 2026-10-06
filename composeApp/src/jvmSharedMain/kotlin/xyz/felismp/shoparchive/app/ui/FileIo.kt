package xyz.felismp.shoparchive.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import xyz.felismp.shoparchive.app.flow.SaveResult

const val CSV_MIME = "text/csv"
const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** A picture from encoded bytes (JPEG or PNG), or null when they are not one this device can read. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Writes a file the person names: [save] asks where (a save dialog on Windows, the system document picker on Android) and reports how it went. */
class FileSaver(val save: (fileName: String, mime: String, bytes: ByteArray) -> Unit)

/** [onResult] hears the outcome of each [FileSaver.save]: saved, the person backed out, or the write failed. */
@Composable
expect fun rememberFileSaver(onResult: (SaveResult) -> Unit): FileSaver
