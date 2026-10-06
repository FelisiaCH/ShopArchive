package xyz.felismp.shoparchive.app.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.time.Duration

actual fun createServerDiscovery(context: PlatformContext): ServerDiscovery = NsdDiscovery(context.applicationContext)

/**
 * Browses `_shoparchive._tcp` with Android's own network service discovery (no Play services). It returns as soon as one
 * server with the wanted id has been resolved, or when [find]'s time is up with whatever it has. Every Android failure
 * (no Wi-Fi, no permission, the service refusing to start) just means nothing was found.
 */
class NsdDiscovery(private val context: Context) : ServerDiscovery {
    override suspend fun find(serverId: String, timeout: Duration): List<String> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val found = CopyOnWriteArrayList<String>()
        val first = CompletableDeferred<Unit>()
        val resolver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CallbackResolver(nsd) else QueuedResolver(nsd)

        fun accept(info: NsdServiceInfo, address: String?) {
            val id = info.attributes[MDNS_SERVER_ID_KEY]?.decodeToString()
            if (id == serverId && address != null && info.port > 0) {
                found += "$address:${info.port}"
                first.complete(Unit)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { first.complete(Unit) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = resolver.resolve(serviceInfo, ::accept)
        }
        try {
            nsd.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            return emptyList()
        }
        try {
            withTimeoutOrNull(timeout) { first.await() }
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { } // already stopped or never started: nothing to undo
            resolver.stop()
        }
        return found.distinct()
    }
}

/** Turns one found service into its resolved details; [onResolved] gets the info and the IPv4 address (null when it has none). */
private interface Resolver {
    fun resolve(found: NsdServiceInfo, onResolved: (NsdServiceInfo, String?) -> Unit)
    fun stop()
}

/** Before Android 14 the only way is `resolveService`, which refuses a second resolve while one runs, so they go one at a time. */
@Suppress("DEPRECATION") // resolveService and `host` are deprecated since API 34 but are what minSdk 26 has
private class QueuedResolver(private val nsd: NsdManager) : Resolver {
    private val pending = ArrayDeque<Pair<NsdServiceInfo, (NsdServiceInfo, String?) -> Unit>>()
    private var running = false
    private var stopped = false

    @Synchronized
    override fun resolve(found: NsdServiceInfo, onResolved: (NsdServiceInfo, String?) -> Unit) {
        pending.addLast(found to onResolved)
        next()
    }

    @Synchronized
    override fun stop() {
        stopped = true
        pending.clear()
    }

    @Synchronized
    private fun next() {
        if (running || stopped) return
        val (info, onResolved) = pending.removeFirstOrNull() ?: return
        running = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    onResolved(serviceInfo, (serviceInfo.host as? Inet4Address)?.hostAddress)
                    done()
                }
            })
        } catch (_: Exception) {
            running = false
        }
    }

    @Synchronized
    private fun done() {
        running = false
        next()
    }
}

/** From Android 14 `resolveService` is deprecated; a service-info callback resolves any number at once and is dropped after its first answer. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class CallbackResolver(private val nsd: NsdManager) : Resolver {
    private val callbacks = CopyOnWriteArrayList<NsdManager.ServiceInfoCallback>()
    private val inline = Executor { it.run() }

    override fun resolve(found: NsdServiceInfo, onResolved: (NsdServiceInfo, String?) -> Unit) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                onResolved(serviceInfo, serviceInfo.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress)
                drop(this)
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        callbacks += callback
        try {
            nsd.registerServiceInfoCallback(found, inline, callback)
        } catch (_: Exception) {
            callbacks -= callback
        }
    }

    private fun drop(callback: NsdManager.ServiceInfoCallback) {
        if (callbacks.remove(callback)) try { nsd.unregisterServiceInfoCallback(callback) } catch (_: Exception) { }
    }

    override fun stop() = callbacks.toList().forEach(::drop)
}
