package xyz.felismp.shoparchive.app.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import org.jetbrains.compose.resources.Font
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.google_sans

/**
 * The Android skin: Material 3 built from the shared [ShopTheme] tokens (no dynamic color), in the
 * bundled Google Sans. Money colors stay reachable as [ShopTheme.colors].
 */
@Composable
fun MaterialShopTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    ProvideShopTokens(mode) { shop ->
        val family = rememberGoogleSans()
        val scheme = remember(shop) { materialColorScheme(shop) }
        val typography = remember(family) { materialTypography(family) }
        SystemBarIcons(light = !shop.isDark)
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** Status / navigation bar icons follow the chosen palette, not the OS theme (the user can pick Dark on a light OS). */
@Composable
private fun SystemBarIcons(light: Boolean) {
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

/**
 * Tokens to Material roles. Filled tonal / selected segment = [ShopColors.border], outline =
 * [ShopColors.iconSubtle] (3:1 for control edges), dividers = [ShopColors.border]. OLED needs no
 * special case: its palette already has pure black background and surface.
 */
internal fun materialColorScheme(c: ShopColors): ColorScheme =
    (if (c.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.border, onPrimaryContainer = c.text,
        secondary = c.textSecondary, onSecondary = c.background, secondaryContainer = c.border, onSecondaryContainer = c.text,
        tertiary = c.textSecondary, onTertiary = c.background, tertiaryContainer = c.border, onTertiaryContainer = c.text,
        background = c.background, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceSelected, onSurfaceVariant = c.textSecondary,
        surfaceTint = c.accent,
        inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = c.onAccent,
        error = c.moneyOut, onError = c.background, errorContainer = c.warnBg, onErrorContainer = c.text,
        outline = c.iconSubtle, outlineVariant = c.border, scrim = Color.Black,
        surfaceBright = if (c.isDark) c.surfaceSelected else c.surface,
        surfaceDim = if (c.isDark) c.background else c.surfaceSelected,
        surfaceContainerLowest = c.background, surfaceContainerLow = c.surface, surfaceContainer = c.surfaceAlt,
        surfaceContainerHigh = c.surfaceSelected, surfaceContainerHighest = c.border,
    )

/** One font file with a variable `wght` axis, exposed at the four weights the theme uses. */
@Composable
private fun rememberGoogleSans(): FontFamily {
    val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
    val fonts = weights.map { w -> Font(Res.font.google_sans, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))) }
    return remember(fonts) { FontFamily(fonts) }
}

internal fun materialTypography(family: FontFamily): Typography {
    // Tabular figures on every style, as the design does at its root.
    fun TextStyle.shop() = copy(fontFamily = family, fontFeatureSettings = "tnum")
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.shop(), displayMedium = d.displayMedium.shop(), displaySmall = d.displaySmall.shop(),
        headlineLarge = d.headlineLarge.shop(), headlineMedium = d.headlineMedium.shop(), headlineSmall = d.headlineSmall.shop(),
        titleLarge = d.titleLarge.shop(), titleMedium = d.titleMedium.shop(), titleSmall = d.titleSmall.shop(),
        bodyLarge = d.bodyLarge.shop(), bodyMedium = d.bodyMedium.shop(), bodySmall = d.bodySmall.shop(),
        labelLarge = d.labelLarge.shop(), labelMedium = d.labelMedium.shop(), labelSmall = d.labelSmall.shop(),
    )
}
