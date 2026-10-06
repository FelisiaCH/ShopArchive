package xyz.felismp.shoparchive.api

import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.NotificationState

/**
 * One message to send, made from an event. [fields] holds the words a channel puts into its template, by name:
 * `type` (`income`, `expense`, or the event type for a day), `category` (its name in [locale], empty when the entry has none; `category.lo`, `category.th` and `category.en` hold all three, `category.key` the key), `item`, `amount`, `currency`, `branch`
 * (the display name), `user` and `time`; for an entry paid in more than one currency [amount] and [currency] list each, in the same order, joined with `, `.
 * `day.closed` adds `date`, `closedBy`, `entries`, `slips`, `note` and, one text per field with the currencies joined by `; `
 * (`LAK 150000; THB 20.50`): `income`, `expense`, `net`, `cashIn`, `cashOut`, `onlineIn`, `onlineOut`, `float`, `counted`, `expected`, `variance`, `kept`, `handover`.
 * Every notification also has `code` and `date`. The words are data, not text: the channel decides the language and layout.
 */
class Notification(
    /** The outbox id: unique and sortable, a new one for every message including a message sent again. */
    val id: String,
    /** Short and printed in the message by the channel (`20261003-A3F9C`): it names the entry or the day, so a message that arrives twice can be recognised. A message sent again has the same code. */
    val code: String,
    /** A [ShopEventTypes] value. */
    val event: String,
    val createdAt: String,
    /** The branch key. */
    val branch: String,
    /** The name of the user the event is about: who recorded the entry, who closed the day. */
    val user: String,
    /** The language the server speaks (`lo`, `th` or `en`): a default for a channel that has no language of its own. */
    val locale: String,
    val fields: Map<String, String>,
    /** 1 for the first try; higher when the message is tried again, so a channel may say it is a repeat. */
    val attempt: Int = 1,
    /** The id of the message this one repeats, if it was sent again by hand. */
    val resendOf: String? = null,
)

/** What a [Notifier] says about one try. Anything else it does (throwing) is understood from what is thrown, see [Notifier.deliver]. */
sealed interface DeliveryResult {
    /** The channel accepted the message. The outbox marks it sent and never offers it again. */
    data object Sent : DeliveryResult

    /**
     * The channel could not take it now but may later (no network, rate limit, 5xx): the outbox tries again after a growing wait. It counts
     * towards `notify.max-attempts`. [reason] is stored and shown to admins, so it must say what was wrong without a secret in it.
     */
    class Retry(val reason: String) : DeliveryResult

    /**
     * This try failed and [reason] is worth showing to an admin (the chat does not exist, the token is wrong). Unlike [Retry] it says the cause is probably
     * not temporary, but the outbox still treats it as a failed attempt: it is tried again after the backoff wait and the message is only given up as
     * `failed` after `notify.max-attempts` failed attempts, so a fixed setting still gets the message through. Same rule for [reason] as for [Retry].
     */
    class Failed(val reason: String) : DeliveryResult
}

/**
 * Sends a [Notification] to a channel (Telegram, Discord, mail...). The core registers one that only logs, with priority 0; a channel
 * plugin registers its own with a higher priority and the highest one is used ([ServiceRegistry] rules), so installing a channel plugin
 * turns the messages to it and removing the plugin turns them back to the log.
 *
 * A plugin whose settings are missing or wrong when it is enabled (an empty bot token or webhook) should not register its Notifier at all:
 * it warns in its own log and the next notifier is used, so the server keeps working and nothing is lost to a channel that cannot send.
 * A message that is left in the outbox is sent by whichever notifier is in use when its turn comes.
 *
 * The outbox calls [deliver] on its own thread, one message at a time. It may block, but a call that takes longer than `notify.timeout-seconds` or
 * fails with a timeout (a [java.util.concurrent.TimeoutException] or a [java.io.InterruptedIOException] such as a socket timeout, also as the cause of what was
 * thrown) is recorded as unknown, which is not a failure: the message may have arrived, so it is tried again later with the same code.
 * Any other exception counts as a [DeliveryResult.Retry]. A notifier must not keep the outbox waiting for ever and must be safe to call again with the same message.
 */
fun interface Notifier {
    fun deliver(notification: Notification): DeliveryResult
}

/**
 * The outbox as the apps and the admin see it. Every method checks what the caller may do and throws [ApiError] if not; routes call it
 * through the [ServiceRegistry], so a plugin can replace it.
 */
interface NotificationService {
    /**
     * The newest messages first, at most [limit] (1 to 500), only those in [state] if given, only of branches the caller works in.
     * @throws ApiError 403 without `shoparchive.notifications.view`
     */
    fun list(principal: Principal, state: NotificationState?, limit: Int): List<NotificationDto>

    /**
     * Puts a copy of a sent or failed message into the outbox again: a new message with the same code, naming [id] in [NotificationDto.resendOf].
     * The old one is not changed.
     * @throws ApiError 403 without `shoparchive.notifications.send` or for a branch the caller does not work in; 404 for an unknown id;
     * 409 [xyz.felismp.shoparchive.shared.ErrorReasons.NOTIFICATION_PENDING] while the message is still queued or unknown
     */
    fun resend(principal: Principal, id: String): NotificationDto

    /**
     * Puts the summary of a closed day into the outbox, as the close did the first time (same code).
     * @throws ApiError 403 without `shoparchive.notifications.send` or the branch; 404 for a day that does not exist or is still open
     */
    fun notifyDay(principal: Principal, branch: String, sessionId: String): NotificationDto
}
