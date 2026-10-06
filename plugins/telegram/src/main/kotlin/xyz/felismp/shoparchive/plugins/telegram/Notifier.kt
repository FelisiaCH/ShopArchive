package xyz.felismp.shoparchive.plugins.telegram

import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.Notifier
import java.io.IOException
import java.io.InterruptedIOException
import java.net.http.HttpTimeoutException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeoutException

/**
 * What was already done for one notification, so a try again does not send to a chat twice: `want <chat>` still to answer, `ok <chat>` accepted,
 * `dead <chat>` refused for good. One small file per notification id in the plugin's folder, written whole (temporary file, fsync, rename). The chats a
 * notification is meant for are written as `want` before the first send, so the record itself knows when it is complete: a later change to the configured
 * chats (removing one that never answered) cannot make it look complete. The file is kept after the notification is done: only the core knows when a
 * message is final (it may try a failed one again), so a file is pruned ([prune], `ledger-keep-days`) only when no chat is still `want` and it is old.
 * A file with a chat that never answered (one that timed out is tried again without limit) is never pruned, whatever its age, or the chats that accepted
 * would get the message again; such a file stays, a few bytes.
 */
interface SentLedger {
    /** The final answers (`ok`, `dead`) by chat; chats still `want` are not in it. */
    fun read(id: String): Map<String, String>

    /** Records [chats] as `want` for [id]; chats the record already has keep their state. */
    fun want(id: String, chats: Collection<String>)

    fun mark(id: String, chat: String, state: String)

    /**
     * Removes the records that are complete (no chat still `want`) and older than [maxAgeDays] days by the file's last change, which is the last answer recorded.
     * They are plugin data, not business data: they only stop a retry from sending twice.
     */
    fun prune(maxAgeDays: Int)
}

class FileSentLedger(private val dir: Path) : SentLedger {
    private fun file(id: String): Path = dir.resolve(id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt")

    @Synchronized
    override fun read(id: String): Map<String, String> = readAll(id).filterValues { it != WANT }

    @Synchronized
    override fun want(id: String, chats: Collection<String>) {
        val all = readAll(id)
        val missing = chats.filter { it !in all }
        if (missing.isNotEmpty()) write(id, all + missing.associateWith { WANT })
    }

    @Synchronized
    override fun mark(id: String, chat: String, state: String) = write(id, readAll(id) + (chat to state))

    private fun readAll(id: String): Map<String, String> {
        val f = file(id)
        if (!Files.exists(f)) return emptyMap()
        return try {
            Files.readAllLines(f, Charsets.UTF_8).mapNotNull { line -> line.split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[1] to it[0] } }.toMap()
        } catch (e: IOException) {
            emptyMap()
        }
    }

