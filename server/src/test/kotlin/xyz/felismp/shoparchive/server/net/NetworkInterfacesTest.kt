package xyz.felismp.shoparchive.server.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkInterfacesTest {
    @Test
    fun virtualAndVpnAdaptersAreRecognisedByNameOrDescription() {
        val virtual = listOf(
            "eth1" to "VMware Virtual Ethernet Adapter for VMnet8",
            "vEthernet (WSL)" to "Hyper-V Virtual Ethernet Adapter",
            "Ethernet 3" to "TAP-Windows Adapter V9",
            "tailscale0" to null,
            "ZeroTier One [8056c2e21c000001]" to "ZeroTier Virtual Port",
            "docker0" to null,
            "tun0" to null,
            "VirtualBox Host-Only Network" to "VirtualBox Host-Only Ethernet Adapter",
            "wg0" to "NordVPN Network TUN",
            "lo" to "Software Loopback Interface 1",
        )
        for ((name, description) in virtual) assertTrue(looksVirtual(name, description), "$name / $description")
    }

    @Test
    fun ordinaryEthernetAndWifiAreNotVirtual() {
        val real = listOf(
            "wlan0" to "Intel(R) Wi-Fi 6 AX201 160MHz",
            "eth0" to "Realtek PCIe GbE Family Controller",
            "en0" to null,
            "Wi-Fi" to "MediaTek Wi-Fi 6 MT7921 Wireless LAN Card",
        )
        for ((name, description) in real) assertFalse(looksVirtual(name, description), "$name / $description")
    }
}
