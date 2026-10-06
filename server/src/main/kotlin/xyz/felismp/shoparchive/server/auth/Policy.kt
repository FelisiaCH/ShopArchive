package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.Refusal
import java.text.Normalizer

/** The password every hash and every length rule is applied to: Unicode NFC, so the same text typed on two keyboards is one password. */
internal fun normalizePassword(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)

/**
 * What a new password or PIN must be to be stored at all, and who needs a password. Reads the live config, so `reload` changes them at once.
 * Strength is only a suggestion (the app warns, see `auth.password.min`), so only what the mechanism cannot work without is refused:
 * a [Refusal] (a reason key for the app and an English sentence for the log); null means acceptable.
 */
internal class Policy(private val config: ConfigService, private val users: UserStore) {
    /** An op, or a user with any node of `auth.password.required-for`. Everyone else signs in with the PIN alone. */
    fun passwordRequired(username: String): Boolean {
        val user = users.find(username) ?: return false
        return config.auth.passwordRequiredFor.any { node ->
            if (node == "op") user.op else users.hasPermission(username, node)
        }
    }

    fun checkPassword(raw: String?): Refusal? {
        val auth = config.auth
        if (raw == null || raw.isEmpty()) return Refusal(ErrorReasons.PASSWORD_REQUIRED, "A password is needed.")
        // Cheap test first: a huge text must not reach the normalizer.
        if (raw.length > auth.passwordMax * 2) return Refusal(ErrorReasons.PASSWORD_LONG, "The password is longer than ${auth.passwordMax} characters.")
        val password = normalizePassword(raw)
        if (password.codePointCount(0, password.length) > auth.passwordMax) return Refusal(ErrorReasons.PASSWORD_LONG, "The password is longer than ${auth.passwordMax} characters.")
        return null
    }

    fun checkPin(pin: String?): Refusal? {
        val length = config.auth.pinLength
        if (pin == null) return Refusal(ErrorReasons.PIN_REQUIRED, "A PIN is needed.")
        if (pin.length != length || !pin.all { it in '0'..'9' }) return Refusal(ErrorReasons.PIN_LENGTH, "The PIN must be exactly $length digits.")
        return null
    }

    /** Whether [given] is short enough to be worth hashing as a PIN or password. Anything longer cannot be right and must not cost a hash. */
    fun fitsToVerify(given: String, password: Boolean): Boolean =
        given.length <= if (password) config.auth.passwordMax * 2 else MAX_PIN_CHARS

    private companion object {
        /** `auth.pin.length` tops out at 12. */
        const val MAX_PIN_CHARS = 12
    }
}
