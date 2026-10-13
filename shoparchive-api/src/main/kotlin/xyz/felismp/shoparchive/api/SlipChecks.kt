package xyz.felismp.shoparchive.api

import xyz.felismp.shoparchive.shared.SlipCheckDto

/**
 * Asks the bank that printed a slip whether it knows it. A bank plugin registers one with a priority above 0 ([ServiceRegistry] rules: the core registers none, and the highest one is used).
 * The core calls it when staff attach a slip photo, with the text of the slip's QR (the slip's code), on its own thread; it may block, but not for long, as a clerk is waiting for the answer.
 */
fun interface SlipChecker {
    /**
     * The bank's answer for [code], or null when this is not a slip the bank can be asked about, or the bank gave no answer (no network, a timeout, a reply nobody can read).
     * Null shows the clerk nothing; a [SlipCheckDto] with `known` false says the bank does not know the slip. An exception counts as null.
     * The answer never holds the payer's name or account.
     */
    fun check(code: String): SlipCheckDto?
}
