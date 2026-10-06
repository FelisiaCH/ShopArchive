package xyz.felismp.shoparchive.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.SaveResult
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.export_save
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

@Composable
actual fun rememberFileSaver(onResult: (SaveResult) -> Unit): FileSaver {
    val latest by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val title = stringResource(Res.string.export_save)
    return FileSaver { fileName, _, bytes ->
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply {
            file = fileName
            isVisible = true // modal: returns when the dialog is closed
        }
        val chosen = dialog.file
        if (chosen == null) {
            latest(SaveResult.CANCELLED)
        } else {
            val target = File(dialog.directory, chosen)
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { target.writeBytes(bytes) }.isSuccess }
                latest(if (ok) SaveResult.SAVED else SaveResult.FAILED)
            }
        }
    }
}
