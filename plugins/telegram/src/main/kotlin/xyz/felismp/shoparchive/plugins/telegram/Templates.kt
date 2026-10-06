package xyz.felismp.shoparchive.plugins.telegram

import xyz.felismp.shoparchive.api.Notification

/** Turns a template and a notification into the text of a message. Pure: no I/O, so it is tested without a server. */
interface TemplateRenderer {
    fun render(template: String, notification: Notification, language: String): String
}

/** The words `{type}` becomes, by language. */
private val TYPE_WORDS = mapOf(
    "lo" to mapOf("income" to "ລາຍຮັບ", "expense" to "ລາຍຈ່າຍ"),
    "th" to mapOf("income" to "รายรับ", "expense" to "รายจ่าย"),
    "en" to mapOf("income" to "Income", "expense" to "Expense"),
)

/**
 * `{name}` is replaced by the notification's field of that name; `{code}` is the notification's code, and `{type}` the word for income
 * or expense in [language]. An unknown name gives an empty text. The replacement is done in one pass, so a value that contains braces is never read as a placeholder.
 * A line that has words in braces, whose words are all empty, and whose other text is only punctuation is left out (an entry without a category).
 * Telegram's limit is 4096 characters; a longer text is cut with a mark.
 */
class PlaceholderRenderer : TemplateRenderer {
    override fun render(template: String, notification: Notification, language: String): String {
        val lines = template.replace("\r\n", "\n").split('\n').mapNotNull { line -> renderLine(line, notification, language) }
        val text = lines.joinToString("\n").trim()
        return if (text.length <= MAX_LENGTH) text else text.take(MAX_LENGTH - 1) + "…"
    }

    private fun renderLine(line: String, notification: Notification, language: String): String? {
        var placeholders = 0
        var filled = 0
        val rendered = PLACEHOLDER.replace(line) { match ->
            placeholders++
            value(match.groupValues[1], notification, language).also { if (it.isNotEmpty()) filled++ }
        }
        if (placeholders > 0 && filled == 0 && PLACEHOLDER.replace(line, "").none { it.isLetterOrDigit() }) return null
        return rendered.replace(Regex(" {2,}"), " ").trim()
    }

    private fun value(name: String, n: Notification, language: String): String = when (name) {
        "code" -> n.code
        "type" -> n.fields["type"]?.let { TYPE_WORDS[language]?.get(it) ?: it } ?: ""
        "time" -> n.fields["time"]?.let(::shortTime) ?: ""
        else -> n.fields[name] ?: ""
    }

    /** `2026-10-03T15:00:12+07:00` as `2026-10-03 15:00`; anything else is shown as it is. */
    private fun shortTime(time: String): String = if (ISO_TIME.containsMatchIn(time)) time.substring(0, 10) + " " + time.substring(11, 16) else time

    companion object {
        const val MAX_LENGTH = 4000
        private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9.]*)\\}")
        private val ISO_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
    }
}
