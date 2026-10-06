package xyz.felismp.shoparchive.plugins.discord

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What a server answered: the status and the start of the body. */
class HttpAnswer(val status: Int, val body: String)

/**
 * Posts JSON. Behind an interface so the notifier is tested without a network. Throws [IOException] when there is no answer
 * ([java.net.http.HttpTimeoutException] for a timeout). The url may hold a secret (the webhook token): an implementation never logs it or puts it in an exception message.
 */
interface HttpTransport {
    fun postJson(url: String, body: String, timeoutSeconds: Int): HttpAnswer
}

/** The JDK's own client: no library. Redirects are not followed, so the secret in the url is never sent anywhere else. */
class JdkHttpTransport : HttpTransport {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()

    override fun postJson(url: String, body: String, timeoutSeconds: Int): HttpAnswer {
        val request = try {
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)).build()
        } catch (e: IllegalArgumentException) {
            // The message of this one quotes the url.
            throw IOException("the address is not valid")
        }
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return HttpAnswer(response.statusCode(), response.body().take(2000))
    }
}

/** [text] as the inside of a JSON string. */
fun jsonString(text: String): String = buildString {
    append('"')
    for (c in text) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}
