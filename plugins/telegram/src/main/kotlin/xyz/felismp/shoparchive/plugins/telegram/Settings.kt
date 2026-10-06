package xyz.felismp.shoparchive.plugins.telegram

import xyz.felismp.shoparchive.api.plugin.PluginConfig

/** The languages a template exists in. */
val LANGUAGES = listOf("lo", "th", "en")

/** The events this channel knows how to write; `notify.events` in the server's config decides which of them reach any channel. */
val KNOWN_EVENTS = listOf("entry.created", "day.closed")

/** The key of an event's templates in config.yml: the dot of the event name would read as a level. */
fun templateKey(event: String): String = event.replace('.', '-')

/** Exactly `localhost`, the IPv6 literal `[::1]`, or an IPv4 literal of four plain decimal octets (0 to 255, no leading zeros) starting with 127. No name is resolved. */
internal fun isLoopbackHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true) || host == "[::1]") return true
    val octets = host.split('.')
    if (octets.size != 4) return false
    val values = octets.map { o -> if (Regex("0|[1-9][0-9]{0,2}").matches(o)) o.toInt() else return false }
    return values[0] == 127 && values.all { it in 0..255 }
}

/**
 * Whether [text] is an address the plugin may send to: https, with a host; http only for this computer (`localhost`, a 127.x.x.x address, `[::1]`, written as such), which is how the tests
 * and a local proxy are reached. [needPath] asks for a path beyond `/` (a webhook has one). A port, when given, is 1 to 65535. Nothing here ever prints the address: it holds a secret.
 */
fun validEndpoint(text: String, needPath: Boolean): Boolean {
    val uri = try { java.net.URI(text) } catch (e: java.net.URISyntaxException) { return false }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
    val local = isLoopbackHost(host)
    if (!(uri.scheme == "https" || (uri.scheme == "http" && local))) return false
    if (uri.userInfo != null) return false
    if (uri.port != -1 && uri.port !in 1..65535) return false
    return !needPath || (uri.rawPath?.trim('/')?.isNotEmpty() == true)
}

/** What the plugin reads from config.yml, after checking. See the comments of the shipped config.yml for each value. */
class TelegramSettings(
    val botToken: String,
    val chatIds: List<String>,
    val language: String,
    val events: Set<String>,
    /** event type to language to text. */
    val templates: Map<String, Map<String, String>>,
    val apiUrl: String,
    val httpTimeoutSeconds: Int,
    val ledgerKeepDays: Int,
) {
    companion object {
        const val DEFAULT_API_URL = "https://api.telegram.org"
        const val DEFAULT_LANGUAGE = "lo"
        const val DEFAULT_HTTP_TIMEOUT_SECONDS = 10
        val HTTP_TIMEOUT_RANGE = 2..25
        const val DEFAULT_LEDGER_KEEP_DAYS = 7
        val LEDGER_KEEP_RANGE = 1..90
        private val CHAT_ID = Regex("-?\\d{1,20}|@[A-Za-z][A-Za-z0-9_]{3,}")

        /**
         * Reads and checks the config. Returns the settings, or null when the channel cannot work (a reason is given to [warn]);
         * values that are merely off (a bad language, a timeout out of range, one bad chat id) are corrected with a warning.
         */
        fun read(config: PluginConfig, warn: (String) -> Unit): TelegramSettings? {
            var ok = true
            fun problem(text: String) { warn(text); ok = false }

            val token = config.getString("bot-token", "").trim()
            if (token.isEmpty()) problem("bot-token is empty")
            else if (token.any { it.isWhitespace() }) problem("bot-token contains a space")

            val chats = config.getStringList("chat-ids").map { it.trim() }.filter { it.isNotEmpty() }.filter {
                CHAT_ID.matches(it).also { valid -> if (!valid) warn("chat-ids: '$it' is not a chat id (a number such as -1001234567890, or @channelname); ignored") }
            }.distinct()
            if (chats.isEmpty()) problem("chat-ids has no chat to send to")

            var language = config.getString("language", DEFAULT_LANGUAGE).trim().lowercase()
            if (language !in LANGUAGES) {
                warn("language: '$language' is not one of ${LANGUAGES.joinToString()}; using $DEFAULT_LANGUAGE")
                language = DEFAULT_LANGUAGE
            }

            val events = config.getStringList("events").map { it.trim() }.filter { it.isNotEmpty() }.filter {
                (it in KNOWN_EVENTS).also { known -> if (!known) warn("events: '$it' is not an event this channel sends (${KNOWN_EVENTS.joinToString()}); ignored") }
            }.toSet()
            if (events.isEmpty()) problem("events lists nothing to send")

            val templates = KNOWN_EVENTS.associateWith { event ->
                LANGUAGES.mapNotNull { lang -> config.getString("templates.${templateKey(event)}.$lang", "").takeIf { it.isNotBlank() }?.let { lang to it } }.toMap()
            }
            for (event in events) if (language !in templates.getValue(event)) problem("templates.${templateKey(event)}.$language is missing")

            val apiUrl = config.getString("api-url", DEFAULT_API_URL).trim().trimEnd('/')
            // Not corrected to the default: someone who set a self-hosted server must not have their messages go to the public one by a typo.
            if (!validEndpoint(apiUrl, needPath = false)) problem("api-url is not a valid https address")

            val asked = config.getInt("http-timeout-seconds", DEFAULT_HTTP_TIMEOUT_SECONDS)
            val timeout = asked.coerceIn(HTTP_TIMEOUT_RANGE)
            if (timeout != asked) warn("http-timeout-seconds: $asked is outside ${HTTP_TIMEOUT_RANGE.first} to ${HTTP_TIMEOUT_RANGE.last}; using $timeout")

            val keepAsked = config.getInt("ledger-keep-days", DEFAULT_LEDGER_KEEP_DAYS)
            val keep = keepAsked.coerceIn(LEDGER_KEEP_RANGE)
            if (keep != keepAsked) warn("ledger-keep-days: $keepAsked is outside ${LEDGER_KEEP_RANGE.first} to ${LEDGER_KEEP_RANGE.last}; using $keep")

            if (!ok) return null
            return TelegramSettings(token, chats, language, events, templates, apiUrl, timeout, keep)
        }
    }
}
