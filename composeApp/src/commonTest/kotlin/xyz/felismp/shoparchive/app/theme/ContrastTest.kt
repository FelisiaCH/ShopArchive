package xyz.felismp.shoparchive.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/** WCAG 2.x contrast of every text-on-background token pair the theme allows. */
class ContrastTest {
    private val palettes = mapOf("Light" to LightColors, "Dark" to DarkColors, "Oled" to OledColors)

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun ShopColors.surfaces() = mapOf(
        "background" to background, "surface" to surface, "surfaceAlt" to surfaceAlt,
        "surfaceSelected" to surfaceSelected, "pill" to pill, "sidebar" to sidebar, "warnBg" to warnBg,
    )

    private fun check(minimum: Double, failures: MutableList<String>, palette: String, fgName: String, fg: Color,
        bgName: String, bg: Color) {
        val ratio = contrast(fg, bg)
        if (ratio < minimum) failures += "$palette: $fgName on $bgName = ${(ratio * 100).toInt() / 100.0} (< $minimum)"
    }

    @Test
    fun bodyAndSecondaryTextHoldFourPointFiveOnEverySurface() {
        val failures = mutableListOf<String>()
        for ((name, c) in palettes) {
            val texts = mapOf("text" to c.text, "textSecondary" to c.textSecondary, "textMuted" to c.textMuted)
            for ((fgName, fg) in texts) for ((bgName, bg) in c.surfaces()) check(4.5, failures, name, fgName, fg, bgName, bg)
            check(4.5, failures, name, "onAccent", c.onAccent, "accent", c.accent)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** moneyOut is body-safe on the plain surfaces; on the tinted ones it is for bold amounts (3:1). */
    @Test
    fun moneyOutHoldsFourPointFiveOnPlainSurfacesAndThreeOnTintedOnes() {
        val failures = mutableListOf<String>()
        for ((name, c) in palettes) {
            for ((bgName, bg) in c.surfaces()) {
                val minimum = if (bgName == "surfaceSelected" || bgName == "warnBg") 3.0 else 4.5
                check(minimum, failures, name, "moneyOut", c.moneyOut, bgName, bg)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** moneyIn is body-safe on the plain surfaces; on the tinted ones it is for bold amounts (3:1). */
    @Test
    fun moneyInHoldsFourPointFiveOnPlainSurfacesAndThreeOnTintedOnes() {
        val failures = mutableListOf<String>()
        for ((name, c) in palettes) {
            for ((bgName, bg) in c.surfaces()) {
                val minimum = if (bgName == "surfaceSelected" || bgName == "warnBg") 3.0 else 4.5
                check(minimum, failures, name, "moneyIn", c.moneyIn, bgName, bg)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** iconSubtle is decoration, not text: 3:1 (WCAG non-text contrast) on the main surfaces. */
    @Test
    fun iconSubtleHoldsThreeOnMainSurfaces() {
        val failures = mutableListOf<String>()
        for ((name, c) in palettes) {
            check(3.0, failures, name, "iconSubtle", c.iconSubtle, "background", c.background)
            check(3.0, failures, name, "iconSubtle", c.iconSubtle, "surface", c.surface)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** Android maps `border` to the tonal container / selected segment, so text must hold 4.5:1 on it. */
    @Test
    fun textHoldsFourPointFiveOnTheTonalContainer() {
        val failures = mutableListOf<String>()
        for ((name, c) in palettes) {
            check(4.5, failures, name, "text", c.text, "border", c.border)
            check(4.5, failures, name, "textSecondary", c.textSecondary, "border", c.border)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** Android draws error text / icons in `moneyOut` and their content (`onError`) in `background`. */
    @Test
    fun backgroundOnMoneyOutHoldsFourPointFive() {
        val failures = mutableListOf<String>()
        for ((name, c) in palettes) check(4.5, failures, name, "background", c.background, "moneyOut", c.moneyOut)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
