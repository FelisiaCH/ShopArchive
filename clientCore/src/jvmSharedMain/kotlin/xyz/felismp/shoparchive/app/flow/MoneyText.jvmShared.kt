package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.text.DecimalFormatSymbols
import java.util.Locale

actual fun localeGroupingSeparator(): Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).groupingSeparator

actual fun localeDecimalSeparator(): Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator

actual fun systemLanguage(): String = Locale.getDefault().language.takeIf { it == "lo" || it == "th" } ?: "en"

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
