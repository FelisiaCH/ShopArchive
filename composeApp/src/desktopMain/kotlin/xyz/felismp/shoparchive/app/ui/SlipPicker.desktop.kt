package xyz.felismp.shoparchive.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.slip_file
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberSlipPicker(onPicked: (name: String, bytes: ByteArray) -> Unit): SlipPicker {
    val latest by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    val title = stringResource(Res.string.slip_file)
    return SlipPicker(
        pickFromLibrary = {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                isMultipleMode = false
                setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } }
                isVisible = true // modal: returns when the dialog is closed
            }
            val name = dialog.file
            if (name != null) {
                val file = File(dialog.directory, name)
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
                    if (bytes != null) latest(file.name, bytes)
                }
            }
        },
        takePhoto = null,
    )
}
