package xyz.felismp.shoparchive.plugins.discord

import xyz.felismp.shoparchive.api.plugin.PluginConfig

val LANGUAGES = listOf("lo", "th", "en")
val KNOWN_EVENTS = listOf("entry.created", "day.closed", "device.new")
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

class DiscordSettings(
    val webhookUrl: String,
    val language: String,
    val events: Set<String>,
    val templates: Map<String, Map<String, String>>,
    val httpTimeoutSeconds: Int,
) {
    companion object {
        val HTTP_TIMEOUT_RANGE = 2..25

        /** The settings, or null (with the reason given to [warn]) when the channel cannot work. A value that is only off is corrected with a warning. */
        fun read(config: PluginConfig, warn: (String) -> Unit): DiscordSettings? {
            var ok = true
            fun problem(text: String) { warn(text); ok = false }
            val url = config.getString("webhook-url", "").trim()
            if (url.isEmpty()) problem("webhook-url is empty")
            else if (!validEndpoint(url, needPath = true)) problem("webhook-url is not a valid https address of a webhook")
            var language = config.getString("language", "lo").trim().lowercase()
            if (language !in LANGUAGES) { warn("language: '$language' is not one of ${LANGUAGES.joinToString()}; using lo"); language = "lo" }
            val events = config.getStringList("events").map { it.trim() }.filter {
                (it in KNOWN_EVENTS).also { known -> if (!known && it.isNotEmpty()) warn("events: '$it' is not an event this channel sends; ignored") }
            }.toSet()
            if (events.isEmpty()) problem("events lists nothing to send")
            val templates = KNOWN_EVENTS.associateWith { e ->
                LANGUAGES.mapNotNull { l -> config.getString("templates.${templateKey(e)}.$l", "").takeIf { it.isNotBlank() }?.let { l to it } }.toMap()
            }
            for (e in events) if (language !in templates.getValue(e)) problem("templates.${templateKey(e)}.$language is missing")
            val asked = config.getInt("http-timeout-seconds", 10)
            val timeout = asked.coerceIn(HTTP_TIMEOUT_RANGE)
            if (timeout != asked) warn("http-timeout-seconds: $asked is outside ${HTTP_TIMEOUT_RANGE.first} to ${HTTP_TIMEOUT_RANGE.last}; using $timeout")
            return if (ok) DiscordSettings(url, language, events, templates, timeout) else null
        }
    }
}
