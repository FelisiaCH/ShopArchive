package xyz.felismp.shoparchive.app.client

import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.shared.PairPayload
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairLinkTest {
    private val fp = "AB".repeat(32)
    private val payload = PairPayload(1, "sid-1", fp, "secret", "alice", listOf("192.168.1.5:8443", "10.0.0.2:8443"))
    private val enc = Json { encodeDefaults = true }
    private fun json(p: PairPayload = payload) = enc.encodeToString(PairPayload.serializer(), p)
    // Same as the server's pairingLink: unpadded base64url.
    private fun link(text: String = json()) = "shoparchive://pair?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun reason(text: String) = assertFailsWith<ClientError.InvalidPairLink> { parsePairLink(text) }.reason

    @Test fun parsesServerLink() = assertEquals(payload, parsePairLink(link()))

    @Test fun parsesPaddedLink() {
        val padded = "shoparchive://pair?d=" + Base64.getUrlEncoder().encodeToString(json().toByteArray())
        assertEquals(payload, parsePairLink(padded))
    }

    @Test fun ignoresSurroundingWhitespace() = assertEquals(payload, parsePairLink("  \n" + link() + " \r\n"))

    @Test fun rejectsOtherSchemeAndText() {
        assertEquals(ClientError.InvalidPairLink.Reason.NOT_A_PAIR_LINK, reason("https://x/pair?d=abc"))
        assertEquals(ClientError.InvalidPairLink.Reason.NOT_A_PAIR_LINK, reason("shoparchive://other?d=abc"))
        assertEquals(ClientError.InvalidPairLink.Reason.NOT_A_PAIR_LINK, reason(""))
    }

    @Test fun rejectsBadBase64AndBadJson() {
        assertEquals(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD, reason("shoparchive://pair?d=***"))
        assertEquals(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD, reason(link("not json")))
        assertEquals(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD, reason(link("""{"v":1}""")))
    }

    @Test fun rejectsUnknownVersionBadPinAndNoEndpoints() {
        assertEquals(ClientError.InvalidPairLink.Reason.UNSUPPORTED_VERSION, reason(link(json(payload.copy(v = 2)))))
        assertEquals(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD, reason(link(json(payload.copy(fp = "abc")))))
        assertEquals(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD, reason(link(json(payload.copy(ep = emptyList())))))
    }

    @Test fun findsTheLinkInALineOfText() {
        val one = link()
        assertEquals(one, pairLinkIn("Link: $one"))
        assertEquals(one, pairLinkIn("$one (valid for 10 minutes)"))
        assertEquals(null, pairLinkIn("Manual code: BCDFG-HJKLM"))
        assertEquals(null, pairLinkIn("Link: shoparchive://pair?d="))
    }
}
