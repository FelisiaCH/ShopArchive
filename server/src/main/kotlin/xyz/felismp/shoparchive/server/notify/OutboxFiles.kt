package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.parseYamlMap
import xyz.felismp.shoparchive.server.users.quoted
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.NotificationState
import java.time.Instant
import java.time.format.DateTimeParseException

/** The newest `file-version` of `data/outbox/<id>.yml` this server writes and understands. */
internal const val OUTBOX_FILE_VERSION = 1

/**
 * One message in the outbox: what to send ([notification]) and where it is. [attempts] counts every try, [failures] only those the channel
 * refused (a retry answer or an error), which is what `notify.max-attempts` limits; a try that got no answer in time is in [attempts] only.
 * [nextAttempt] is when a queued or unknown message is due again (null: now). Times of tries are text like every time in the files.
 */
internal data class OutboxItem(
    val notification: Notification,
    val state: NotificationState,
    val attempts: Int = 0,
    val failures: Int = 0,
    val lastAttempt: String? = null,
    val nextAttempt: Instant? = null,
    val sentAt: String? = null,
    val lastError: String? = null,
    /** The entry or day session the message is about (see [Draft.subject]); null for files written before it was kept. */
    val subject: String? = null,
) {
    val id: String get() = notification.id

    fun toDto() = NotificationDto(
        id, notification.code, notification.event, state, notification.branch, notification.user, notification.createdAt,
        attempts, failures, lastAttempt, nextAttempt?.toString(), lastError, notification.resendOf, describe(notification),
    )
}

/** A file of the outbox that cannot be understood; the message says what is wrong. The file is left as it is. */
internal class OutboxFormatException(message: String) : Exception(message)

private fun text(value: String?) = if (value == null) "null" else quoted(value)

internal fun renderOutboxItem(item: OutboxItem): String = buildString {
    val n = item.notification
    append("file-version: $OUTBOX_FILE_VERSION\n")
    append("id: ${quoted(n.id)}\n")
    append("code: ${quoted(n.code)}\n")
    append("event: ${quoted(n.event)}\n")
    append("created: ${quoted(n.createdAt)}\n")
    append("branch: ${quoted(n.branch)}\n")
    append("user: ${quoted(n.user)}\n")
    append("locale: ${quoted(n.locale)}\n")
    append("resend-of: ${text(n.resendOf)}\n")
    append("subject: ${text(item.subject)}\n")
    append("state: ${item.state.name.lowercase()}\n")
    append("attempts: ${item.attempts}\n")
    append("failures: ${item.failures}\n")
    append("last-attempt: ${text(item.lastAttempt)}\n")
    append("next-attempt: ${text(item.nextAttempt?.toString())}\n")
    append("sent-at: ${text(item.sentAt)}\n")
    append("last-error: ${text(item.lastError)}\n")
    if (n.fields.isEmpty()) {
        append("fields: {}\n")
    } else {
        append("fields:\n")
        for ((key, value) in n.fields) append("  ${quoted(key)}: ${quoted(value)}\n")
    }
}

/** [content] of the file [name] (for messages) as an item. @throws OutboxFormatException if it is not a file this server wrote */
internal fun parseOutboxItem(name: String, content: String): OutboxItem {
    val map = try {
        parseYamlMap(name, content)
    } catch (e: ConfigFileException) {
        throw OutboxFormatException(e.message ?: "cannot be parsed")
    }
    fun need(key: String): String = (map[key] as? String)?.takeIf { it.isNotBlank() } ?: throw OutboxFormatException("$key is missing")
    fun optional(key: String): String? = map[key] as? String
    // Written always, but may be empty: a device.new for a user with no branch has none.
    fun present(key: String): String = optional(key) ?: throw OutboxFormatException("$key is missing")
    fun count(key: String): Int = need(key).toIntOrNull()?.takeIf { it >= 0 } ?: throw OutboxFormatException("$key is not a whole number")
    val version = need("file-version").toIntOrNull() ?: throw OutboxFormatException("file-version is not a number")
    if (version > OUTBOX_FILE_VERSION) throw OutboxFormatException("file-version $version is newer than this server understands ($OUTBOX_FILE_VERSION)")
    val state = NotificationState.entries.firstOrNull { it.name.lowercase() == need("state") } ?: throw OutboxFormatException("state '${need("state")}' is not queued, sent, unknown or failed")
    val next = optional("next-attempt")?.let {
        try { Instant.parse(it) } catch (e: DateTimeParseException) { throw OutboxFormatException("next-attempt '$it' is not a time") }
    }
    val fields = when (val raw = map["fields"]) {
        null -> emptyMap()
        is Map<*, *> -> raw.entries.associate { (k, v) -> k.toString() to (v as? String ?: "") }
        else -> throw OutboxFormatException("fields is not a list of words")
    }
    val notification = Notification(
        id = need("id"), code = need("code"), event = need("event"), createdAt = need("created"), branch = present("branch"),
        user = optional("user").orEmpty(), locale = need("locale"), fields = fields, attempt = 1, resendOf = optional("resend-of"),
    )
    return OutboxItem(notification, state, count("attempts"), count("failures"), optional("last-attempt"), next, optional("sent-at"), optional("last-error"), optional("subject"))
}
