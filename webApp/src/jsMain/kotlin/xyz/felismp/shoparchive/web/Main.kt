package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import org.jetbrains.compose.web.renderComposable
import xyz.felismp.shoparchive.app.flow.AppFlow
import xyz.felismp.shoparchive.app.flow.AppState

fun main() {
    val storage = LocalStorageKeyValue()
    val flow = AppFlow(
        MainScope(), WebCredentialStore(storage), webDevice,
        connect = ::webConnect, probe = ::webProbe, prefs = WebPreferencesStore(storage),
        compressor = webSlipCompressor, reader = webSlipReader, outbox = WebOutboxStore(storage),
    )
    // Any click, key press or touch is "the user is here" for the auto-lock timer; coming back to the tab counts time away as idle.
    for (event in listOf("click", "keydown", "touchstart")) document.addEventListener(event, { flow.userActive() })
    document.addEventListener("visibilitychange", { if (document.asDynamic().visibilityState == "visible") flow.appResumed() })
    renderComposable(rootElementId = "root") { App(flow) }
}

@Composable
fun App(flow: AppFlow) {
    val language by flow.language.collectAsState()
    val t = remember(language) { Translator(chooseLanguage(language, window.navigator.language)) }
    LaunchedEffect(t.language) { document.documentElement?.setAttribute("lang", t.language) }
    val autoAdd = remember { AutoAdd(webAddress, flow::addServer) }
    val prompt by flow.reauth.collectAsState()
    val state by flow.state.collectAsState()
    LaunchedEffect(state) { autoAdd.onState(state) }
    val reauth = prompt
    if (reauth != null) {
        ReauthScreen(t, reauth, flow)
        return
    }
    when (val s = state) {
        AppState.Starting -> Screen(t.t("app_name")) { Banner(t.t("starting")) }
        is AppState.Servers -> ServersScreen(t, s, autoAdd.fired) { flow.addServer(webAddress) }
        is AppState.Login -> LoginScreen(t, s, flow)
        is AppState.Locked -> LockedScreen(t, s, flow)
        is AppState.Unlocked -> flow.workspace?.let { Shell(t, s, flow, it) }
        is AppState.Settings -> Unit // never opened: the page has no Settings yet
        is AppState.CertChanged -> CertChangedScreen(t, s, flow)
        is AppState.ProtocolMismatch -> ProtocolMismatchScreen(t, s)
    }
}
