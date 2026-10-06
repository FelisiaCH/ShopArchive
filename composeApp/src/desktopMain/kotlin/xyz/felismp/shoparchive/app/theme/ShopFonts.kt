package xyz.felismp.shoparchive.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.noto_sans_lao_bold
import xyz.felismp.shoparchive.app.resources.noto_sans_lao_medium
import xyz.felismp.shoparchive.app.resources.noto_sans_lao_regular
import xyz.felismp.shoparchive.app.resources.noto_sans_lao_semibold
import xyz.felismp.shoparchive.app.resources.prompt_bold
import xyz.felismp.shoparchive.app.resources.prompt_medium
import xyz.felismp.shoparchive.app.resources.prompt_regular
import xyz.felismp.shoparchive.app.resources.prompt_semibold

/**
 * The bundled Windows font families. Prompt covers Latin, Thai, digits and the baht sign; it has no
 * Lao and no kip sign, and a [FontFamily] is matched by weight, not per glyph, so Lao and kip runs get
 * [lao] through [shopAnnotatedString] (per-script spans) instead of relying on platform fallback.
 */
@Immutable
class ShopFonts(
    val sans: FontFamily,
    val lao: FontFamily,
)

@Composable
internal fun rememberShopFonts(): ShopFonts {
    val sans = FontFamily(
        Font(Res.font.prompt_regular, FontWeight.Normal),
        Font(Res.font.prompt_medium, FontWeight.Medium),
        Font(Res.font.prompt_semibold, FontWeight.SemiBold),
        Font(Res.font.prompt_bold, FontWeight.Bold),
    )
    val lao = FontFamily(
        Font(Res.font.noto_sans_lao_regular, FontWeight.Normal),
        Font(Res.font.noto_sans_lao_medium, FontWeight.Medium),
        Font(Res.font.noto_sans_lao_semibold, FontWeight.SemiBold),
        Font(Res.font.noto_sans_lao_bold, FontWeight.Bold),
    )
    return remember(sans, lao) { ShopFonts(sans, lao) }
}
