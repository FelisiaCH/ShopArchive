package xyz.felismp.shoparchive.server.users

import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.server.config.MigrationStep
import xyz.felismp.shoparchive.server.config.Warn
import xyz.felismp.shoparchive.server.config.YamlConfigFile
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** A command the admin typed wrongly or that cannot be done; the message is shown on the console as it is. */
internal class UserException(message: String) : Exception(message)

internal const val NO_ROLE = "none"

/** Why an account is disabled: the server did it after too many wrong PINs (`user unlock` undoes it), or an admin did. */
internal const val DISABLED_BY_BACKOFF = "backoff"
internal const val DISABLED_BY_ADMIN = "admin"
private const val NEW_MARK = "# ใหม่"
private val NAME = Regex("[a-z0-9_]{3,32}")
private val LOCALES = listOf("lo", "th", "en")

internal fun isValidUserName(name: String) = NAME.matches(name) && name != "role"
internal fun isValidRoleName(name: String) = NAME.matches(name) && name != NO_ROLE

internal fun checkUserName(name: String) {
    if (!isValidUserName(name)) throw UserException("Invalid user name '$name': 3 to 32 characters from a-z, 0-9 and _, and not 'role'")
}

internal fun checkRoleName(name: String) {
    if (!isValidRoleName(name)) throw UserException("Invalid role name '$name': 3 to 32 characters from a-z, 0-9 and _, and not '$NO_ROLE'")
}

internal data class UserData(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val op: Boolean,
    val role: String,
    val branches: List<String>,
    val locale: String,
    val password: String?,
    val pin: String?,
    val permissions: Map<String, Boolean>,
    val newNodes: Set<String> = emptySet(),
    /** Wrong PINs and passwords since the last right one; [lockedUntil] is when the next try is allowed. Kept here so a restart does not clear them. */
    val failedLogins: Int = 0,
    val lockedUntil: Instant? = null,
    val disabledReason: String? = null,
)

internal data class RoleData(val displayName: String, val permissions: Map<String, Boolean>, val newNodes: Set<String> = emptySet())

/** Only a hash can be checked later; anything else in the file is kept as it is but cannot be used to log in. */
internal fun isUsableCredential(value: String?) = value != null && value.startsWith("\$argon2id\$")

