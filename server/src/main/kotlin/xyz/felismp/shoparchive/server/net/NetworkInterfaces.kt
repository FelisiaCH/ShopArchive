package xyz.felismp.shoparchive.server.net

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Locale

/** Words in an interface's name or description that mark a VPN or virtual adapter: not the LAN, so neither in the certificate nor announced by mDNS. */
private val VIRTUAL_WORDS = listOf("virtual", "vpn", "vmware", "vbox", "hyper-v", "wsl", "tailscale", "zerotier", "docker", "loopback", "tap", "tun")

internal fun looksVirtual(name: String?, description: String?): Boolean {
    val text = "${name.orEmpty()} ${description.orEmpty()}".lowercase(Locale.ROOT)
    return VIRTUAL_WORDS.any { it in text }
}

/** A real LAN interface: up, not loopback, not virtual-looking, with at least one address a client could use (no link-local `fe80::`, which needs a zone id). */
internal class LanInterface(val name: String, val addresses: List<InetAddress>)

internal fun lanInterfaces(): List<LanInterface> {
    val all = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (e: SocketException) {
        emptyList()
    }
    return all.mapNotNull { nic ->
        val usable = try {
            nic.isUp && !nic.isLoopback && !nic.isVirtual && !looksVirtual(nic.name, nic.displayName)
        } catch (e: SocketException) {
            false
        }
        val addresses = nic.inetAddresses.toList().filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
        if (usable && addresses.isNotEmpty()) LanInterface(nic.displayName ?: nic.name, addresses) else null
    }
}

/** [address] as text without the `%zone` Java appends to link-local IPv6 addresses. */
internal fun hostText(address: InetAddress): String = address.hostAddress.substringBefore('%')
