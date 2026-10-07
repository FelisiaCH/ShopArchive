package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import kotlin.time.Duration

actual fun createServerDiscovery(context: PlatformContext): ServerDiscovery = JmdnsDiscovery()

/**
 * The IPv4 addresses of the interfaces that are up, not loopback and able to multicast: the ones an mDNS answer can come in on.
 * On a PC with Hyper-V, VPN or Docker adapters the single address `JmDNS.create()` picks may sit on the wrong network, so every one is browsed.
 */
internal fun multicastAddresses(): List<InetAddress> {
    val all = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
    return all.filter { nic -> try { nic.isUp && !nic.isLoopback && nic.supportsMulticast() } catch (_: Exception) { false } }
        .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress } }
        .distinct()
}

/**
 * Browses `_shoparchive._tcp` with JmDNS, the same library the server announces with, on every usable interface at once (one JmDNS
 * per address, all within the same [browse] timeout). Any failure on an interface just means nothing was found there.
 */
class JmdnsDiscovery : ServerDiscovery {
    override suspend fun browse(timeout: Duration): List<FoundServer> = withContext(Dispatchers.IO) {
        // Without any usable address, the library's own pick is still better than not looking.
        val targets: List<InetAddress?> = multicastAddresses().ifEmpty { listOf(null) }
        coroutineScope {
            targets.map { address -> async { browse(address, timeout) } }.awaitAll().flatten().distinct()
        }
    }

    private fun browse(address: InetAddress?, timeout: Duration): List<FoundServer> {
        var jmdns: JmDNS? = null
        return try {
            jmdns = if (address == null) JmDNS.create() else JmDNS.create(address)
            jmdns.list("$MDNS_SERVICE_TYPE.local.", timeout.inWholeMilliseconds.coerceAtLeast(1)).flatMap { info ->
                val id = info.getPropertyString(MDNS_SERVER_ID_KEY) ?: return@flatMap emptyList()
                val name = info.getPropertyString(MDNS_NAME_KEY) ?: info.name
                info.inet4Addresses.map { FoundServer(id, name, "${it.hostAddress}:${info.port}") }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        } finally {
            // Closing waits for the announcements to end, which takes seconds: not the caller's wait.
            jmdns?.let { j -> Thread { try { j.close() } catch (_: Exception) { } }.apply { isDaemon = true }.start() }
        }
    }
}
