package xyz.felismp.shoparchive.server.config

import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import xyz.felismp.shoparchive.server.users.quoted
import java.util.Locale

internal typealias Warn = (String) -> Unit

/**
 * One config key, declared once: its path, type, default, allowed values and the comment the template
 * prints above it. Reading, validating (clamp / default + warn) and rendering all come from this one
 * declaration, so a key cannot be readable but missing from the template.
 */
internal abstract class Key<T : Any>(val path: String, val default: T, val comment: String) {
    /** What the template and the warnings say is allowed, e.g. "integer from 1 to 65535". */
    abstract val allowed: String

    /** The value [raw] stands for, or null if it is not a value of this type at all. Range is not checked here. */
    abstract fun parse(raw: String): T?

    /** The nearest allowed value. */
    open fun clamp(value: T): T = value

    open fun render(value: T): String = value.toString()

    /** The value a parsed file entry stands for: a scalar is [parse]d; a key that takes a list overrides this. */
    protected open fun fromRaw(raw: Any?): T? = (raw as? String)?.let { parse(it.trim()) }

    /** [raw] as a value that needs no clamping, else null - for command-line overrides, which are refused instead of fixed. */
    fun parseExact(raw: String): T? = parse(raw)?.takeIf { clamp(it) == it }

    /** The value for this key in [flat] (dotted path -> raw value): missing -> default; out of range -> clamped + warning; invalid -> default + warning. */
    fun resolve(flat: Map<String, Any?>, warn: Warn): T {
        if (path !in flat) return default
        val raw = flat[path]
        val parsed = fromRaw(raw)
        if (parsed == null) {
            warn("$path: invalid value '${raw ?: "(empty)"}' (expected $allowed); using default '${shown(default)}'")
            return default
        }
        val used = clamp(parsed)
        if (used != parsed) warn("$path: value '$raw' is outside the allowed range ($allowed); using '${shown(used)}'")
        return used
    }

    fun renderValue(values: Values): String = render(values[this])

    /** The template lines for this key, without the comment marker. */
    fun commentLines(): List<String> =
        comment.lines() + "Allowed: $allowed. Default: ${shown(default)}"

    private fun shown(value: T) = render(value).ifEmpty { "empty" }
}

internal class StringKey(path: String, default: String, comment: String, private val allowBlank: Boolean = false) :
    Key<String>(path, default, comment) {
    override val allowed get() = if (allowBlank) "any text" else "any non-blank text"
    override fun parse(raw: String) = raw.takeIf { allowBlank || it.isNotBlank() }
}

internal class IntKey(path: String, default: Int, comment: String, val min: Int, val max: Int) :
    Key<Int>(path, default, comment) {
    override val allowed get() = "integer from $min to $max"

    // Saturates instead of failing on a number too big for Int, so clamp() still finds the nearest bound.
    override fun parse(raw: String) =
        raw.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    override fun clamp(value: Int) = value.coerceIn(min, max)
}

/** Host names (e.g. the domains a certificate must cover), written as a YAML list. Lower-cased; an entry that is not a host name makes the whole value invalid. */
internal class HostListKey(path: String, comment: String) : Key<List<String>>(path, emptyList(), comment) {
    override val allowed get() = "a list of host names such as shop.example.com"

    override fun parse(raw: String) = toHosts(raw.split(','))

    override fun fromRaw(raw: Any?) = when (raw) {
        is String -> parse(raw.trim())
        is List<*> -> toHosts(raw.map { it as? String })
        else -> null
    }

    override fun render(value: List<String>) = value.joinToString(", ", "[", "]")

    private fun toHosts(entries: List<String?>): List<String>? {
        val hosts = entries.map { it?.trim()?.lowercase(Locale.ROOT) }.filter { it != "" }
        return if (hosts.all { it != null && HOST.matches(it) }) hosts.filterNotNull().distinct() else null
    }

    private companion object {
        val HOST = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*")
    }
}

/**
 * A YAML list of lower-case words such as permission nodes. With [choices] only those words are allowed; an entry
 * outside them (or not shaped like a word) makes the whole value invalid, so a typo falls back to the default with a warning.
 */
