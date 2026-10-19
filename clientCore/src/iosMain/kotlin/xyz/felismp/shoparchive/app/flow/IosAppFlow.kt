package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UIKit.UIDevice
import xyz.felismp.shoparchive.app.client.ApiClient
import xyz.felismp.shoparchive.app.client.createCredentialStore
import xyz.felismp.shoparchive.app.client.createPreferencesStore
import xyz.felismp.shoparchive.app.client.createServerDiscovery
import xyz.felismp.shoparchive.app.client.probeFingerprint

/**
 * The flow of the iOS app, wired like ShopApp does on the other platforms; the Swift app calls this once and keeps the result for as long as it runs.
 * No updater: the app is updated from the Mac with xtool, not by itself. Amounts are written in the language chosen in the app.
 */
fun createIosAppFlow(): AppFlow {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val flow = AppFlow(
        scope,
        createCredentialStore(),
        ThisDevice(UIDevice.currentDevice.name, "ios"),
        connect = { pin, serverId, endpoints -> ApiClient(serverId, pin, endpoints) },
        probe = { address -> probeFingerprint(address) },
        prefs = createPreferencesStore(),
        compressor = createSlipCompressor(),
        reader = createIosSlipReader(),
        discovery = createServerDiscovery(),
    )
    setAmountLanguage(flow.language.value)
    scope.launch { flow.language.collect { setAmountLanguage(it) } }
    return flow
}
