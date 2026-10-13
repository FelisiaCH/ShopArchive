package xyz.felismp.shoparchive.plugins.bcel

import java.net.URLEncoder

/** The two numbers that name a BCEL OneProof slip at BCEL: [ticket] and [fccref]. */
class OneProof(val ticket: String, val fccref: String) {
    /** The address that asks BCEL about this slip. */
    fun url(): String = "$ENDPOINT?fccref=${encode(fccref)}&ticket=${encode(ticket)}"

    companion object {
        const val ENDPOINT = "https://bcel.la:8083/onesure.php"

        /** The tags that hold the bank's own data in an EMV QR text. */
        private val BANK_TAGS = listOf("33", "38")

        private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

        /**
         * The slip [code] names, or null when it is not a BCEL OneProof slip (another bank's code, or text that is not an EMV QR). Reads the code as EMV TLV text:
         * a tag 33 or 38 whose own tags say `00` BCEL and `01` ONEPROOF, with `03` the ticket and `04` the fccref (spaces in them are removed). The CRC at the end is not checked.
         */
        fun parse(code: String): OneProof? {
            val top = tlv(code) ?: return null
            for ((id, value) in top) {
                if (id !in BANK_TAGS) continue
                val tags = tlv(value)?.toMap() ?: continue
                if (tags["00"] != "BCEL" || tags["01"] != "ONEPROOF") continue
                val ticket = tags["03"]?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: continue
                val fccref = tags["04"]?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: continue
                return OneProof(ticket, fccref)
            }
            return null
        }

        /** [text] as (tag, value) pairs: a 2-digit tag, a 2-digit length, then that many characters. Null if it does not end exactly where its last value does. */
        internal fun tlv(text: String): List<Pair<String, String>>? {
            val out = mutableListOf<Pair<String, String>>()
            var pos = 0
            while (pos < text.length) {
                if (pos + 4 > text.length) return null
                val id = text.substring(pos, pos + 2)
                val length = text.substring(pos + 2, pos + 4)
                if (!id.all { it in '0'..'9' } || !length.all { it in '0'..'9' }) return null
                val end = pos + 4 + length.toInt()
                if (end > text.length) return null
                out += id to text.substring(pos + 4, end)
                pos = end
            }
            return out
        }

        /**
         * Whether [shown], an account number as BCEL prints it (some digits hidden as `X`: `160-12-00-XXXXXX78-001`), can be the full [account]: same length, and
         * every character equal (ignoring case) or hidden by BCEL.
         */
        fun matches(account: String, shown: String): Boolean =
            account.length == shown.length && account.indices.all { shown[it] == 'X' || shown[it] == 'x' || shown[it].equals(account[it], ignoreCase = true) }
    }
}
