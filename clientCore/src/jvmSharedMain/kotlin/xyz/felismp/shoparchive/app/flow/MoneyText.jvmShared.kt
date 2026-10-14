package xyz.felismp.shoparchive.app.flow

import java.text.DecimalFormatSymbols
import java.util.Locale

actual fun localeGroupingSeparator(): Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).groupingSeparator

actual fun localeDecimalSeparator(): Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
