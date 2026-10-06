package xyz.felismp.shoparchive.app.client

import java.net.Inet4Address
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MulticastAddressesTest {
    @Test fun onlyUsableIpv4AddressesAreBrowsed() {
        val found = multicastAddresses()
        assertTrue(found.all { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress })
        assertEquals(found.distinct(), found)
    }
}
