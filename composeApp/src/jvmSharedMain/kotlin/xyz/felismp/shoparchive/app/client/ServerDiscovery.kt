package xyz.felismp.shoparchive.app.client

import kotlin.time.Duration

/**
 * Looks for a server on the local network by its mDNS announcement (`_shoparchive._tcp`, with the TXT `server-id`).
 * It is only a hint: what it returns is a list of `host:port` to try, and the caller still connects with the pinned
 * certificate and asks `/info` for the server id, so a wrong or forged answer can only cost a failed attempt.
 */
interface ServerDiscovery {
    /** The `host:port` of every server announcing [serverId] that answered within [timeout]; empty when none did or discovery is not possible here. */
    suspend fun find(serverId: String, timeout: Duration): List<String>
}

/** For tests and platforms without discovery: finds nothing. */
object NoDiscovery : ServerDiscovery {
    override suspend fun find(serverId: String, timeout: Duration): List<String> = emptyList()
}

/** The mDNS service type the server announces. */
internal const val MDNS_SERVICE_TYPE = "_shoparchive._tcp"

/** The TXT key that carries the server id. */
internal const val MDNS_SERVER_ID_KEY = "server-id"

/** The platform's discovery: Android's network service discovery, JmDNS on desktop. */
expect fun createServerDiscovery(context: PlatformContext): ServerDiscovery
