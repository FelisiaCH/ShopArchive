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

private fun cfg(hook: String = HOOK, vararg extra: Pair<String, Any>) = Cfg(mapOf(
    "webhook-url" to hook, "language" to "en", "events" to listOf("entry.created"),
    "templates.entry-created.en" to "{type} {amount} {currency} #{code}",
) + extra)

private val n = Notification("id1", "20261003-A3F9C", "entry.created", "t", "main", "noy", "lo", mapOf("type" to "expense", "amount" to "5", "currency" to "LAK"))

/** The shipped config.yml as text: its `events:` list and the template of [event] in [language], read line by line. */
private val shipped: List<String> = DiscordTest::class.java.getResourceAsStream("/config.yml")!!.bufferedReader(Charsets.UTF_8).readLines()

private fun shippedEvents(): List<String> = shipped.drop(shipped.indexOf("events:") + 1).takeWhile { it.startsWith("  - ") }.map { it.removePrefix("  - ") }

private fun shippedTemplate(event: String, language: String): String {
    val block = shipped.indexOf("  ${templateKey(event)}:")
    assertTrue(block >= 0, "no templates.${templateKey(event)} in config.yml")
    val rest = shipped.drop(block + 1)
    return rest.drop(rest.indexOf("    $language: |-") + 1).takeWhile { it.startsWith("      ") }.joinToString("\n") { it.removePrefix("      ") }
}

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
    fun theShippedDeviceNewTemplateRendersAndAConfigListingItIsAcceptedWithoutAWarning() {
        val device = Notification("id2", "20261003-A3F9C", "device.new", "t", "main", "noy", "lo", mapOf("user" to "noy", "device" to "Phone of noy", "platform" to "android", "deviceId" to "d-a3f9c"))
        val r = PlaceholderRenderer()
        assertEquals("New device: noy logged in on Phone of noy (android)", r.render(shippedTemplate("device.new", "en"), device, "en"))
        for (language in LANGUAGES) {
            val text = r.render(shippedTemplate("device.new", language), device, language)
            assertTrue("noy" in text && "Phone of noy" in text && "(android)" in text, "$language: $text")
        }
        assertTrue("device.new" in shippedEvents(), shippedEvents().toString())

        val warns = mutableListOf<String>()
        val templates = LANGUAGES.map { "templates.device-new.$it" to shippedTemplate("device.new", it) }.toTypedArray()
        val config = cfg(HOOK, "events" to listOf("entry.created", "device.new"), *templates)
        assertEquals(setOf("entry.created", "device.new"), DiscordSettings.read(config) { warns += it }!!.events)
        assertEquals(emptyList(), warns)
        val t = T { HttpAnswer(204, "") }
        assertTrue(DiscordNotifier({ DiscordSettings.read(config) {} }, t).deliver(device) is DeliveryResult.Sent)
        assertTrue(t.posted.single().second.contains("New device: noy logged in on Phone of noy (android)"), t.posted.single().second)
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
