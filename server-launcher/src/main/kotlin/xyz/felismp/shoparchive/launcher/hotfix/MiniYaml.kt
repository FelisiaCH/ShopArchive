package xyz.felismp.shoparchive.launcher.hotfix

/**
 * Reads the top-level keys of a plugin.yml / approved.yml and nothing else: the launcher has no YAML library (it
 * depends on the Kotlin stdlib and ASM only), and it needs just a few flat keys before the core exists.
 * A value is text, a list of text (`[a, b]` or `- a` lines) or null (key without a value); nested maps are skipped.
 * The core reads the same files with a real YAML parser: a test per file format keeps the two in agreement.
 */
internal fun parseTopLevel(text: String): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    var openKey: String? = null // a key with no value yet: `- item` lines below it make its list
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank() || line.trimStart().startsWith("#") || line == "---") continue
        val first = line[0]
        if (first == ' ' || first == '\t') {
            val key = openKey ?: continue
            val item = line.trimStart()
            if (item == "-" || item.startsWith("- ")) {
                @Suppress("UNCHECKED_CAST")
                val list = (result[key] as? MutableList<String>) ?: ArrayList<String>().also { result[key] = it }
                list.add(unquote(stripComment(item.removePrefix("-").trim())))
            }
            continue
        }
        if (first == '-') {
            val key = openKey ?: continue
            @Suppress("UNCHECKED_CAST")
            val list = (result[key] as? MutableList<String>) ?: ArrayList<String>().also { result[key] = it }
            list.add(unquote(stripComment(line.removePrefix("-").trim())))
            continue
        }
        val colon = line.indexOf(':')
        if (colon <= 0) { openKey = null; continue }
        val key = unquote(line.substring(0, colon).trim())
        val value = stripComment(line.substring(colon + 1).trim())
        when {
            value.isEmpty() -> { result[key] = null; openKey = key }
            value.startsWith("[") && value.endsWith("]") -> {
                result[key] = value.substring(1, value.length - 1).split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                openKey = null
            }
            else -> { result[key] = unquote(value); openKey = null }
        }
    }
    return result
}

/** A `#` after the value starts a comment, unless the value is quoted (then the closing quote ends it). */
private fun stripComment(value: String): String {
    if (value.isNotEmpty() && (value[0] == '"' || value[0] == '\'')) {
        val close = value.indexOf(value[0], 1)
        return if (close > 0) value.substring(0, close + 1) else value
    }
    val hash = value.indexOf(" #")
    return (if (hash >= 0) value.substring(0, hash) else value).trim()
}

private fun unquote(value: String): String {
    val v = value.trim()
    if (v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v[v.length - 1] == v[0]) return v.substring(1, v.length - 1)
    return v
}
