package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private fun intlSeparator(type: String): String? =
    js("(function (t) { var p = new Intl.NumberFormat(navigator.language).formatToParts(1234567.5); for (var i = 0; i < p.length; i++) { if (p[i].type === t) return p[i].value; } return null; })")(type) as String?

private fun browserLanguage(): String = js("navigator.language") as String

actual fun localeGroupingSeparator(): Char = intlSeparator("group")?.firstOrNull() ?: ','

actual fun localeDecimalSeparator(): Char = intlSeparator("decimal")?.firstOrNull() ?: '.'

actual fun systemLanguage(): String = browserLanguage().take(2).lowercase().takeIf { it == "lo" || it == "th" } ?: "en"

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
