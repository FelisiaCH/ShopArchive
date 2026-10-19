package xyz.felismp.shoparchive.app.flow

import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier

// The language chosen in the app (`en`, `lo`, `th`) decides the separators, as Locale.setDefault does on the other platforms; the device's own when none is chosen.
private var chosenLocale: NSLocale? = null

internal fun setAmountLanguage(tag: String?) {
    chosenLocale = tag?.let { NSLocale(localeIdentifier = it) }
}

private fun currentFormatter() = NSNumberFormatter().apply {
    locale = chosenLocale ?: NSLocale.currentLocale
    numberStyle = NSNumberFormatterDecimalStyle
}

actual fun localeGroupingSeparator(): Char = currentFormatter().groupingSeparator?.firstOrNull() ?: ','

actual fun localeDecimalSeparator(): Char = currentFormatter().decimalSeparator?.firstOrNull() ?: '.'

actual fun systemLanguage(): String = (chosenLocale ?: NSLocale.currentLocale).localeIdentifier.take(2).takeIf { it == "lo" || it == "th" } ?: "en"
