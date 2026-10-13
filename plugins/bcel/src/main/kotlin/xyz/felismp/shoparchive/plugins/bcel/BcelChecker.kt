package xyz.felismp.shoparchive.plugins.bcel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.felismp.shoparchive.api.SlipChecker
import xyz.felismp.shoparchive.shared.SlipCheckDto
import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.parseAmount
import java.io.IOException
import java.math.BigDecimal

/**
 * Asks BCEL about a OneProof slip: `GET onesure.php?fccref=..&ticket=..`, which answers with the transfer when it knows the slip. The answer is `known` when `result` is 0 and the
 * transfer's fields are all there; any other JSON object (another result, fields missing) says BCEL does not know the slip; no answer at all (no network, a timeout, a status other than 200,
 * text that is not JSON) is null. The payer's name and account (`sourcename`, `sourceaccount`) go to the answer, never to the log.
 */
class BcelChecker(
    private val settings: () -> BcelSettings,
    private val transport: HttpTransport,
    private val log: (String) -> Unit = {},
) : SlipChecker {
    override fun check(code: String): SlipCheckDto? {
        val slip = OneProof.parse(code) ?: return null
        val s = settings()
        val answer = try {
            transport.get(slip.url(), s.timeoutSeconds)
        } catch (e: IOException) {
            log("no answer from BCEL (${e.javaClass.simpleName})")
            return null
        }
        if (answer.status != 200) {
            log("no answer from BCEL (status ${answer.status})")
            return null
        }
        val reply = try {
            Json.parseToJsonElement(answer.body) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        if (reply == null) {
            log("no answer from BCEL (the reply is not a JSON object)")
            return null
        }
        return read(reply, s) ?: SlipCheckDto(SOURCE, known = false)
    }

    /** The transfer [reply] describes, or null when BCEL does not know the slip. */
    private fun read(reply: JsonObject, s: BcelSettings): SlipCheckDto? {
        if (reply.text("result")?.toIntOrNull() != 0) return null
        val currency = reply.text("ccy") ?: return null
        val amount = amount(reply.text("amount") ?: return null, currency) ?: return null
        val time = reply.text("txtime") ?: return null
        val to = reply.text("destinationname") ?: return null
        val account = reply.text("destinationaccount") ?: return null
        val toShop = if (s.accounts.isEmpty()) null else s.accounts.any { OneProof.matches(it, account) }
        return SlipCheckDto(
            SOURCE, known = true, amount = amount, currency = currency, time = time, to = to, toShop = toShop,
            toAccount = account, from = reply.text("sourcename"), fromAccount = reply.text("sourceaccount"),
        )
    }

    /** The text of the scalar [key] holds, trimmed; null if it is missing, null or empty. */
    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** [text] (a JSON number) in the currency's normal format: LAK has no decimals, the others two. Null if it is not a positive amount that fits that. */
    private fun amount(text: String, currency: String): String? {
        val exponent = if (currency == "LAK") 0 else 2
        if (text.length > 20) return null
        val number = try { BigDecimal(text).stripTrailingZeros() } catch (e: NumberFormatException) { return null }
        // Written out, 1E999999999 would be a billion digits: no amount has more than 18 before the point or after it.
        if (number.precision() - number.scale() > 18 || number.scale() > 18) return null
        val plain = number.toPlainString()
        return parseAmount(plain, exponent)?.let { formatAmount(it, exponent) }
    }

    private companion object {
        const val SOURCE = "BCEL"
    }
}
