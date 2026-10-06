package xyz.felismp.shoparchive.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.felismp.shoparchive.app.flow.SaveResult

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

/** The system document picker (Storage Access Framework): no storage permission is needed, the person picks the place. */
@Composable
actual fun rememberFileSaver(onResult: (SaveResult) -> Unit): FileSaver {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<ByteArray>(1) }

    fun write(uri: Uri?) {
        val bytes = pending[0]
        pending[0] = null
        if (uri == null || bytes == null) {
            latest(SaveResult.CANCELLED)
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream") }.isSuccess
            }
            latest(if (ok) SaveResult.SAVED else SaveResult.FAILED)
        }
    }

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { write(it) }
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { write(it) }
    return FileSaver { fileName, mime, bytes ->
        pending[0] = bytes
        (if (mime == XLSX_MIME) xlsx else csv).launch(fileName)
    }
}
