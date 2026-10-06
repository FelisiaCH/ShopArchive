package xyz.felismp.shoparchive.app.client

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.shared.PairPayload
import java.util.Base64

private const val PREFIX = "shoparchive://pair?d="
private val json = Json { ignoreUnknownKeys = true }

private val LINK_IN_TEXT = Regex(Regex.escape(PREFIX) + "[A-Za-z0-9_=-]+")

/** The pairing link a line of text holds (the server's reply to `user pair` has one), or null. It is not checked: [parsePairLink] does that. */
fun pairLinkIn(text: String): String? = LINK_IN_TEXT.find(text)?.value

/** Inverse of the server's `pairingLink`. Surrounding whitespace is ignored; padded and unpadded base64url both work. */
fun parsePairLink(text: String): PairPayload {
    val link = text.trim()
    if (!link.startsWith(PREFIX)) throw ClientError.InvalidPairLink(ClientError.InvalidPairLink.Reason.NOT_A_PAIR_LINK)
    val payload = try {
        val bytes = Base64.getUrlDecoder().decode(link.removePrefix(PREFIX).substringBefore('&'))
        json.decodeFromString(PairPayload.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        throw ClientError.InvalidPairLink(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD)
    } catch (_: SerializationException) {
        throw ClientError.InvalidPairLink(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD)
    }
    if (payload.v != 1) throw ClientError.InvalidPairLink(ClientError.InvalidPairLink.Reason.UNSUPPORTED_VERSION)
    if (decodePin(payload.fp) == null || payload.ep.isEmpty() || payload.sid.isBlank() || payload.sec.isBlank()) {
        throw ClientError.InvalidPairLink(ClientError.InvalidPairLink.Reason.BAD_PAYLOAD)
    }
    return payload
}
