package xyz.felismp.shoparchive.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalColors = staticCompositionLocalOf<ShopColors> { error("No shop theme provided") }
private val LocalSpacing = staticCompositionLocalOf { ShopSpacing() }

/** Tokens shared by every platform skin (`MaterialShopTheme` on Android, `FluentTheme` on Windows). */
object ShopTheme {
    val colors: ShopColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val spacing: ShopSpacing @Composable @ReadOnlyComposable get() = LocalSpacing.current
}

/** Resolves [mode] to a palette (System follows the OS) and provides it as [ShopTheme.colors]. */
@Composable
internal fun ProvideShopTokens(mode: ThemeMode, content: @Composable (ShopColors) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (mode) {
        ThemeMode.Light -> LightColors
        ThemeMode.Dark -> DarkColors
        ThemeMode.Oled -> OledColors
        ThemeMode.System -> if (systemDark) DarkColors else LightColors
    }
    CompositionLocalProvider(LocalColors provides colors) { content(colors) }
}
