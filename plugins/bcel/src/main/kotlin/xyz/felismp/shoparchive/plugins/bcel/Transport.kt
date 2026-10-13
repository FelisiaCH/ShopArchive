package xyz.felismp.shoparchive.plugins.bcel

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What a server answered: the status and the start of the body. */
class HttpAnswer(val status: Int, val body: String)

/** Gets a page. Behind an interface so the checker is tested without a network. Throws [IOException] when there is no answer ([java.net.http.HttpTimeoutException] for a timeout). */
interface HttpTransport {
    fun get(url: String, timeoutSeconds: Int): HttpAnswer
}

/** The JDK's own client: no library. Redirects are not followed, so a slip's numbers are never sent anywhere else. */
class JdkHttpTransport : HttpTransport {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()

    override fun get(url: String, timeoutSeconds: Int): HttpAnswer {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds.toLong())).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return HttpAnswer(response.statusCode(), response.body().take(4000))
    }
}
