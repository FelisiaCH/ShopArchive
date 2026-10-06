package xyz.felismp.shoparchive.plugins.discord

import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.plugin.PluginConfig
import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOOK = "https://discord.example/api/webhooks/1/SECRETPART"

private class Cfg(private val v: Map<String, Any>) : PluginConfig {
    override fun contains(path: String) = v.containsKey(path)
    override fun getString(path: String, default: String) = (v[path] as? String) ?: default
    override fun getInt(path: String, default: Int) = (v[path] as? String)?.toIntOrNull() ?: default
    override fun getLong(path: String, default: Long) = default
    override fun getDouble(path: String, default: Double) = default
    override fun getBoolean(path: String, default: Boolean) = default
    @Suppress("UNCHECKED_CAST") override fun getStringList(path: String) = (v[path] as? List<String>) ?: emptyList()
    override fun keys(section: String) = v.keys.toList()
}

private fun cfg(hook: String = HOOK) = Cfg(mapOf(
    "webhook-url" to hook, "language" to "en", "events" to listOf("entry.created"),
    "templates.entry-created.en" to "{type} {amount} {currency} #{code}",
))

private val n = Notification("id1", "20261003-A3F9C", "entry.created", "t", "main", "noy", "lo", mapOf("type" to "expense", "amount" to "5", "currency" to "LAK"))

private class T(val answer: () -> HttpAnswer) : HttpTransport {
    val posted = mutableListOf<Pair<String, String>>()
    override fun postJson(url: String, body: String, timeoutSeconds: Int): HttpAnswer { posted += url to body; return answer() }
}

class DiscordTest {
    private fun notifier(t: HttpTransport, hook: String = HOOK) = DiscordNotifier({ DiscordSettings.read(cfg(hook)) {} }, t)

    @Test fun blankWebhookGivesNoSettings() {
        val warns = mutableListOf<String>()
        assertNull(DiscordSettings.read(cfg(" "), { warns += it }))
        assertEquals(1, warns.size)
    }

    @Test fun anInvalidWebhookAddressIsNotUsedAndIsNotPrinted() {
        for (bad in listOf("https://", "https://bad host/hook/SECRETPART", "ftp://x.example/hook", "http://discord.example/hook/1", "https://discord.example", "https://discord.example/", "https://u:p@discord.example/hook/1")) {
            val warns = mutableListOf<String>()
            assertNull(DiscordSettings.read(cfg(bad)) { warns += it }, bad)
            assertTrue(warns.isNotEmpty() && warns.none { it.contains("SECRETPART") || it.contains("bad host") }, bad)
        }
        assertTrue(DiscordSettings.read(cfg("http://127.0.0.1:8080/hook/1/x")) {} != null)
        assertTrue(DiscordSettings.read(cfg("https://discord.example/api/webhooks/1/x")) {} != null)
    }

    @Test fun sentOn204AndBodyHasTheText() {
        val t = T { HttpAnswer(204, "") }
        assertTrue(notifier(t).deliver(n) is DeliveryResult.Sent)
        assertEquals(HOOK, t.posted.single().first)
        assertTrue(t.posted.single().second.contains("Expense 5 LAK #20261003-A3F9C"))
    }

    @Test fun mappingOfStatusesAndErrorsWithoutTheWebhook() {
        assertTrue(notifier(T { HttpAnswer(429, "") }).deliver(n) is DeliveryResult.Retry)
        assertTrue(notifier(T { HttpAnswer(503, "") }).deliver(n) is DeliveryResult.Retry)
        val failed = notifier(T { HttpAnswer(404, "") }).deliver(n)
        assertTrue(failed is DeliveryResult.Failed && !failed.reason.contains("SECRETPART"))
        val net = notifier(T { throw IOException("failed $HOOK") }).deliver(n)
        assertTrue(net is DeliveryResult.Retry && !net.reason.contains("SECRETPART"))
        assertFailsWith<TimeoutException> { notifier(T { throw java.net.http.HttpTimeoutException(HOOK) }).deliver(n) }
        assertFalse(false)
    }

    @Test
    fun aWebhookWithAPortOutOfRangeIsNotUsed() {
        for (bad in listOf("https://discord.example:99999/hook", "https://discord.example:0/hook", "https://discord.example:65536/hook")) {
            val warns = mutableListOf<String>()
            assertNull(DiscordSettings.read(cfg(bad)) { warns += it }, bad)
            assertTrue(warns.none { it.contains("99999") || it.contains("discord.example") })
        }
        assertTrue(DiscordSettings.read(cfg("https://discord.example:65535/hook")) {} != null)
        assertTrue(DiscordSettings.read(cfg("https://discord.example:8443/hook")) {} != null)
    }

    @Test
    fun plainHttpIsOnlyForARealLoopbackAddress() {
        for (good in listOf("http://localhost:8081/hook", "http://127.0.0.1:8081/hook", "http://[::1]:8081/hook"))
            assertTrue(DiscordSettings.read(cfg(good)) {} != null, good)
        for (bad in listOf("http://127.attacker.example/hook", "http://localhost.example/hook", "http://127.0.0.1.example/hook", "http://127.0.0.256/hook", "http://128.0.0.1/hook"))
            assertNull(DiscordSettings.read(cfg(bad)) {}, bad)
        val warns = mutableListOf<String>()
        DiscordSettings.read(cfg("http://127.attacker.example/hook")) { warns += it }
        assertTrue(warns.isNotEmpty() && warns.none { it.contains("attacker") })
    }
}
