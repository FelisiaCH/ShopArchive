package xyz.felismp.shoparchive.launcher.hotfix

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plugins/approved.yml` (written by the core's `plugins approve`): plugin name -> SHA-256 of the jar approved.
 * A hotfix is applied only if its jar is listed here with exactly its hash, whatever `plugins.require-approval` says.
 * Nothing readable (missing file, I/O error) means nothing is approved.
 *
 * This must approve exactly what the core's reader (`ApprovedPlugins.read`, a real YAML parser) approves, never more: only a
 * direct scalar child of the top-level `approved:` key counts. A child with a nested map, a list or a flow collection as its value
 * approves nothing (and neither does anything inside it). What the core refuses as a whole file (a list or scalar under `approved:`,
 * a duplicate key, a tab or uneven indentation, a line that is not `key: value`) approves nothing here either, so the launcher is
 * never more permissive. Only `approved:` followed by a block (or by `{}`, no approvals) is read: any other value on that line,
 * a flow map with entries included, approves nothing (the core writes no such form, and a quoted scalar such as `"{A: x}"` must not look like one). The fixtures in `MiniYamlTest` hold the core's own answers.
 */
internal fun readApproved(file: Path): Map<String, String> {
    val text = try {
        if (!Files.isRegularFile(file)) return emptyMap()
        String(Files.readAllBytes(file), Charsets.UTF_8)
    } catch (e: IOException) {
        return emptyMap()
    }
    return parseApproved(text)
}

internal fun parseApproved(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    var seenApproved = false
    var inApproved = false
    var childIndent = -1
    var lastHadValue = true
    val children = HashSet<String>()
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        if (line.isBlank() || line.trimStart().startsWith("#") || line == "---") continue
        val indent = line.length - line.trimStart(' ').length
        if (line[indent] == '\t') return emptyMap()
        if (indent == 0) {
            inApproved = false
            if (line[0] == '-') continue
            val colon = line.indexOf(':')
            if (colon <= 0) return emptyMap()
            val entry = parseTopLevel(line).entries.singleOrNull() ?: return emptyMap()
            if (entry.key != "approved") continue
            if (seenApproved) return emptyMap()
            seenApproved = true
            // The core writes `approved:` followed by a block, or `approved: {}` when empty, and nothing else: so nothing else is read.
            val rest = line.substring(colon + 1).trim()
            when {
                rest.isEmpty() || rest.startsWith("#") -> { inApproved = true; childIndent = -1 }
                rest == "{}" || (rest.startsWith("{}") && rest.substring(2).trimStart().startsWith("#")) -> {}
                else -> return emptyMap()
            }
            continue
        }
        if (!inApproved) continue
        val item = line.substring(indent)
        if (childIndent < 0) childIndent = indent
        if (indent < childIndent) return emptyMap()
        if (indent > childIndent) {
            // inside the value of the entry above: fine if that value is a nested structure (it approves nothing), else not understood
            if (lastHadValue) return emptyMap()
            continue
        }
        if (item == "-" || item.startsWith("- ") || item.indexOf(':') <= 0) return emptyMap()
        val entry = parseTopLevel(item).entries.singleOrNull() ?: return emptyMap()
        if (!children.add(entry.key)) return emptyMap()
        val value = entry.value
        lastHadValue = value != null
        if (value is String) {
            val hash = value.trim().lowercase()
            if (hash.isNotEmpty() && hash[0] != '[' && hash[0] != '{' && hash[0] != '|' && hash[0] != '>') result[entry.key] = hash
            if (hash.isNotEmpty() && (hash[0] == '|' || hash[0] == '>')) lastHadValue = false
        }
    }
    return result
}
