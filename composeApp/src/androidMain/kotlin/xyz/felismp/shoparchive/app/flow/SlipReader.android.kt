package xyz.felismp.shoparchive.app.flow

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun createSlipReader(): SlipReader = SlipReader { jpeg -> withContext(Dispatchers.Default) { readWithMlKit(jpeg) } }

private suspend fun readWithMlKit(jpeg: ByteArray): String? {
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        return recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult().text
    } finally {
        recognizer.close()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
