package xyz.felismp.shoparchive.spike.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Platform-specific test controls (Android: local network + NSD, iOS: Keychain, desktop: label only). */
@Composable
expect fun PlatformExtras()

private suspend fun show(set: (String) -> Unit, block: suspend () -> String) {
    set("...")
    set(
        try {
            "✅ ${block()}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "❌ ${e::class.simpleName}: ${e.message}"
        },
    )
}

@Composable
fun App() {
    MaterialTheme {
        var url by rememberSaveable { mutableStateOf("https://192.168.1.10:8443") }
        var pin by rememberSaveable { mutableStateOf("") }
        var ping by remember { mutableStateOf("") }
        var ws by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ShopArchive spike", style = MaterialTheme.typography.h6)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
            OutlinedTextField(pin, { pin = it }, Modifier.fillMaxWidth(), label = { Text("Pin (base64 SPKI SHA-256)") }, singleLine = true)
            Button({ scope.launch { show({ ping = it }) { httpsPing(url, pin) } } }) { Text("HTTPS /ping") }
            Text(ping)
            Button({ scope.launch { show({ ws = it }) { wsEcho(url, pin) } } }) { Text("WS echo") }
            Text(ws)
            PlatformExtras()
        }
    }
}
