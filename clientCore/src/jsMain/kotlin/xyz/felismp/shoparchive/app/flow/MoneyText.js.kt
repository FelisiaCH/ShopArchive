package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Intl.NumberFormat can be called without `new`; written this way the code the Kotlin compiler emits cannot be read as `new (a.b)()`.
private fun intlSeparator(type: String): String? {
    val parts = js("Intl.NumberFormat(navigator.language).formatToParts(1234567.5)")
    val length = parts.length as Int
    for (i in 0 until length) {
        if (parts[i].type as String == type) return parts[i].value as String
    }
    return null
}

private fun browserLanguage(): String = js("navigator.language") as String

actual fun localeGroupingSeparator(): Char = intlSeparator("group")?.firstOrNull() ?: ','

actual fun localeDecimalSeparator(): Char = intlSeparator("decimal")?.firstOrNull() ?: '.'

actual fun systemLanguage(): String = browserLanguage().take(2).lowercase().takeIf { it == "lo" || it == "th" } ?: "en"

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
