package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import xyz.felismp.shoparchive.app.AppBuild
import xyz.felismp.shoparchive.app.client.ApiClient
import xyz.felismp.shoparchive.app.client.PlatformContext
import xyz.felismp.shoparchive.app.client.createCredentialStore
import xyz.felismp.shoparchive.app.client.createPreferencesStore
import xyz.felismp.shoparchive.app.client.createServerDiscovery
import xyz.felismp.shoparchive.app.client.probeFingerprint
import xyz.felismp.shoparchive.app.flow.AppFlow
import xyz.felismp.shoparchive.app.flow.createAppUpdater
import xyz.felismp.shoparchive.app.flow.createSlipCompressor

/** The whole app for one window / activity. */
@Composable
fun ShopApp(context: PlatformContext) {
    ShopSkin {
        LocalNetworkGate {
            val scope = rememberCoroutineScope()
            val flow = remember {
                AppFlow(
                    scope, createCredentialStore(context), thisDevice(context),
                    connect = { pin, serverId, endpoints -> ApiClient(serverId, pin, endpoints) },
                    probe = { address -> probeFingerprint(address) },
                    prefs = createPreferencesStore(context),
                    compressor = createSlipCompressor(),
                    discovery = createServerDiscovery(context),
                    updater = createAppUpdater(context),
                    ownVersion = AppBuild.version,
                )
            }
            OnAppResume(flow::appResumed)
            AppLanguage(flow.language.collectAsState().value) {
                // Any touch, click or key press is "the user is here" for the auto-lock timer (the Initial pass sees it before the controls do).
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(flow) {
                            awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); flow.userActive() } }
                        }
                        .onPreviewKeyEvent { flow.userActive(); false },
                ) { AppHost(flow) }
            }
        }
    }
}
