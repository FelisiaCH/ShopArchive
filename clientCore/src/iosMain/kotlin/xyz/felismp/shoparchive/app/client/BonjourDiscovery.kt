@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, UnsafeNumber::class)

package xyz.felismp.shoparchive.app.client

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import kotlin.time.Duration

/** The platform's discovery: Bonjour (`NSNetServiceBrowser`), no extra library. */
fun createServerDiscovery(): ServerDiscovery = BonjourDiscovery()

/**
 * Browses `_shoparchive._tcp` with the system's Bonjour browser. [find] returns as soon as one server with the wanted id has been
 * resolved, [browse] when its time is up; both with whatever they have by then. Every failure (no Wi-Fi, Local Network access refused)
 * just means nothing was found. The browser and the resolves run on the main run loop, so the search runs on the main thread.
 */
internal class BonjourDiscovery : ServerDiscovery {
    override suspend fun find(serverId: String, timeout: Duration): List<String> =
        search(timeout) { it.serverId == serverId }.filter { it.serverId == serverId }.map { it.endpoint }.distinct()

    override suspend fun browse(timeout: Duration): List<FoundServer> = search(timeout) { false }

    private suspend fun search(timeout: Duration, enough: (FoundServer) -> Boolean): List<FoundServer> = withContext(Dispatchers.Main) {
        val search = BonjourSearch(enough)
        search.start()
        try {
            withTimeoutOrNull(timeout) { search.finished.await() }
        } finally {
            search.stop()
        }
        search.found.distinct()
    }
}

private class BonjourSearch(private val enough: (FoundServer) -> Boolean) : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {
    val found = mutableListOf<FoundServer>()
    val finished = CompletableDeferred<Unit>()
    private val browser = NSNetServiceBrowser()
    private val services = mutableListOf<NSNetService>() // kept so they are not freed while resolving

    fun start() {
        browser.delegate = this
        browser.searchForServicesOfType("$MDNS_SERVICE_TYPE.", inDomain = "local.")
    }

    fun stop() {
        browser.stop()
        browser.delegate = null
        services.forEach { it.stop(); it.delegate = null }
        services.clear()
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        services += didFindService
        didFindService.delegate = this
        didFindService.resolveWithTimeout(5.0)
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        finished.complete(Unit)
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        val txt = sender.TXTRecordData()?.toByteArray()?.let(::parseTxt).orEmpty()
        val id = txt[MDNS_SERVER_ID_KEY]?.decodeToString() ?: return
        val name = txt[MDNS_NAME_KEY]?.decodeToString() ?: sender.name
        val port = sender.port.toInt()
        if (port <= 0) return
        sender.addresses.orEmpty().mapNotNull { (it as? NSData)?.toByteArray()?.let(::ipv4Of) }.forEach { address ->
            val server = FoundServer(id, name, "$address:$port")
            found += server
            if (enough(server)) finished.complete(Unit)
        }
    }
}

/** The TXT record's `key=value` strings (each behind a length byte) as a map; whatever does not parse is left out. */
internal fun parseTxt(bytes: ByteArray): Map<String, ByteArray> {
    val result = mutableMapOf<String, ByteArray>()
    var i = 0
    while (i < bytes.size) {
        val length = bytes[i].toInt() and 0xFF
        val end = i + 1 + length
        if (end > bytes.size) break
        val entry = bytes.copyOfRange(i + 1, end)
        val eq = entry.indexOf('='.code.toByte())
        if (eq > 0) result[entry.copyOfRange(0, eq).decodeToString()] = entry.copyOfRange(eq + 1, entry.size)
        i = end
    }
    return result
}

/** The dotted address in a BSD `sockaddr_in` (length, family 2, port, address), or null for any other kind of address (IPv6 is left out, as on the other platforms). */
internal fun ipv4Of(sockaddr: ByteArray): String? =
    if (sockaddr.size >= 8 && sockaddr[1].toInt() == 2) (4..7).joinToString(".") { (sockaddr[it].toInt() and 0xFF).toString() } else null
