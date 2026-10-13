package xyz.felismp.shoparchive.plugins.telegram

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What a server answered: the status and the start of the body. */
class HttpAnswer(val status: Int, val body: String)

/** One field of a multipart form: text when [filename] is null, otherwise a file of [contentType]. */
class FormPart(val name: String, val bytes: ByteArray, val filename: String? = null, val contentType: String? = null) {
    constructor(name: String, text: String) : this(name, text.toByteArray(Charsets.UTF_8))
}

/**
 * Posts JSON, or a multipart form (for files). Behind an interface so the notifier is tested without a network. Throws [IOException] when there is no answer
 * ([java.net.http.HttpTimeoutException] for a timeout). The url may hold a secret (the bot token): an implementation never logs it or puts it in an exception message.
 */
interface HttpTransport {
    fun postJson(url: String, body: String, timeoutSeconds: Int): HttpAnswer

    fun postMultipart(url: String, parts: List<FormPart>, timeoutSeconds: Int): HttpAnswer
}

/** [parts] as a `multipart/form-data` body, parts separated by `--[boundary]`. Names and filenames are the plugin's own (letters, digits, `-`, `.`), never user text. */
fun multipartBody(boundary: String, parts: List<FormPart>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun line(text: String) = out.write("$text\r\n".toByteArray(Charsets.UTF_8))
    for (part in parts) {
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"${part.name}\"" + (part.filename?.let { "; filename=\"$it\"" } ?: ""))
        part.contentType?.let { line("Content-Type: $it") }
        line("")
        out.write(part.bytes)
        line("")
    }
    line("--$boundary--")
    return out.toByteArray()
}

/** The JDK's own client: no library. Redirects are not followed, so the token in the url is never sent anywhere else. */
class JdkHttpTransport : HttpTransport {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()

    override fun postJson(url: String, body: String, timeoutSeconds: Int): HttpAnswer =
        send(url, "application/json; charset=utf-8", HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8), timeoutSeconds)

    override fun postMultipart(url: String, parts: List<FormPart>, timeoutSeconds: Int): HttpAnswer {
        val boundary = "shoparchive-" + java.util.UUID.randomUUID().toString().replace("-", "")
        return send(url, "multipart/form-data; boundary=$boundary", HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, parts)), timeoutSeconds)
    }

    private fun send(url: String, contentType: String, body: HttpRequest.BodyPublisher, timeoutSeconds: Int): HttpAnswer {
        val request = try {
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
                .header("Content-Type", contentType).POST(body).build()
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