internal class WordListKey(path: String, default: List<String>, comment: String, val choices: List<String>? = null) :
    Key<List<String>>(path, default, comment) {
    override val allowed get() = if (choices == null) "a list of words such as shoparchive.users.manage" else "a list of: ${choices.joinToString(", ")}"

    override fun parse(raw: String) = toWords(raw.split(','))

    override fun fromRaw(raw: Any?) = when (raw) {
        is String -> parse(raw.trim())
        is List<*> -> toWords(raw.map { it as? String })
        else -> null
    }

    override fun render(value: List<String>) = value.joinToString(", ", "[", "]")

    private fun toWords(entries: List<String?>): List<String>? {
        val words = entries.map { it?.trim()?.lowercase(Locale.ROOT) }.filter { it != "" }
        val valid = words.all { it != null && WORD.matches(it) && (choices == null || it in choices) }
        return if (valid) words.filterNotNull().distinct() else null
    }

    private companion object {
        val WORD = Regex("[a-z0-9_.-]+")
    }
}

internal class BoolKey(path: String, default: Boolean, comment: String) : Key<Boolean>(path, default, comment) {
    override val allowed get() = "true or false"
    override fun parse(raw: String) = when (raw.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** One of a fixed set of lower-case words; matching ignores case. */
internal class ChoiceKey(path: String, default: String, comment: String, val choices: List<String>) :
    Key<String>(path, default, comment) {
    override val allowed get() = choices.joinToString(", ")
    override fun parse(raw: String) = choices.firstOrNull { it == raw.lowercase(Locale.ROOT) }
}

internal class ZoneKey(path: String, default: ZoneId, comment: String) : Key<ZoneId>(path, default, comment) {
    override val allowed get() = "an IANA time zone id such as Asia/Vientiane"
    override fun parse(raw: String) = try {
        ZoneId.of(raw)
    } catch (e: DateTimeException) {
        null
    }

    override fun render(value: ZoneId) = value.id
}

/** A language tag from a fixed set. */
internal class LocaleKey(path: String, default: Locale, comment: String, val tags: List<String>) :
    Key<Locale>(path, default, comment) {
    override val allowed get() = tags.joinToString(", ")
    override fun parse(raw: String) = tags.firstOrNull { it == raw.lowercase(Locale.ROOT) }?.let(Locale::forLanguageTag)
    override fun render(value: Locale) = value.toLanguageTag()
}

/** A time of day as `HH:mm` (24 hours). */
internal class TimeKey(path: String, default: LocalTime, comment: String) : Key<LocalTime>(path, default, comment) {
    override val allowed get() = "a time of day as HH:mm, e.g. 03:00"
    override fun parse(raw: String): LocalTime? {
        if (!TIME.matches(raw)) return null
        val hour = raw.substring(0, 2).toInt()
        val minute = raw.substring(3).toInt()
        return if (hour < 24 && minute < 60) LocalTime.of(hour, minute) else null
    }
    override fun render(value: LocalTime) = String.format(Locale.ROOT, "%02d:%02d", value.hour, value.minute)

    private companion object {
        val TIME = Regex("\\d{2}:\\d{2}")
    }
}

/** A folder path written between double quotes in the YAML, so any path (`E:\\backup`, one with a `#` or `: `) stays text. Blank means none. */
internal class PathKey(path: String, comment: String) : Key<String>(path, "", comment) {
    override val allowed get() = "a folder path, or empty for none"
    override fun parse(raw: String) = raw
    override fun render(value: String) = quoted(value)
}

/** Resolved value of every key of one file. Immutable, so a whole set can be swapped in one assignment. */
internal class Values(private val map: Map<Key<*>, Any>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: Key<T>): T = map.getValue(key) as T

    fun withAll(replacements: Map<Key<*>, Any>) = Values(map + replacements)

    /** Paths of the keys whose value differs between this and [other], in declared order. */
    fun changedPaths(other: Values): List<String> = map.keys.filter { map[it] != other.map[it] }.map { it.path }
}

/** Resolves every key in [keys] from [flat], warning about every entry of [flat] that no key declares. */
internal fun resolveKeys(keys: List<Key<*>>, flat: Map<String, Any?>, warn: Warn): Values {
    val known = keys.map { it.path }.toSet()
    for (name in flat.keys) {
        if (name !in known) warn("unknown key '$name' (ignored, and not kept when the file is rewritten)")
    }
    return Values(keys.associateWith { it.resolve(flat, warn) })
}
