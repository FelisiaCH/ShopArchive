package xyz.felismp.shoparchive.server.net

import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** What a client reads from the announcement: enough to ask the server's own `/info`. mDNS is only a hint, never trusted. */
internal class MdnsAnnouncement(val serverId: String, val name: String, val version: String, val port: Int)

/**
 * Announces `_shoparchive._tcp` on every LAN interface (JmDNS, one instance per interface, so each network sees
 * the address that is reachable on it). Announcing takes seconds, so it runs on its own threads: a slow or
 * failing interface only costs a warning and never holds up startup or shutdown.
 */
internal class Mdns(
    private val interfaces: () -> List<LanInterface> = ::lanInterfaces,
    private val log: ConfigLog = ConsoleConfigLog,
) {
    private val running = CopyOnWriteArrayList<JmDNS>()

    @Volatile private var stopped = false

    fun start(announcement: MdnsAnnouncement) {
        for (lan in interfaces()) {
            // An IPv4 address when there is one: that is what a phone on the same Wi-Fi will use.
            val address = lan.addresses.firstOrNull { it is Inet4Address } ?: lan.addresses.first()
            Thread({ announce(lan.name, address, announcement) }, "mdns-${lan.name}").apply { isDaemon = true }.start()
        }
    }

    private fun announce(interfaceName: String, address: java.net.InetAddress, announcement: MdnsAnnouncement) {
        var jmdns: JmDNS? = null
        try {
            jmdns = JmDNS.create(address, "shoparchive")
            running += jmdns
            if (stopped) return stopQuietly(jmdns)
            jmdns.registerService(
                ServiceInfo.create(
                    "_shoparchive._tcp.local.", announcement.name, announcement.port, 0, 0,
                    mapOf("server-id" to announcement.serverId, "name" to announcement.name, "version" to announcement.version),
                )
            )
            log.info("mDNS: announcing on $interfaceName (${hostText(address)})")
        } catch (e: Exception) {
            log.warn("mDNS: cannot announce on $interfaceName (${hostText(address)}): ${e.message ?: e.javaClass.simpleName}")
            jmdns?.let { running -= it; stopQuietly(it) }
        }
    }

    /** Withdraws the announcements. Waits a few seconds at most; JmDNS closes slowly. */
    fun stop() {
        stopped = true
        val closers = running.map { jmdns -> Thread { stopQuietly(jmdns) }.apply { isDaemon = true; start() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        for (closer in closers) closer.join(maxOf(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
    }

    private fun stopQuietly(jmdns: JmDNS) {
        try {
            jmdns.close()
        } catch (e: Exception) {
            log.warn("mDNS: closing failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
