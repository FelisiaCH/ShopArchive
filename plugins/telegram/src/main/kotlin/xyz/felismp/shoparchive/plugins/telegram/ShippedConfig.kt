package xyz.felismp.shoparchive.plugins.telegram

/**
 * The `config.yml` this jar ships, read as text. A server upgraded with a `plugins/Telegram/config.yml` from an older jar (which the plugin never rewrites) still
 * gets the templates it lacks, and the new default of a template it never changed, from here; see [TelegramSettings.read].
 */
internal object ShippedConfig {
    /** The `config-version` the shipped file has: 2 added the `note.created` event and the revised entry template. */
    const val VERSION = 2

    /** The jar's config.yml, or null if it cannot be read. */
    val text: String? by lazy {
        TelegramSettings::class.java.getResourceAsStream("/config.yml")?.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }

    /** The template of [event] in [language] in [shipped], or null if there is none. Reads the block lines of `templates:` as the file is laid out. */
    fun template(shipped: String, event: String, language: String): String? {
        val lines = shipped.replace("\r\n", "\n").split('\n')
        val block = lines.indexOf("  ${templateKey(event)}:")
        if (block < 0) return null
        val inBlock = lines.drop(block + 1).takeWhile { it.startsWith("    ") }
        val start = inBlock.indexOf("    $language: |-")
        if (start < 0) return null
        return inBlock.drop(start + 1).takeWhile { it.startsWith("      ") }.joinToString("\n") { it.removePrefix("      ") }.takeIf { it.isNotBlank() }
    }

    /** What the entry template said in 0.1.1, per language. A file that still has exactly this text never changed it, so it takes the shipped one. */
    val OLD_ENTRY_TEMPLATES: Map<String, String> = mapOf(
        "lo" to "{type} · {branch}\n{amount} {currency}\n{category} {item}\n{user} · {time}\nລະຫັດ {code}",
        "th" to "{type} · {branch}\n{amount} {currency}\n{category} {item}\n{user} · {time}\nรหัส {code}",
        "en" to "{type} · {branch}\n{amount} {currency}\n{category} {item}\n{user} · {time}\nCode {code}",
    )
}
