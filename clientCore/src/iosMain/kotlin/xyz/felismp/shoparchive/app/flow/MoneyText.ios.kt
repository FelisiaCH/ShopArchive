package xyz.felismp.shoparchive.app.flow

import platform.Foundation.NSLocale
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale

private fun currentFormatter() = NSNumberFormatter().apply {
    locale = NSLocale.currentLocale
    numberStyle = NSNumberFormatterDecimalStyle
}

actual fun localeGroupingSeparator(): Char = currentFormatter().groupingSeparator?.firstOrNull() ?: ','

actual fun localeDecimalSeparator(): Char = currentFormatter().decimalSeparator?.firstOrNull() ?: '.'
