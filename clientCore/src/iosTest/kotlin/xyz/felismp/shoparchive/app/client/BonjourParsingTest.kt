package xyz.felismp.shoparchive.app.client

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BonjourParsingTest {
    /** A TXT record as the announcement puts it: each `key=value` behind its length. */
    private fun txt(vararg entries: String) = entries.fold(ByteArray(0)) { all, e -> all + e.length.toByte() + e.encodeToByteArray() }

    @Test fun txtGivesTheServerIdAndTheName() {
        val parsed = parseTxt(txt("server-id=abc-123", "name=Main shop"))
        assertEquals("abc-123", parsed[MDNS_SERVER_ID_KEY]?.decodeToString())
        assertEquals("Main shop", parsed[MDNS_NAME_KEY]?.decodeToString())
    }

    @Test fun txtValueMayBeEmptyOrHaveEqualSignsAndAnEntryWithoutAnEqualSignIsLeftOut() {
        val parsed = parseTxt(txt("empty=", "a=b=c", "flag"))
        assertContentEquals(ByteArray(0), parsed["empty"])
        assertEquals("b=c", parsed["a"]?.decodeToString())
        assertEquals(setOf("empty", "a"), parsed.keys)
    }

    @Test fun aTruncatedTxtRecordKeepsWhatCameBeforeTheBreak() {
        val whole = txt("server-id=abc", "name=Main shop")
        assertEquals(setOf("server-id"), parseTxt(whole.copyOf(whole.size - 3)).keys)
        assertEquals(emptyMap(), parseTxt(ByteArray(0)))
        assertEquals(emptyMap(), parseTxt(byteArrayOf(0)))
    }

    @Test fun ipv4IsReadFromASockaddrIn() {
        // length 16, AF_INET (2), port 8443 (0x20FB), 192.168.1.10, 8 bytes of padding
        val sockaddr = byteArrayOf(16, 2, 0x20, 0xFB.toByte(), 192.toByte(), 168.toByte(), 1, 10) + ByteArray(8)
        assertEquals("192.168.1.10", ipv4Of(sockaddr))
    }

    @Test fun otherAddressesAreNotIpv4() {
        val ipv6 = ByteArray(28).also { it[0] = 28; it[1] = 30 } // AF_INET6 on Apple platforms
        assertNull(ipv4Of(ipv6))
        assertNull(ipv4Of(ByteArray(0)))
    }
}
