package xyz.felismp.shoparchive.spike.app

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Called from the :androidApp activity (a plain call, so that module needs no Compose compiler). */
fun ComponentActivity.setSpikeContent() = setContent { App() }

// Android 17 (API 37): runtime permission in the NEARBY_DEVICES group; targetSdk 37 apps are blocked from the LAN without it.
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
actual fun PlatformExtras() {
    val ctx = LocalContext.current
    fun permissionState() = when {
        Build.VERSION.SDK_INT < 37 -> "n/a (API ${Build.VERSION.SDK_INT} < 37, not enforced)"
        ctx.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED -> "GRANTED"
        else -> "DENIED"
    }

    var permission by remember { mutableStateOf(permissionState()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = permissionState() }
    var status by remember { mutableStateOf("idle") }
    var found by remember { mutableStateOf(listOf<String>()) }
    var stopDiscovery by remember { mutableStateOf<(() -> Unit)?>(null) }
    DisposableEffect(Unit) { onDispose { stopDiscovery?.invoke() } }

    Text("Android API ${Build.VERSION.SDK_INT}, targetSdk ${ctx.applicationInfo.targetSdkVersion} - OkHttp engine, X509TrustManager SPKI pin")
    Text("ACCESS_LOCAL_NETWORK: $permission")
    Button({ launcher.launch(LOCAL_NETWORK) }) { Text("Request ACCESS_LOCAL_NETWORK") }
    Button({
        stopDiscovery?.invoke()
        found = emptyList()
        stopDiscovery = discoverShopArchive(ctx, { status = it }) { found = found + it }
    }) { Text("NSD discover _shoparchive._tcp") }
    Text("NSD: $status")
    found.forEach { Text(it) }
}
