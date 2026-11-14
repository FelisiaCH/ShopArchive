package xyz.felismp.shoparchive.web

/** A sentence of the app's strings: its [key], the values for `%1$s` / `%1$d` in [args], and an optional [detail] said after it (a reason, a failure). */
data class Message(val key: String, val args: List<String> = emptyList(), val detail: Message? = null)

private val PLACEHOLDER = Regex("""%(\d+)[$][sd]|%%""")

/** The words of one language (a key missing there reads in English, a key missing everywhere as itself, so a gap shows up instead of failing). */
class Translator(val language: String, private val tables: Map<String, Map<String, String>> = mapOf("en" to WebStrings.en, "th" to WebStrings.th, "lo" to WebStrings.lo)) {
    fun t(key: String, vararg args: String): String {
        val template = tables[language]?.get(key) ?: tables.getValue("en")[key] ?: return key
        return PLACEHOLDER.replace(template) { m -> if (m.value == "%%") "%" else args.getOrElse(m.groupValues[1].toInt() - 1) { "" } }
    }

    fun text(m: Message): String = t(m.key, *m.args.toTypedArray()) + (m.detail?.let { " " + text(it) } ?: "")

    /** [key] in the plural item [language] itself has for [count]: `one` only for 1 where that language has it, else `other` (Thai and Lao have only "other"). */
    fun plural(key: String, count: Int, vararg args: String): String =
        t(if (count == 1 && tables[language]?.containsKey("$key.one") == true) "$key.one" else "$key.other", *args)
}
