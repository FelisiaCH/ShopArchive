package xyz.felismp.shoparchive.app.flow

import java.text.Normalizer
import java.util.Locale

/** What looks weak about a new password, with the numbers and names the setup form says. Only a hint: the server accepts any password, so none of these blocks the submit. */
internal sealed interface PasswordHint {
    data class Short(val length: Int, val suggestedMin: Int) : PasswordHint
    data object Common : PasswordHint
    data class HasUserName(val name: String) : PasswordHint
    data class HasServerName(val name: String) : PasswordHint
}

/** What looks weak about a new PIN. Only a hint, like [PasswordHint]. */
internal sealed interface PinHint {
    data class Repeated(val digit: Char) : PinHint
    data class Run(val first: Char, val last: Char) : PinHint
}

/** Passwords everyone tries first. Compared in lower case, after NFC. */
private val COMMON_PASSWORDS = setOf(
    "password", "password1", "password12", "password123", "password1234", "passw0rd", "p@ssw0rd", "p@ssword",
    "12345678", "123456789", "1234567890", "123456789012345", "12345678901234567890", "123123123", "11111111", "00000000", "87654321", "987654321",
    "qwertyui", "qwertyuiop", "qwerty123", "qwerty12345", "qwertyuiopasdfg", "asdfghjk", "asdfghjkl", "zxcvbnm1", "1q2w3e4r", "1q2w3e4r5t", "1qaz2wsx",
    "iloveyou", "iloveyou1", "letmein1", "letmein123", "welcome1", "welcome123", "admin123", "administrator", "adminadmin", "changeme", "changeme123",
    "abc12345", "abcd1234", "abcdefgh", "abcdefghijklmno", "trustno1", "football1", "baseball1", "monkey123", "dragon123", "sunshine1",
    "master123", "superman1", "princess1", "shoparchive", "shoparchive123", "shopadmin", "shop12345",
)

/**
 * What looks weak about [password] for the account [username] on the server called [serverName]; empty when nothing does or nothing is typed yet.
 * [suggestedMin] is the server's suggested shortest length in characters (0: no suggestion, as from an older server, which enforces its own rules).
 */
internal fun passwordHints(password: String, username: String, serverName: String, suggestedMin: Int): List<PasswordHint> {
    if (password.isEmpty()) return emptyList()
    val text = Normalizer.normalize(password, Normalizer.Form.NFC)
    val lower = text.lowercase(Locale.ROOT)
    val length = text.codePointCount(0, text.length)
    val name = username.trim()
    val server = serverName.trim()
    return buildList {
        if (length < suggestedMin) add(PasswordHint.Short(length, suggestedMin))
        if (lower in COMMON_PASSWORDS) add(PasswordHint.Common)
        if (name.isNotEmpty() && lower.contains(name.lowercase(Locale.ROOT))) add(PasswordHint.HasUserName(name))
        if (server.isNotEmpty() && lower.contains(server.lowercase(Locale.ROOT))) add(PasswordHint.HasServerName(server))
    }
}

/** What looks weak about [pin] once all [length] digits are typed; empty before that, so a half-typed PIN does not look like a run. */
internal fun pinHints(pin: String, length: Int): List<PinHint> {
    if (pin.length != length || !pin.all { it in '0'..'9' }) return emptyList()
    val steps = pin.zipWithNext { a, b -> b - a }
    return buildList {
        if (pin.all { it == pin[0] }) add(PinHint.Repeated(pin[0]))
        if (steps.isNotEmpty() && (steps.all { it == 1 } || steps.all { it == -1 })) add(PinHint.Run(pin.first(), pin.last()))
    }
}
