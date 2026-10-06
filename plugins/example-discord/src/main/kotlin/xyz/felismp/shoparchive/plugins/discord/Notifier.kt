package xyz.felismp.shoparchive.plugins.discord

import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.Notifier
import java.io.IOException
import java.io.InterruptedIOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Posts a notification to a Discord webhook. 2xx is sent; 429, 5xx and no connection are tried again; other 4xx (a deleted webhook) are failed;
 * a wait that ran out is a [TimeoutException] (unknown, not a failure). Nothing it returns holds the webhook address: it is the secret.
 * One webhook is one destination, so there is nothing to remember between tries (a message whose answer never came may arrive twice; the code in it shows that).
 */
class DiscordNotifier(
    private val settings: () -> DiscordSettings?,
    private val transport: HttpTransport,
    private val renderer: TemplateRenderer = PlaceholderRenderer(),
) : Notifier {
    override fun deliver(notification: Notification): DeliveryResult {
        val s = settings() ?: return DeliveryResult.Retry("the Discord settings are incomplete; fix plugins/ExampleDiscord/config.yml and reload")
        if (notification.event !in s.events) return DeliveryResult.Sent
        val template = s.templates[notification.event]?.get(s.language) ?: return DeliveryResult.Failed("there is no template for ${notification.event} in ${s.language}")
        val body = """{"content":${jsonString(renderer.render(template, notification, s.language))},"allowed_mentions":{"parse":[]}}"""
        val status = try {
            transport.postJson(s.webhookUrl, body, s.httpTimeoutSeconds).status
        } catch (e: HttpTimeoutException) {
            throw TimeoutException("no answer from Discord")
        } catch (e: InterruptedIOException) {
            Thread.currentThread().interrupt(); throw TimeoutException("the wait was ended")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw TimeoutException("the wait was ended")
        } catch (e: IOException) {
            return DeliveryResult.Retry("no connection to Discord (${e.javaClass.simpleName})")
        }
        return when {
            status in 200..299 -> DeliveryResult.Sent
            status == 429 -> DeliveryResult.Retry("Discord says too many requests (429)")
            status >= 500 -> DeliveryResult.Retry("Discord is not working now ($status)")
            else -> DeliveryResult.Failed("Discord refused the message ($status): the webhook may be deleted or wrong")
        }
    }
}
