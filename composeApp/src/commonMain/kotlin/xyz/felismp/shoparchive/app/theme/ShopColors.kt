package xyz.felismp.shoparchive.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Which palette the user picked. [System] resolves to Light or Dark at runtime and never to Oled. */
enum class ThemeMode { Light, Dark, Oled, System }

/**
 * Semantic color tokens, mapped from the design's CSS variables (`--color-*`, `--surface`, ...).
 * Text tokens are for text only; [iconSubtle] is decorative (icons, dividers) and must not carry text.
 */
@Immutable
class ShopColors(
    val background: Color,
    val surface: Color,
    /** `neutral-100`: table headers, hero blocks, inset areas. */
    val surfaceAlt: Color,
    /** `--sel`: selected row / selected segment. */
    val surfaceSelected: Color,
    val pill: Color,
    val sidebar: Color,
    val warnBg: Color,
    /** `neutral-300`. */
    val border: Color,
    /** `neutral-400`. */
    val borderStrong: Color,
    val text: Color,
    /** `neutral-700`. */
    val textSecondary: Color,
    /** `neutral-600`, never `neutral-500` (3.2:1 on light). Light is #6B6B6B, a step darker than the design's #737373, to hold 4.5:1 on selected / warn surfaces. */
    val textMuted: Color,
    /** `neutral-500`: decoration only (icons, dividers), 3.2:1 on light, so never text. */
    val iconSubtle: Color,
    val accent: Color,
    val onAccent: Color,
    /** Money in. Text contrast 4.5:1 on the main surfaces (3:1 on selected / warn). */
    val moneyIn: Color,
    val moneyOut: Color,
    val isDark: Boolean,
)

val LightColors = ShopColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFFAFAFA),
    surfaceSelected = Color(0xFFF5F5F5),
    pill = Color(0xFFFFFFFF),
    sidebar = Color(0xFFFAFAFA),
    warnBg = Color(0xFFFEF2F2),
    border = Color(0xFFE5E5E5),
    borderStrong = Color(0xFFD4D4D4),
    text = Color(0xFF0A0A0A),
    textSecondary = Color(0xFF525252),
    textMuted = Color(0xFF6B6B6B),
    iconSubtle = Color(0xFF8F8F8F),
    accent = Color(0xFF171717),
    onAccent = Color(0xFFFAFAFA),
    moneyIn = Color(0xFF047857),
    moneyOut = Color(0xFFDC2626),
    isDark = false,
)

val DarkColors = ShopColors(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF141414),
    surfaceAlt = Color(0xFF171717),
    surfaceSelected = Color(0xFF1F1F1F),
    pill = Color(0xFF2B2B2B),
    sidebar = Color(0xFF101010),
    warnBg = Color(0xFF2A1517),
    border = Color(0xFF2B2B2B),
    borderStrong = Color(0xFF404040),
    text = Color(0xFFFAFAFA),
    textSecondary = Color(0xFFD4D4D4),
    textMuted = Color(0xFFA1A1A1),
    iconSubtle = Color(0xFF8F8F8F),
    accent = Color(0xFFFAFAFA),
    onAccent = Color(0xFF0A0A0A),
    moneyIn = Color(0xFF34D399),
    moneyOut = Color(0xFFF87171),
    isDark = true,
)

/** Dark with true black surfaces (the design overrides only the differing variables). */
val OledColors = ShopColors(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceAlt = Color(0xFF0B0B0B),
    surfaceSelected = Color(0xFF141414),
    pill = Color(0xFF232323),
    sidebar = Color(0xFF000000),
    warnBg = Color(0xFF240F11),
    border = Color(0xFF232323),
    borderStrong = Color(0xFF3A3A3A),
    text = Color(0xFFFFFFFF),
    textSecondary = DarkColors.textSecondary,
    textMuted = DarkColors.textMuted,
    iconSubtle = DarkColors.iconSubtle,
    accent = DarkColors.accent,
    onAccent = Color(0xFF000000),
    moneyIn = DarkColors.moneyIn,
    moneyOut = DarkColors.moneyOut,
    isDark = true,
)
