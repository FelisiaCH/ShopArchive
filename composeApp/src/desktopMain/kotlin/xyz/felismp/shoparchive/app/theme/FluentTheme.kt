package xyz.felismp.shoparchive.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Windows 11 layering without Mica: [backdrop] (behind the navigation pane) < [layer] (the content
 * area) < [card]. Light steps down the palette's surface tokens, dark steps up them.
 */
@Immutable
class FluentColors(
    val shop: ShopColors,
    val backdrop: Color,
    val layer: Color,
    val card: Color,
    val cardStroke: Color,
    val controlFill: Color,
    val controlStroke: Color,
    /** Off-state outline of the toggle switch: 3:1 against the layers ([ShopColors.iconSubtle]). */
    val toggleOffStroke: Color,
    /** Subtle fill overlays drawn over a control on hover / press. */
    val hoverOverlay: Color,
    val pressedOverlay: Color,
)

internal fun fluentColors(c: ShopColors): FluentColors = FluentColors(
    shop = c,
    backdrop = if (c.isDark) c.background else c.surfaceSelected,
    layer = c.surfaceAlt,
    card = if (c.isDark) c.surfaceSelected else c.surface,
    cardStroke = c.border,
    controlFill = if (c.isDark) c.pill else c.surface,
    controlStroke = c.borderStrong,
    toggleOffStroke = c.iconSubtle,
    hoverOverlay = c.text.copy(alpha = 0.06f),
    pressedOverlay = c.text.copy(alpha = 0.10f),
)

/** Windows 11 type ramp (Segoe UI Variable sizes) in the bundled Prompt. */
@Immutable
class FluentTypography(
    val caption: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val subtitle: TextStyle,
    val title: TextStyle,
)

internal fun fluentTypography(fonts: ShopFonts): FluentTypography {
    // Prompt has no `tnum` feature, so figures are proportional (amount columns will not align).
    val base = TextStyle(fontFamily = fonts.sans)
    return FluentTypography(
        caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
        body = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        bodyStrong = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        subtitle = base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        title = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    )
}

/** Windows 11 corner radii: 4 for controls, 8 for cards and overlays. */
object FluentShapes {
    val control = RoundedCornerShape(4.dp)
    val card = RoundedCornerShape(8.dp)
}

private val LocalFluentColors = staticCompositionLocalOf<FluentColors> { error("No FluentTheme provided") }
private val LocalFluentTypography = staticCompositionLocalOf<FluentTypography> { error("No FluentTheme provided") }
internal val LocalShopFonts = staticCompositionLocalOf<ShopFonts> { error("No FluentTheme provided") }

object FluentTheme {
    val colors: FluentColors @Composable @ReadOnlyComposable get() = LocalFluentColors.current
    val typography: FluentTypography @Composable @ReadOnlyComposable get() = LocalFluentTypography.current
}

/** The Windows skin: Fluent-style colors, type ramp and shapes over the shared [ShopTheme] tokens. */
@Composable
fun FluentTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    ProvideShopTokens(mode) { shop ->
        val fonts = rememberShopFonts()
        val colors = remember(shop) { fluentColors(shop) }
        val typography = remember(fonts) { fluentTypography(fonts) }
        CompositionLocalProvider(
            LocalFluentColors provides colors,
            LocalFluentTypography provides typography,
            LocalShopFonts provides fonts,
        ) {
            content()
        }
    }
}
