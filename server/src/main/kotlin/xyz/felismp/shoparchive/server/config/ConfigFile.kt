package xyz.felismp.shoparchive.server.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode

/** A file we cannot read, parse, or understand. Never recovered from by overwriting it: startup fails instead. */
internal class ConfigFileException(message: String) : Exception(message)

/**
 * A file the server owns. The file on disk is always the output of [render] - a template in code - so
 * comments come from the template and anything else in the file is not kept.
 */
internal abstract class ConfigFile<V> {
    /** Path below the server root, with `/` separators. */
    abstract val path: String

    protected abstract val title: String

    /** Reads [text]. Throws [ConfigFileException] if it cannot be understood at all; bad single values only [warn]. */
    abstract fun parse(text: String, warn: Warn): V

    /** The value of a file that does not exist yet. */
    abstract fun defaults(): V

    abstract fun render(value: V): String

    protected fun header(): String = listOf(
        "# $title",
        "# This file is rewritten from a template every time ShopArchive loads it: your own comments are not",
        "# kept and unknown keys are dropped. The previous copy is saved under data/migration/ first.",
    ).joinToString("\n", postfix = "\n")
}

/** `key=value` lines, UTF-8, `#` comments. java.util.Properties is not used: it mangles non-Latin text and treats a backslash as an escape. */
internal abstract class PropertiesConfigFile : ConfigFile<Values>() {
    abstract val keys: List<Key<*>>

    override fun parse(text: String, warn: Warn): Values = resolveKeys(keys, parseLines(text, warn), warn)

    override fun defaults(): Values = resolveKeys(keys, emptyMap()) {}

    override fun render(value: Values): String =
        header() + "\n" + keys.joinToString("\n\n", postfix = "\n") { key ->
            key.commentLines().joinToString("\n") { "# $it" } + "\n${key.path}=${key.renderValue(value)}"
        }

    private fun parseLines(text: String, warn: Warn): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val equals = line.indexOf('=')
            if (equals <= 0) {
                warn("line ${index + 1} is not 'key=value'; ignored")
                return@forEachIndexed
            }
            val key = line.substring(0, equals).trim()
            if (key in out) warn("duplicate key '$key' on line ${index + 1}; the last value wins")
            out[key] = line.substring(equals + 1).trim() // everything after the first '=', as is
        }
        return out
    }
}

/** One step of a migration chain: turns a file at version [from] into the shape of version [from] + 1. */
internal class MigrationStep(val from: Int, val transform: (Map<String, Any?>) -> Map<String, Any?>)

/**
 * A YAML file with a version number as its first key ([versionKey]: `config-version` for config files,
 * `file-version` for data files later). Older files are brought to [currentVersion] by running [steps] in
 * order on the raw map; a newer file is refused. Scalars reach [resolve] and the steps as strings, maps as
 * `Map<String, Any?>`, lists as `List<Any?>`, YAML null as null.
 */
internal abstract class YamlConfigFile<V>(
    private val versionKey: String,
    private val currentVersion: Int,
    private val steps: List<MigrationStep>,
) : ConfigFile<V>() {
    init {
        require(steps.map { it.from } == (1..steps.size).toList() && currentVersion == steps.size + 1) {
            "steps must lead from version 1 to $currentVersion without gaps"
        }
    }

    /** Resolves the content (the version key already taken out, any migration already run). */
    protected abstract fun resolve(content: Map<String, Any?>, warn: Warn): V

    /** The template for [value], below the version line. */
    protected abstract fun renderBody(value: V): String

    override fun parse(text: String, warn: Warn): V {
        val content = LinkedHashMap(parseYamlMap(path, text))
        // A file without the key is taken as the first version: it can only be hand-written, older than any migration.
        val version = if (versionKey in content) readVersion(content.remove(versionKey)) else 1
        if (version > currentVersion) {
            throw ConfigFileException(
                "$path has $versionKey $version, but this server only knows up to $currentVersion. " +
                    "The file is left untouched; use a newer ShopArchive or restore an older copy."
            )
        }
        var migrated: Map<String, Any?> = content
        for (step in steps) if (step.from >= version) migrated = step.transform(migrated)
        return resolve(migrated, warn)
    }

    override fun defaults(): V = resolve(emptyMap()) {}

    override fun render(value: V): String = header() + "$versionKey: $currentVersion\n\n" + renderBody(value)

    private fun readVersion(raw: Any?): Int =
        (raw as? String)?.trim()?.toIntOrNull()?.takeIf { it >= 1 }
            ?: throw ConfigFileException("$path: $versionKey must be a whole number from 1 (found '${raw ?: "(empty)"}')")
}

/** [text] as a map; blank text is an empty map. Anything that is not a YAML mapping is [ConfigFileException]. */
internal fun parseYamlMap(path: String, text: String): Map<String, Any?> {
    if (text.isBlank()) return emptyMap()
    val node = try {
        Yaml.default.parseToYamlNode(text)
    } catch (e: Exception) {
        throw ConfigFileException("$path cannot be parsed: ${e.message}")
    }
    @Suppress("UNCHECKED_CAST")
    return node.toRaw() as? Map<String, Any?>
        ?: throw ConfigFileException("$path cannot be parsed: the top level is not a mapping of keys")
}

private fun YamlNode.toRaw(): Any? = when (this) {
    is YamlScalar -> content
    is YamlNull -> null
    is YamlMap -> entries.entries.associateTo(LinkedHashMap()) { (key, value) -> key.content to value.toRaw() }
    is YamlList -> items.map { it.toRaw() }
    is YamlTaggedNode -> innerNode.toRaw()
}

/** `{a: {b: 1}}` -> `{"a.b": 1}`; lists and scalars are kept whole. */
internal fun flatten(map: Map<String, Any?>, prefix: String = ""): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((key, value) in map) {
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            out.putAll(flatten(value as Map<String, Any?>, "$prefix$key."))
        } else {
            out[prefix + key] = value
        }
    }
    return out
}

/** Template for [keys] as YAML: a path `a.b.name` becomes `name` under the lines `a:` and `b:`. The keys of one section must be listed together. */
internal fun renderYamlKeys(keys: List<Key<*>>, values: Values): String {
    val out = StringBuilder()
    var sections = emptyList<String>()
    for ((index, key) in keys.withIndex()) {
        val parts = key.path.split('.')
        val keySections = parts.dropLast(1)
        if (index > 0) out.append('\n')
        // Only the sections the previous key was not already in get a line of their own.
        val shared = sections.zip(keySections).takeWhile { (a, b) -> a == b }.size
        for (depth in shared until keySections.size) out.append("  ".repeat(depth)).append(keySections[depth]).append(":\n")
        sections = keySections
        val indent = "  ".repeat(keySections.size)
        key.commentLines().forEach { out.append(indent).append("# ").append(it).append('\n') }
        // Only safe plain scalars reach here (zone ids, language tags, numbers, word lists). A free-text key needs quoting first.
        out.append(indent).append(parts.last()).append(": ").append(key.renderValue(values)).append('\n')
    }
    return out.toString()
}