    private fun write(id: String, all: Map<String, String>) {
        Files.createDirectories(dir)
        val tmp = dir.resolve(file(id).fileName.toString() + ".tmp")
        try {
            Files.write(tmp, all.map { "${it.value} ${it.key}" }.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(tmp, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Synchronized
    override fun prune(maxAgeDays: Int) {
        if (!Files.isDirectory(dir)) return
        val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
        try {
            Files.newDirectoryStream(dir, "*.txt").use { stream ->
                for (f in stream) {
                    if (Files.getLastModifiedTime(f).toMillis() >= cutoff) continue
                    val states = try {
                        Files.readAllLines(f, Charsets.UTF_8).mapNotNull { line -> line.split(' ', limit = 2).takeIf { it.size == 2 }?.get(0) }
                    } catch (e: IOException) { continue }
                    if (states.isNotEmpty() && WANT !in states) Files.deleteIfExists(f)
                }
            }
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val WANT = "want"
    }
}

/**
 * Sends a notification to every chat through the Bot API. One notification is sent when every chat accepted it. A chat that accepted is remembered
 * in the [ledger] so a try again goes only to the others; a message whose answer never came may still arrive twice, which its code makes recognisable.
 * Answers: 2xx accepted; 429, 5xx and no connection are tried again; 400, 401, 403, 404 and other 4xx will never work for that chat (the notification fails once the rest is done);
 * a wait for an answer that ran out is a [TimeoutException], which the server records as "unknown", not as a failure.
 * Nothing it returns or throws holds the bot token or the address: the address contains the token.
 */
class TelegramNotifier(
    private val settings: () -> TelegramSettings?,
    private val transport: HttpTransport,
    private val ledger: SentLedger,
    private val renderer: TemplateRenderer = PlaceholderRenderer(),
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : Notifier {
    private var lastPrune = 0L

    private sealed interface Outcome {
        data object Accepted : Outcome
        class Again(val reason: String) : Outcome
        class Never(val reason: String) : Outcome
        data object TimedOut : Outcome
    }

    override fun deliver(notification: Notification): DeliveryResult {
        val s = settings() ?: return DeliveryResult.Retry("the Telegram settings are incomplete; fix plugins/Telegram/config.yml and reload")
        if (notification.event !in s.events) return DeliveryResult.Sent
        val template = s.templates[notification.event]?.get(s.language)
            ?: return DeliveryResult.Failed("there is no template for ${notification.event} in ${s.language}")
        val text = renderer.render(template, notification, s.language)
        if (clock() - lastPrune > PRUNE_EVERY_MS) { lastPrune = clock(); ledger.prune(s.ledgerKeepDays) }
        ledger.want(notification.id, s.chatIds)
        val done = ledger.read(notification.id).toMutableMap()

        var again: String? = null
        var never: String? = null
        var timedOut = false
        for (chat in s.chatIds) {
            if (chat in done) continue
            when (val outcome = send(s, chat, text)) {
                Outcome.Accepted -> { ledger.mark(notification.id, chat, "ok"); done[chat] = "ok" }
                is Outcome.Never -> { ledger.mark(notification.id, chat, "dead"); done[chat] = "dead"; never = never ?: outcome.reason }
                is Outcome.Again -> again = again ?: "chat $chat: ${outcome.reason}"
                Outcome.TimedOut -> timedOut = true
            }
        }
        if (timedOut) throw TimeoutException("no answer from Telegram")
        again?.let { return DeliveryResult.Retry(it) }
        // Every chat has an answer now. Sent only when none refused for good; otherwise Failed, with the count (never a chat id). The ledger stays:
        // if the core tries a failed message again, nobody who already accepted gets it again, and a chat that refused is not asked again either.
        val refused = s.chatIds.count { done[it] == "dead" }
        return if (refused == 0) DeliveryResult.Sent
        else DeliveryResult.Failed("$refused of ${s.chatIds.size} chat(s) refused the message${never?.let { " ($it)" } ?: " earlier"}")
    }

    private fun send(s: TelegramSettings, chat: String, text: String): Outcome {
        val body = """{"chat_id":${jsonString(chat)},"text":${jsonString(text)},"disable_web_page_preview":true}"""
        val answer = try {
            transport.postJson("${s.apiUrl}/bot${s.botToken}/sendMessage", body, s.httpTimeoutSeconds)
        } catch (e: HttpTimeoutException) {
            return Outcome.TimedOut
        } catch (e: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw TimeoutException("the wait was ended")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TimeoutException("the wait was ended")
        } catch (e: IOException) {
            // Only the class: the message of a network error may quote the address, and the address holds the token.
            return Outcome.Again("no connection to Telegram (${e.javaClass.simpleName})")
        }
        val status = answer.status
        val detail = describe(answer.body, s.botToken)
        return when {
            status in 200..299 -> Outcome.Accepted
            status == 429 -> Outcome.Again("Telegram says too many requests (429)$detail")
            status >= 500 -> Outcome.Again("Telegram is not working now ($status)$detail")
            status == 401 -> Outcome.Never("the bot token is wrong (401)$detail")
            status == 403 -> Outcome.Never("the bot may not write to this chat (403)$detail")
            status == 404 -> Outcome.Never("Telegram does not know the bot or the chat (404)$detail")
            status == 400 -> Outcome.Never("Telegram refused the message (400)$detail")
            else -> Outcome.Never("Telegram answered $status$detail")
        }.also { if (it !is Outcome.Accepted) log("chat $chat: Telegram answered $status") }
    }

    /** Telegram's own words (`Forbidden: bot was blocked by the user`) when it gave some, with the token taken out should it be quoted. */
    private fun describe(body: String, token: String): String {
        val text = DESCRIPTION.find(body)?.groupValues?.get(1) ?: return ""
        return ": " + text.replace(token, "***").replace("\\\"", "\"").take(120)
    }

    companion object {
        private const val PRUNE_EVERY_MS = 3_600_000L
        private val DESCRIPTION = Regex("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
