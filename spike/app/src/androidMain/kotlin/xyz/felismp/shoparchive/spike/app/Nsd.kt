package xyz.felismp.shoparchive.spike.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/**
 * Starts NSD discovery of `_shoparchive._tcp` and resolves every hit (one at a time: NsdManager rejects parallel
 * resolves). Callbacks arrive on binder threads, so everything is bounced to the main thread. Returns a stop function.
 */
@Suppress("DEPRECATION") // resolveService/host are deprecated since API 34 but still the only API that works on minSdk 26
fun discoverShopArchive(ctx: Context, onStatus: (String) -> Unit, onFound: (String) -> Unit): () -> Unit {
    val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    val main = Handler(Looper.getMainLooper())
    val pending = ArrayDeque<NsdServiceInfo>()
    var resolving = false

    fun resolveNext() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                main.post {
                    onStatus("resolve failed for ${info.serviceName}: error $errorCode")
                    resolving = false
                    resolveNext()
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val txt = info.attributes.entries.joinToString(", ") { "${it.key}=${it.value?.decodeToString()}" }
                main.post {
                    onFound("${info.serviceName} @ ${info.host?.hostAddress}:${info.port} [$txt]")
                    resolving = false
                    resolveNext()
                }
            }
        })
    }

    val listener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { onStatus("start failed: error $errorCode") } }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { onStatus("stop failed: error $errorCode") } }
        override fun onDiscoveryStarted(serviceType: String) { main.post { onStatus("discovering $serviceType (nothing listed yet = nothing found)") } }
        override fun onDiscoveryStopped(serviceType: String) { main.post { onStatus("stopped") } }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) { main.post { onStatus("lost ${serviceInfo.serviceName}") } }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            main.post {
                onStatus("found ${serviceInfo.serviceName}, resolving")
                pending.addLast(serviceInfo)
                resolveNext()
            }
        }
    }
    nsd.discoverServices("_shoparchive._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    return {
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: IllegalArgumentException) {
            // listener already stopped or never registered: nothing to undo
        }
    }
}
