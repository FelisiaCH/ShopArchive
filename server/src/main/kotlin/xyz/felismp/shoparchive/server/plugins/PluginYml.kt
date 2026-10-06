package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.parseYamlMap

/** plugin.yml that cannot be used; [message] says which fields are wrong, so the admin can send it to the plugin's author. */
internal class PluginYmlException(message: String) : Exception(message)

/** A plugin's plugin.yml, checked. */
internal class PluginYml(
    val name: String,
    val version: String,
    val main: String,
    val apiVersion: Int,
    val depend: List<String>,
    val softdepend: List<String>,
    val description: String,
    /** `hotfix: true`: a bytecode patch the launcher applied (or not) before the core started; it has no main class and is never run as a plugin. */
    val hotfix: Boolean,
    /** Hotfix only: the IDs it fixes (`fixes`), `security` or `bug` (`severity`), and the core build it was made for (`target-build`). */
    val fixes: List<String>,
    val severity: String?,
    val targetBuild: Int?,
    /** Keys the server does not know; ignored, and reported once at load. */
    val unknownKeys: List<String>,
)

private val NAME = Regex("[A-Za-z0-9_-]{1,32}")
private val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
private val KNOWN = setOf("name", "version", "main", "api-version", "depend", "softdepend", "description", "hotfix", "fixes", "severity", "target-build")
private val FIX_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

/** Reads and checks [text]; every wrong field is named in the one exception, not just the first. */
internal fun parsePluginYml(text: String): PluginYml {
    val map = try {
        parseYamlMap("plugin.yml", text)
    } catch (e: ConfigFileException) {
        throw PluginYmlException(e.message ?: "plugin.yml cannot be read")
    }
    val problems = mutableListOf<String>()

    fun text(key: String, required: Boolean = true): String? {
        val value = (map[key] as? String)?.trim()
        if (value.isNullOrEmpty()) {
            if (required) problems += "$key is required"
            else if (key in map) problems += "$key must be text"
            return null
        }
        return value
    }

    fun names(key: String): List<String> {
        if (key !in map || map[key] == null) return emptyList()
        val list = map[key] as? List<*>
        if (list == null) {
            problems += "$key must be a list of plugin names"
            return emptyList()
        }
        return list.mapNotNull { entry ->
            val value = (entry as? String)?.trim()
            if (value == null || !NAME.matches(value)) {
                problems += "$key has '${entry ?: "(empty)"}', which is not a plugin name (letters, digits, _ and -, 1 to 32 characters)"
                null
            } else value
        }.distinct()
    }

    val name = text("name")?.also { if (!NAME.matches(it)) problems += "name '$it' must be 1 to 32 characters of letters, digits, _ and -" }
    val version = text("version")
    val hotfix = when (val raw = text("hotfix", required = false)?.lowercase()) {
        null, "false" -> false
        "true" -> true
        else -> { problems += "hotfix must be true or false (found '$raw')"; false }
    }
    // A hotfix is not run as a plugin: no main class and no plugin api.
    val main = text("main", required = !hotfix)?.also { if (!CLASS_NAME.matches(it)) problems += "main '$it' is not a class name such as com.example.MyPlugin" }
    val apiVersion = text("api-version", required = !hotfix)?.let { raw ->
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed < 1) {
            problems += "api-version '$raw' must be a whole number from 1"
            null
        } else parsed
    }
    val depend = names("depend")
    val softdepend = names("softdepend").filter { it !in depend }
    if (name != null && (name in depend || name in softdepend)) problems += "a plugin cannot depend on itself"
    val description = text("description", required = false).orEmpty()

    val fixes = if (hotfix) {
        val list = (map["fixes"] as? List<*>)?.mapNotNull { (it as? String)?.trim() }.orEmpty()
        if (list.isEmpty()) problems += "fixes must be a list of at least one ID"
        list.filter { !FIX_ID.matches(it) }.forEach { problems += "fixes has '$it', which is not an ID (letters, digits, . _ -, 1 to 64 characters)" }
        list.distinct()
    } else emptyList()
    val severity = text("severity", required = false)
    if (hotfix && severity != "security" && severity != "bug") problems += "severity must be security or bug"
    val targetBuild = text("target-build", required = false)?.let { raw -> raw.toIntOrNull()?.takeIf { it >= 1 } ?: run { problems += "target-build '$raw' must be a whole number from 1"; null } }
    if (hotfix && targetBuild == null && "target-build" !in map) problems += "target-build is required"
    if (!hotfix) listOf("fixes", "severity", "target-build").filter { it in map }.forEach { problems += "$it is only for a hotfix (hotfix: true)" }

    if (problems.isNotEmpty()) throw PluginYmlException("plugin.yml: " + problems.joinToString("; "))
    return PluginYml(name!!, version!!, main.orEmpty(), apiVersion ?: 0, depend, softdepend, description, hotfix, fixes, severity, targetBuild, map.keys.filter { it !in KNOWN })
}
