package xyz.felismp.shoparchive.spike.app

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter

/** Entry point for the Swift host (`MainViewControllerKt.MainViewController()`). */
fun MainViewController() = ComposeUIViewController { App() }

private fun readItem(): String = try {
    keychainRead() ?: "none"
} catch (e: Throwable) {
    "error: ${e.message}"
}

private fun writeItem(): String = try {
    keychainWrite(NSISO8601DateFormatter().stringFromDate(NSDate()))
    readItem()
} catch (e: Throwable) {
    "error: ${e.message}"
}

@Composable
actual fun PlatformExtras() {
    var item by remember { mutableStateOf(readItem()) }
    Text("iOS - Darwin engine, handleChallenge SPKI pin (Cancel unless the pin matches)")
    Text("Keychain item (AfterFirstUnlockThisDeviceOnly, no access group): $item")
    Button({ item = writeItem() }) { Text("Write timestamp to Keychain") }
    Button({ item = readItem() }) { Text("Read Keychain") }
}