/** `user/<name>.yml`. [roles] is what a role-user's permission block is copied from. */
internal class UserFile(
    private val name: String,
    private val roles: Map<String, RoleData>,
    private val nodes: List<PermissionNode>,
    private val defaultLocale: String,
    currentVersion: Int = 1,
    steps: List<MigrationStep> = emptyList(),
) : YamlConfigFile<UserData>("file-version", currentVersion, steps) {
    override val path = "user/$name.yml"
    override val title = "ShopArchive user '$name'. Change it with the console, or edit by hand and run 'reload users'."

    private val known = setOf("id", "display-name", "enabled", "op", "role", "branches", "locale", "password", "pin", "permissions", "failed-logins", "locked-until", "disabled-reason")

    // A '# ใหม่' the template wrote earlier is a comment, which the YAML parse drops, so it is read from the text.
    override fun parse(text: String, warn: Warn): UserData {
        val user = super.parse(text, warn)
        return user.copy(newNodes = user.newNodes + markedNew(text).filter { it in user.permissions })
    }

    override fun resolve(content: Map<String, Any?>, warn: Warn): UserData {
        for (key in content.keys) if (key !in known) warn("unknown key '$key' (ignored, and not kept when the file is rewritten)")
        val id = (content["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { warn("id is missing; made a new one: $it") }
        val role = (content["role"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: NO_ROLE
        var locale = defaultLocale
        if ("locale" in content) {
            val given = (content["locale"] as? String)?.trim()?.lowercase()
            if (given in LOCALES) locale = given!! else warn("locale: invalid value '${content["locale"] ?: "(empty)"}'; using '$locale' (expected ${LOCALES.joinToString()})")
        }

        val fileBlock = readBlock(content["permissions"], warn)
        val roleData = roles[role]
        if (role != NO_ROLE && roleData == null) {
            warn("role '$role' does not exist; this user has no permissions until the role is back or 'user role $name none' is run")
        }
        val (permissions, newNodes) = if (roleData != null) {
            if (fileBlock.any { (node, value) -> roleData.permissions[node] != value }) {
                warn("the permissions block differs from role '$role'; rewritten as a copy of the role (change user/role/$role.yml instead)")
            }
            roleData.permissions to roleData.newNodes
        } else {
            fillBlock(fileBlock, nodes, warn)
        }
        return UserData(
            id = id,
            displayName = (content["display-name"] as? String)?.takeIf { it.isNotBlank() } ?: name,
            enabled = readBool(content, "enabled", whenMissing = true, warn = warn),
            op = readBool(content, "op", whenMissing = false, warn = warn),
            role = role,
            branches = readBranches(content["branches"], warn),
            locale = locale,
            password = readCredential(content, "password", warn),
            pin = readCredential(content, "pin", warn),
            permissions = permissions,
            newNodes = newNodes,
            failedLogins = (content["failed-logins"] as? String)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            lockedUntil = readLockedUntil(content["locked-until"], warn),
            disabledReason = (content["disabled-reason"] as? String)?.trim()?.lowercase()?.takeIf { it == DISABLED_BY_BACKOFF || it == DISABLED_BY_ADMIN },
        )
    }

    override fun renderBody(value: UserData): String = buildString {
        append("id: ").append(value.id).append('\n')
        append("display-name: ").append(quoted(value.displayName)).append('\n')
        append("enabled: ").append(value.enabled).append('\n')
        append("op: ").append(value.op).append('\n')
        append("role: ").append(scalar(value.role)).append('\n')
        append("branches: ").append(value.branches.joinToString(", ", "[", "]") { scalar(it) }).append('\n')
        append("locale: ").append(value.locale).append('\n')
        append("password: ").append(value.password?.let(::quoted) ?: "null").append('\n')
        append("pin: ").append(value.pin?.let(::quoted) ?: "null").append('\n')
        append("failed-logins: ").append(value.failedLogins).append('\n')
        append("locked-until: ").append(value.lockedUntil ?: "null").append('\n')
        append("disabled-reason: ").append(value.disabledReason ?: "null").append('\n')
        if (value.role != NO_ROLE) {
            append("# A copy of role '").append(value.role).append("', rewritten on every start and reload. Change user/role/")
                .append(value.role).append(".yml instead.\n")
        }
        append(renderBlock(value.permissions, value.newNodes))
    }
}

/** `user/role/<name>.yml`. */
internal class RoleFile(private val name: String, private val nodes: List<PermissionNode>) :
    YamlConfigFile<RoleData>("file-version", 1, emptyList()) {
    override val path = "user/role/$name.yml"
    override val title = "ShopArchive role '$name'. Change it with the console, or edit by hand and run 'reload users'."

    private val known = setOf("display-name", "permissions")

    override fun parse(text: String, warn: Warn): RoleData {
        val role = super.parse(text, warn)
        return role.copy(newNodes = role.newNodes + markedNew(text).filter { it in role.permissions })
    }

    override fun resolve(content: Map<String, Any?>, warn: Warn): RoleData {
        for (key in content.keys) if (key !in known) warn("unknown key '$key' (ignored, and not kept when the file is rewritten)")
        val (permissions, newNodes) = fillBlock(readBlock(content["permissions"], warn), nodes, warn)
        return RoleData((content["display-name"] as? String)?.takeIf { it.isNotBlank() } ?: name, permissions, newNodes)
    }

    override fun renderBody(value: RoleData): String =
        "display-name: ${quoted(value.displayName)}\n" + renderBlock(value.permissions, value.newNodes)
}

/** The nodes in a `permissions:` block. A value that is not true or false reads as false (with a warning): a typo must not turn a default-true node on, or grant it to a whole role. */
private fun readBlock(raw: Any?, warn: Warn): Map<String, Boolean> {
    if (raw == null) return emptyMap()
    val map = raw as? Map<*, *>
    if (map == null) {
        warn("permissions: expected lines of 'node: true' or 'node: false'; ignored")
        return emptyMap()
    }
    val out = LinkedHashMap<String, Boolean>()
    for ((node, value) in map) {
        when ((value as? String)?.trim()?.lowercase()) {
            "true" -> out[node as String] = true
            "false" -> out[node as String] = false
            else -> {
                out[node as String] = false
                warn("permissions: '$node' has the value '${value ?: "(empty)"}', not true or false; using false")
            }
        }
    }
    return out
}

/** Every registered node gets a value (a missing one: its default, marked new). A node nobody registers is kept and warned about. */
private fun fillBlock(block: Map<String, Boolean>, nodes: List<PermissionNode>, warn: Warn): Pair<Map<String, Boolean>, Set<String>> {
    val registered = nodes.map { it.node }.toSet()
    val out = LinkedHashMap<String, Boolean>()
    val added = LinkedHashSet<String>()
    for (node in nodes) {
        val value = block[node.node]
        if (value == null) added += node.node
        out[node.node] = value ?: node.default
    }
    for ((node, value) in block) {
        if (node in registered) continue
        warn("permission node '$node' is not registered (any more); kept as it is")
        out[node] = value
    }
    return out to added
}

private fun renderBlock(permissions: Map<String, Boolean>, newNodes: Set<String>): String {
    if (permissions.isEmpty()) return "permissions: {}\n"
    return permissions.entries.joinToString("\n", "permissions:\n", "\n") { (node, value) ->
        "  ${scalar(node)}: $value" + if (node in newNodes) "  $NEW_MARK" else ""
    }
}

private val MARKED = Regex("^\\s+(\\S+):\\s+(?:true|false)\\s+${Regex.escape(NEW_MARK)}\\s*$", RegexOption.MULTILINE)

private fun markedNew(text: String): Set<String> = MARKED.findAll(text).map { it.groupValues[1] }.toSet()

/** A bad value reads as false: a typo must not enable a user or make one an op. */
private fun readBool(content: Map<String, Any?>, key: String, whenMissing: Boolean, warn: Warn): Boolean {
    if (key !in content) return whenMissing
    return when ((content[key] as? String)?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> false.also { warn("$key: invalid value '${content[key] ?: "(empty)"}' (expected true or false); using false") }
    }
}

private fun readBranches(raw: Any?, warn: Warn): List<String> {
    if (raw == null) return emptyList()
    val list = raw as? List<*>
    if (list == null) {
        warn("branches: expected a list such as [main, market]; ignored")
        return emptyList()
    }
    val names = list.map { (it as? String)?.trim().orEmpty() }
    if (names.any { it.isEmpty() }) warn("branches: an entry is not a name; ignored")
    return names.filter { it.isNotEmpty() }.distinct()
}

private fun readCredential(content: Map<String, Any?>, key: String, warn: Warn): String? {
    val value = (content[key] as? String)?.takeIf { it.isNotBlank() }
    if (value != null && !isUsableCredential(value)) warn("$key is not an Argon2id hash; it cannot be used to log in until it is reset")
    return value
}

/** A bad time means no lock: a typo must not lock an account for ever. */
private fun readLockedUntil(raw: Any?, warn: Warn): Instant? {
    val text = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    return try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        warn("locked-until: not a time like 2026-10-03T08:00:00Z; using none")
        null
    }
}

/** A double-quoted YAML string. */
internal fun quoted(s: String): String = buildString {
    append('"')
    for (c in s) when {
        c == '\\' -> append("\\\\")
        c == '"' -> append("\\\"")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append(String.format("\\u%04x", c.code))
        else -> append(c)
    }
    append('"')
}

/** Bare when that reads back as the same text, else quoted. */
internal fun scalar(s: String): String = if (Regex("[A-Za-z0-9_.\\-]+").matches(s) && s != "null") s else quoted(s)
