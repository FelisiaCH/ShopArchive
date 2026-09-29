package xyz.felismp.shoparchive.spike.app

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

@Composable
actual fun PlatformExtras() {
    Text("Desktop JVM ${System.getProperty("java.version")} - OkHttp engine, X509TrustManager SPKI pin")
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ShopArchive spike") { App() }
}
