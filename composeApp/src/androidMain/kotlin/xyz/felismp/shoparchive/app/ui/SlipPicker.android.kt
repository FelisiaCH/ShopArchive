package xyz.felismp.shoparchive.app.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberSlipPicker(onPicked: (name: String, bytes: ByteArray) -> Unit): SlipPicker {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onPicked)
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<File?>(null) }

    fun read(name: String, open: () -> java.io.InputStream?, after: () -> Unit = {}) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { open()?.use { it.readBytes() } }.getOrNull() }
            after()
            if (bytes != null) latest(name, bytes)
        }
    }

    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) read(uri.lastPathSegment ?: "slip.jpg", { context.contentResolver.openInputStream(uri) })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = photo
        photo = null
        if (saved && file != null) read(file.name, { file.inputStream() }, { file.delete() }) else file?.delete()
    }
    return SlipPicker(
        pickFromLibrary = { library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        takePhoto = {
            // The camera app writes to a file only this app's FileProvider shares; the file is deleted once read.
            val file = File(File(context.cacheDir, "slips").apply { mkdirs() }, "photo-${System.nanoTime()}.jpg")
            photo = file
            try {
                camera.launch(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file))
            } catch (_: ActivityNotFoundException) {
                photo = null // no camera app on this device
            }
        },
    )
}
