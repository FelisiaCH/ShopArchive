package xyz.felismp.shoparchive.app.ui

import androidx.compose.runtime.Composable

/** Ways to get a slip photo: [pickFromLibrary] always; [takePhoto] only where there is a camera to ask (Android). */
class SlipPicker(val pickFromLibrary: () -> Unit, val takePhoto: (() -> Unit)?)

/**
 * Android: the system photo picker (no Play services) and the camera app. Windows: a file chooser.
 * [onPicked] gets the file's name and its bytes, read off the main thread.
 */
@Composable
expect fun rememberSlipPicker(onPicked: (name: String, bytes: ByteArray) -> Unit): SlipPicker
