package xyz.felismp.shoparchive.app.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Script of one run of text; [Other] covers everything Prompt draws: Latin, Thai, digits, baht, punctuation. */
internal enum class Script { Other, Lao }

internal class ScriptRun(val script: Script, val start: Int, val end: Int)

private fun scriptOf(c: Char): Script = when (c.code) {
    in 0x0E80..0x0EFF, 0x20AD -> Script.Lao
    else -> Script.Other
}

/**
 * Splits [text] into runs by script. The Lao block (U+0E80-0EFF) and the kip sign (U+20AD) are
 * [Script.Lao]: Prompt has neither. Everything else, Thai and the baht sign (U+0E3F) included, is
 * [Script.Other].
 */
internal fun scriptRuns(text: String): List<ScriptRun> {
    if (text.isEmpty()) return emptyList()
    val runs = mutableListOf<ScriptRun>()
    var start = 0
    for (i in 1..text.length) {
        if (i == text.length || scriptOf(text[i]) != scriptOf(text[start])) {
            runs += ScriptRun(scriptOf(text[start]), start, i)
            start = i
        }
    }
    return runs
}

/** [text] with the Lao / kip runs set in Noto Sans Lao; other runs keep the style's own family. */
internal fun shopAnnotatedString(text: String, fonts: ShopFonts): AnnotatedString = buildAnnotatedString {
    append(text)
    for (run in scriptRuns(text)) {
        if (run.script == Script.Lao) addStyle(SpanStyle(fontFamily = fonts.lao), run.start, run.end)
    }
}
