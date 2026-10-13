package xyz.felismp.shoparchive.plugins.telegram

import com.sun.net.httpserver.HttpServer
import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.NotificationImages
import xyz.felismp.shoparchive.api.SlipFile
import xyz.felismp.shoparchive.api.plugin.PluginConfig
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "123456:SECRET-token-value"

private class MapConfig(private val v: Map<String, Any>) : PluginConfig {
    override fun contains(path: String) = v.containsKey(path)
    override fun getString(path: String, default: String) = (v[path] as? String) ?: default
    override fun getInt(path: String, default: Int) = (v[path] as? String)?.toIntOrNull() ?: default
    override fun getLong(path: String, default: Long) = default
    override fun getDouble(path: String, default: Double) = default
    override fun getBoolean(path: String, default: Boolean) = default
    @Suppress("UNCHECKED_CAST")
    override fun getStringList(path: String) = (v[path] as? List<String>) ?: emptyList()
    override fun keys(section: String) = v.keys.toList()
}

private fun config(vararg overrides: Pair<String, Any>): PluginConfig {
    val base = mutableMapOf<String, Any>(
        "bot-token" to TOKEN, "chat-ids" to listOf("111", "-100222"), "language" to "en", "events" to listOf("entry.created", "day.closed"),
        "templates.entry-created.lo" to "LO {type} {category} {item} {code}", "templates.entry-created.th" to "TH {type}", "templates.entry-created.en" to "EN {type} {amount} {currency} {category} {item}\n{user} {time} #{code}",
        "templates.day-closed.lo" to "lo", "templates.day-closed.th" to "th", "templates.day-closed.en" to "Closed {date} float {float} handover {handover} {note}",
    )
    overrides.forEach { base[it.first] = it.second }
    return MapConfig(base)
}

private fun note(id: String = "20261003-150000-001", event: String = "entry.created", fields: Map<String, String> = emptyMap()) =
    Notification(id, "20261003-A3F9C", event, "2026-10-03T15:00:00+07:00", "main", "noy", "lo", fields)

private val entryFields = mapOf("type" to "income", "category" to "", "item" to "coffee", "amount" to "150000", "currency" to "LAK", "user" to "noy", "time" to "2026-10-03T15:00:12+07:00")

/** The shipped config.yml as text: its `events:` list and the template of [event] in [language], read line by line. */
private val shipped: List<String> = TelegramTest::class.java.getResourceAsStream("/config.yml")!!.bufferedReader(Charsets.UTF_8).readLines()

private fun shippedEvents(): List<String> = shipped.drop(shipped.indexOf("events:") + 1).takeWhile { it.startsWith("  - ") }.map { it.removePrefix("  - ") }

private fun shippedTemplate(event: String, language: String): String {
    val block = shipped.indexOf("  ${templateKey(event)}:")
    assertTrue(block >= 0, "no templates.${templateKey(event)} in config.yml")
    val rest = shipped.drop(block + 1)
    return rest.drop(rest.indexOf("    $language: |-") + 1).takeWhile { it.startsWith("      ") }.joinToString("\n") { it.removePrefix("      ") }
}

private class FakeTransport(val answers: ArrayDeque<() -> HttpAnswer> = ArrayDeque()) : HttpTransport {
    val posted = mutableListOf<Pair<String, String>>()
    val forms = mutableListOf<Pair<String, List<FormPart>>>()

    /** The Bot API method of every request, in order, JSON or form. */
    val methods = mutableListOf<String>()

    override fun postJson(url: String, body: String, timeoutSeconds: Int): HttpAnswer {
        posted += url to body
        methods += url.substringAfterLast('/')
        return (answers.removeFirstOrNull() ?: { HttpAnswer(200, "{\"ok\":true}") })()
    }

    override fun postMultipart(url: String, parts: List<FormPart>, timeoutSeconds: Int): HttpAnswer {
        forms += url to parts
        methods += url.substringAfterLast('/')
        return (answers.removeFirstOrNull() ?: { HttpAnswer(200, "{\"ok\":true}") })()
    }
}

private class MemoryLedger : SentLedger {
    val data = mutableMapOf<String, MutableMap<String, String>>()
    override fun read(id: String) = data[id]?.toMap() ?: emptyMap()
    override fun want(id: String, chats: Collection<String>) {}
    override fun mark(id: String, chat: String, state: String) { data.getOrPut(id) { mutableMapOf() }[chat] = state }
    override fun prune(maxAgeDays: Int) {}
}

class TelegramTest {
    private fun notifier(t: HttpTransport, ledger: SentLedger = MemoryLedger(), cfg: PluginConfig = config()) =
        TelegramNotifier({ TelegramSettings.read(cfg) {} }, t, ledger)

    @Test
    fun successSendsToEveryChatWithAnEscapedJsonBody() {
        val t = FakeTransport()
        val result = notifier(t).deliver(note(fields = entryFields + ("item" to "a \"quoted\"\nitem")))
        assertTrue(result is DeliveryResult.Sent)
        assertEquals(2, t.posted.size)
        assertTrue(t.posted[0].first.endsWith("/bot$TOKEN/sendMessage"))
        assertTrue(t.posted[0].second.contains("\"chat_id\":\"111\""))
        assertTrue(t.posted[0].second.contains("a \\\"quoted\\\"\\nitem"), t.posted[0].second)
    }

    @Test
    fun status429IsRetryAnd401IsFailedAndTheTokenIsNotInTheReason() {
        val retry = notifier(FakeTransport(ArrayDeque(listOf({ HttpAnswer(429, "{\"description\":\"Too Many Requests: $TOKEN\"}") })))).deliver(note(fields = entryFields))
        assertTrue(retry is DeliveryResult.Retry)
        assertFalse(retry.reason.contains(TOKEN))
        val failed = notifier(FakeTransport(ArrayDeque(List(2) { { HttpAnswer(401, "{\"description\":\"Unauthorized\"}") } }))).deliver(note(fields = entryFields))
        assertTrue(failed is DeliveryResult.Failed)
        assertFalse(failed.reason.contains(TOKEN))
        assertTrue(failed.reason.contains("401"))
    }

    @Test
    fun networkErrorsAreRetryWithoutTheAddressAndTimeoutsAreTimeouts() {
        val boom = FakeTransport(ArrayDeque(listOf({ throw java.io.IOException("connect to https://api/bot$TOKEN failed") })))
        val r = notifier(boom).deliver(note(fields = entryFields))
        assertTrue(r is DeliveryResult.Retry)
        assertFalse(r.reason.contains(TOKEN))
        val slow = FakeTransport(ArrayDeque(listOf({ throw java.net.http.HttpTimeoutException("x $TOKEN") })))
        assertFailsWith<TimeoutException> { notifier(slow).deliver(note(fields = entryFields)) }
    }

    @Test
    fun aRetryDoesNotSendAgainToAChatThatAlreadyGotIt() {
        val ledger = MemoryLedger()
        val t = FakeTransport(ArrayDeque(listOf({ HttpAnswer(200, "") }, { HttpAnswer(502, "") })))
        val n = notifier(t, ledger)
        assertTrue(n.deliver(note(fields = entryFields)) is DeliveryResult.Retry)
        assertEquals(2, t.posted.size)
        assertTrue(n.deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(3, t.posted.size)
        assertTrue(t.posted[2].second.contains("-100222"))
        assertEquals(setOf("111", "-100222"), ledger.data.getValue("20261003-150000-001").keys)
    }

    @Test
    fun whenTheCoreTriesAFailedMessageAgainNobodyWhoAcceptedGetsItAgain() {
        val ledger = MemoryLedger()
        val t = FakeTransport(ArrayDeque(listOf({ HttpAnswer(200, "") }, { HttpAnswer(403, "") })))
        val n = notifier(t, ledger)
        val first = n.deliver(note(fields = entryFields))
        assertTrue(first is DeliveryResult.Failed)
        assertEquals("1 of 2 chat(s) refused the message (the bot may not write to this chat (403))", first.reason)
        assertEquals(2, t.posted.size)
        repeat(3) { assertTrue(n.deliver(note(fields = entryFields)) is DeliveryResult.Failed) }
        assertEquals(2, t.posted.size)
    }

    @Test
    fun oldLedgerFilesArePrunedByAge() {
        val dir = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).also { Files.createDirectories(it) }, "prune")
        val l = FileSentLedger(dir)
        l.mark("old", "1", "ok")
        l.mark("new", "1", "ok")
        Files.setLastModifiedTime(dir.resolve("old.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10 * 86_400_000L))
        l.prune(7)
        assertTrue(l.read("old").isEmpty())
        assertEquals(mapOf("1" to "ok"), l.read("new"))
    }

    @Test
    fun aLedgerWithAChatStillUnansweredIsNeverPrunedWhateverItsAge() {
        val dir = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).also { Files.createDirectories(it) }, "prune2")
        val ledger = FileSentLedger(dir)
        var now = 1_000_000_000_000L
        val t = FakeTransport(ArrayDeque(listOf({ HttpAnswer(200, "") }, { throw java.net.http.HttpTimeoutException("slow") })))
        val n = TelegramNotifier({ TelegramSettings.read(config()) {} }, t, ledger, clock = { now })
        assertFailsWith<TimeoutException> { n.deliver(note(fields = entryFields)) }
        assertEquals(2, t.posted.size)
        // Ten days pass (the file's own time is moved back, as the ledger reads it) and the hourly prune runs on the next delivery.
        Files.setLastModifiedTime(dir.resolve("20261003-150000-001.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10 * 86_400_000L))
        now += 2 * 3_600_000L
        assertTrue(n.deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(3, t.posted.size)
        assertTrue(t.posted[2].second.contains("-100222"))
    }

    @Test
    fun aCompleteLedgerIsPrunedOnlyWhenOldAndAChatThatNeverAnsweredKeepsItWhateverTheConfigSays() {
        val dir = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).also { Files.createDirectories(it) }, "prune3")
        val l = FileSentLedger(dir)
        val old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10 * 86_400_000L)
        for (id in listOf("gone", "fresh", "partial")) l.want(id, listOf("1", "2"))
        l.mark("gone", "1", "ok"); l.mark("gone", "2", "dead"); l.mark("fresh", "1", "ok"); l.mark("fresh", "2", "ok"); l.mark("partial", "1", "ok")
        for (id in listOf("gone", "partial")) Files.setLastModifiedTime(dir.resolve("$id.txt"), old)
        l.prune(7)
        assertTrue(l.read("gone").isEmpty())
        assertEquals(2, l.read("fresh").size)
        assertEquals(mapOf("1" to "ok"), l.read("partial"))
    }

    @Test
    fun removingAChatThatNeverAnsweredFromTheConfigDoesNotLetTheOthersGetTheMessageAgain() {
        // chat -100222 times out; the server stops for longer than ledger-keep-days; -100222 is taken out of the config; the core tries the message again
        val dir = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).also { Files.createDirectories(it) }, "prune4")
        val ledger = FileSentLedger(dir)
        var now = 1_000_000_000_000L
        val t = FakeTransport(ArrayDeque(listOf({ HttpAnswer(200, "") }, { throw java.net.http.HttpTimeoutException("slow") })))
        assertFailsWith<TimeoutException> { TelegramNotifier({ TelegramSettings.read(config()) {} }, t, ledger, clock = { now }).deliver(note(fields = entryFields)) }
        Files.setLastModifiedTime(dir.resolve("20261003-150000-001.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 10 * 86_400_000L))
        now += 2 * 3_600_000L
        val onlyFirst = TelegramNotifier({ TelegramSettings.read(config("chat-ids" to listOf("111"))) {} }, t, ledger, clock = { now })

        assertTrue(onlyFirst.deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(2, t.posted.size, "chat 111 already had it")
    }

    @Test
    fun aRefusedChatFailsTheNotificationOnlyAfterTheOthersGotIt() {
        val t = FakeTransport(ArrayDeque(listOf({ HttpAnswer(403, "") })))
        val r = notifier(t).deliver(note(fields = entryFields))
        assertTrue(r is DeliveryResult.Failed)
        assertEquals(2, t.posted.size)
    }

    @Test
    fun eventsNotListedAreSkippedAsSentWithoutPosting() {
        val t = FakeTransport()
        val r = notifier(t, cfg = config("events" to listOf("day.closed"))).deliver(note(fields = entryFields))
        assertTrue(r is DeliveryResult.Sent)
        assertTrue(t.posted.isEmpty())
    }

    @Test
    fun incompleteConfigGivesNoSettings() {
        val warns = mutableListOf<String>()
        assertNull(TelegramSettings.read(config("bot-token" to " ")) { warns += it })
        assertNull(TelegramSettings.read(config("chat-ids" to emptyList<String>())) { warns += it })
        assertNull(TelegramSettings.read(config("templates.entry-created.en" to "")) { warns += it })
        assertEquals(3, warns.size)
        assertNotNull(TelegramSettings.read(config("language" to "xx")) { warns += it })
    }

    @Test
    fun anInvalidApiUrlIsNotUsed() {
        for (bad in listOf("https://", "https://bad host", "ftp://x.example", "http://api.example", "api.telegram.org")) {
            val warns = mutableListOf<String>()
            assertNull(TelegramSettings.read(config("api-url" to bad)) { warns += it }, bad)
            assertTrue(warns.none { it.contains("bad host") })
        }
        assertNotNull(TelegramSettings.read(config("api-url" to "http://localhost:8081/")) {})
        assertNotNull(TelegramSettings.read(config("api-url" to "https://bot-api.example.com")) {})
    }

    @Test
    fun plainHttpIsOnlyForARealLoopbackAddress() {
        for (good in listOf("http://localhost:8081", "http://127.0.0.1:8081", "http://127.255.0.3", "http://[::1]:8081"))
            assertNotNull(TelegramSettings.read(config("api-url" to good)) {}, good)
        for (bad in listOf("http://127.attacker.example", "http://localhost.example", "http://127.0.0.1.example", "http://127.0.0.256", "http://127.1", "http://127.0.0.01", "http://128.0.0.1", "http://[::2]"))
            assertNull(TelegramSettings.read(config("api-url" to bad)) {}, bad)
        val warns = mutableListOf<String>()
        TelegramSettings.read(config("api-url" to "http://127.attacker.example")) { warns += it }
        assertTrue(warns.none { it.contains("attacker") })
        assertNotNull(TelegramSettings.read(config("api-url" to "https://127.attacker.example")) {})
    }

    @Test
    fun anApiUrlWithAPortOutOfRangeIsNotUsed() {
        for (bad in listOf("https://api.example:99999", "https://api.example:0", "https://api.example:65536")) {
            val warns = mutableListOf<String>()
            assertNull(TelegramSettings.read(config("api-url" to bad)) { warns += it }, bad)
            assertTrue(warns.none { it.contains("99999") })
        }
        assertNotNull(TelegramSettings.read(config("api-url" to "https://api.example:65535")) {})
        assertNotNull(TelegramSettings.read(config("api-url" to "https://api.example:8443")) {})
    }

    @Test
    fun templatesRenderPerLanguageAndAnEmptyCategoryLeavesNoBlankLine() {
        val r = PlaceholderRenderer()
        val n = note(fields = entryFields)
        assertEquals("EN 🟢 Income 150000 LAK coffee\nnoy 2026-10-03 15:00 #20261003-A3F9C", r.render("EN {type} {amount} {currency} {category} {item}\n{user} {time} #{code}", n, "en"))
        assertEquals("🟢 รายรับ", r.render("{type}", n, "th"))
        assertEquals("🟢 ລາຍຮັບ", r.render("{type}", n, "lo"))
        assertEquals("a\nb", r.render("a\n{category}\nb", n, "en"))
        val day = note(event = "day.closed", fields = mapOf("date" to "2026-10-03", "float" to "LAK 100000", "handover" to "LAK 400000", "note" to ""))
        assertEquals("Closed 2026-10-03 float LAK 100000 handover LAK 400000", r.render("Closed {date} float {float} handover {handover} {note}", day, "en"))
        assertEquals("{x}", r.render("{v}", note(fields = mapOf("v" to "{x}")), "en"))
    }

    @Test
    fun theShippedEntryTemplateLeadsWithTheMarkTheWordAndTheSignedMoney() {
        val r = PlaceholderRenderer()
        val income = note(fields = entryFields + mapOf("money" to "+150.000 LAK", "branch" to "Main Shop", "category" to "ເຄື່ອງດື່ມ"))
        assertEquals("🟢 ລາຍຮັບ +150.000 LAK\nເຄື່ອງດື່ມ coffee\nMain Shop · noy · 2026-10-03 15:00\nລະຫັດ 20261003-A3F9C", r.render(shippedTemplate("entry.created", "lo"), income, "lo"))
        val expense = note(fields = entryFields + mapOf("type" to "expense", "money" to "-50,000 LAK, -20.50 THB", "branch" to "Main Shop", "note" to "for the cooler"))
        assertEquals("🔴 Expense -50,000 LAK, -20.50 THB\ncoffee\n📝 for the cooler\nMain Shop · noy · 2026-10-03 15:00\nCode 20261003-A3F9C", r.render(shippedTemplate("entry.created", "en"), expense, "en"))
    }

    @Test
    fun theShippedDeviceNewTemplateRendersAndAConfigListingItIsAcceptedWithoutAWarning() {
        val n = note(event = "device.new", fields = mapOf("user" to "noy", "device" to "Phone of noy", "platform" to "android", "deviceId" to "4f1c2d3e-0000-4000-8000-00000000a3f9c"))
        val r = PlaceholderRenderer()
        assertEquals("New device: noy logged in on Phone of noy (android)", r.render(shippedTemplate("device.new", "en"), n, "en"))
        for (language in LANGUAGES) {
            val text = r.render(shippedTemplate("device.new", language), n, language)
            assertTrue("noy" in text && "Phone of noy" in text && "(android)" in text, "$language: $text")
        }
        assertTrue("device.new" in shippedEvents(), shippedEvents().toString())

        val warns = mutableListOf<String>()
        val templates = LANGUAGES.map { "templates.device-new.$it" to shippedTemplate("device.new", it) }.toTypedArray()
        val cfg = config("events" to listOf("entry.created", "day.closed", "device.new"), *templates)
        assertEquals(setOf("entry.created", "day.closed", "device.new"), TelegramSettings.read(cfg) { warns += it }!!.events)
        assertEquals(emptyList(), warns)
        val t = FakeTransport()
        assertTrue(notifier(t, cfg = cfg).deliver(n) is DeliveryResult.Sent)
        assertTrue(t.posted[0].second.contains("New device: noy logged in on Phone of noy (android)"), t.posted[0].second)
    }

    @Test
    fun theFileLedgerSurvivesAndClears() {
        val dir = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).also { Files.createDirectories(it) }, "ledger")
        val l = FileSentLedger(dir.resolve("pending"))
        l.mark("a/1", "111", "ok")
        l.mark("a/1", "222", "dead")
        assertEquals(mapOf("111" to "ok", "222" to "dead"), FileSentLedger(dir.resolve("pending")).read("a/1"))
    }

    @Test
    fun theRealHttpTransportAgainstAFakeBotApi() {
        val seen = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += ex.requestURI.path + " " + String(ex.requestBody.readAllBytes(), Charsets.UTF_8)
            val bad = ex.requestURI.path.contains("BAD")
            val out = (if (bad) "{\"ok\":false,\"description\":\"Unauthorized\"}" else "{\"ok\":true}").toByteArray()
            ex.sendResponseHeaders(if (bad) 401 else 200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val ok = TelegramNotifier({ TelegramSettings.read(config("api-url" to url)) {} }, JdkHttpTransport(), MemoryLedger())
            assertTrue(ok.deliver(note(fields = entryFields)) is DeliveryResult.Sent)
            val bad = TelegramNotifier({ TelegramSettings.read(config("api-url" to url, "bot-token" to "1:BAD")) {} }, JdkHttpTransport(), MemoryLedger())
            val r = bad.deliver(note(fields = entryFields))
            assertTrue(r is DeliveryResult.Failed && !r.reason.contains("1:BAD"))
            assertEquals(4, seen.size)
        } finally { server.stop(0) }
    }
}

private fun slip(n: Int, png: Boolean = false) = SlipFile(ByteArray(8) { (n * 10 + it).toByte() }, if (png) "image/png" else "image/jpeg")

private fun FormPart.text() = String(bytes, Charsets.UTF_8)

private fun List<FormPart>.field(name: String) = single { it.name == name }

private const val RENDERED = "EN 🟢 Income 150000 LAK coffee\nnoy 2026-10-03 15:00 #20261003-A3F9C"

class TelegramImagesTest {
    private fun notifier(t: HttpTransport, pictures: List<SlipFile>, ledger: SentLedger = MemoryLedger(), cfg: PluginConfig = config("chat-ids" to listOf("111"))) =
        TelegramNotifier({ TelegramSettings.read(cfg) {} }, t, ledger, images = { NotificationImages { pictures } })

    @Test
    fun withoutImagesTheSendMessageBodyIsUnchanged() {
        val t = FakeTransport()
        assertTrue(notifier(t, emptyList()).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(
            listOf("{\"chat_id\":\"111\",\"text\":\"EN 🟢 Income 150000 LAK coffee\\nnoy 2026-10-03 15:00 #20261003-A3F9C\",\"disable_web_page_preview\":true}"),
            t.posted.map { it.second },
        )
        assertTrue(t.forms.isEmpty())
    }

    @Test
    fun noImageServiceOrOneThatThrowsMeansNoImages() {
        val t = FakeTransport()
        assertTrue(TelegramNotifier({ TelegramSettings.read(config()) {} }, t, MemoryLedger()).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendMessage", "sendMessage"), t.methods)
        val boom = FakeTransport()
        val n = TelegramNotifier({ TelegramSettings.read(config()) {} }, boom, MemoryLedger(), images = { NotificationImages { throw IllegalStateException("broken") } })
        assertTrue(n.deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendMessage", "sendMessage"), boom.methods)
    }

    @Test
    fun oneImageIsASendPhotoWithTheTextAsCaption() {
        val t = FakeTransport()
        assertTrue(notifier(t, listOf(slip(1)), cfg = config()).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendPhoto", "sendPhoto"), t.methods)
        assertTrue(t.posted.isEmpty())
        val (url, parts) = t.forms[0]
        assertTrue(url.endsWith("/bot$TOKEN/sendPhoto"))
        assertEquals(listOf("chat_id", "caption", "photo"), parts.map { it.name })
        assertEquals("111", parts.field("chat_id").text())
        assertEquals(RENDERED, parts.field("caption").text())
        val photo = parts.field("photo")
        assertEquals("slip-1.jpg", photo.filename)
        assertEquals("image/jpeg", photo.contentType)
        assertContentEquals(slip(1).bytes, photo.bytes)
        assertEquals("-100222", t.forms[1].second.field("chat_id").text())
    }

    @Test
    fun severalImagesAreASendMediaGroupWithTheCaptionOnTheFirstPhoto() {
        val t = FakeTransport()
        assertTrue(notifier(t, listOf(slip(1), slip(2, png = true), slip(3))).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendMediaGroup"), t.methods)
        val parts = t.forms.single().second
        assertEquals(listOf("chat_id", "media", "photo0", "photo1", "photo2"), parts.map { it.name })
        val caption = RENDERED.replace("\n", "\\n")
        assertEquals(
            """[{"type":"photo","media":"attach://photo0","caption":"$caption"},{"type":"photo","media":"attach://photo1"},{"type":"photo","media":"attach://photo2"}]""",
            parts.field("media").text(),
        )
        val files = parts.filter { it.name.startsWith("photo") }
        assertEquals(listOf("slip-1.jpg", "slip-2.png", "slip-3.jpg"), files.map { it.filename })
        assertEquals(listOf("image/jpeg", "image/png", "image/jpeg"), files.map { it.contentType })
        assertContentEquals(slip(2, png = true).bytes, parts.field("photo1").bytes)
    }

    @Test
    fun moreThanTenImagesGoInGroupsOfTenWithTheCaptionOnlyOnTheVeryFirst() {
        val t = FakeTransport()
        assertTrue(notifier(t, (1..11).map { slip(it) }).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendMediaGroup", "sendPhoto"), t.methods)
        val group = t.forms[0].second
        assertEquals(10, group.count { it.name.startsWith("photo") })
        assertEquals(1, Regex("\"caption\"").findAll(group.field("media").text()).count())
        val last = t.forms[1].second
        assertEquals(listOf("chat_id", "photo"), last.map { it.name })
        assertEquals("slip-11.jpg", last.field("photo").filename)
        assertContentEquals(slip(11).bytes, last.field("photo").bytes)

        val twenty = FakeTransport()
        assertTrue(notifier(twenty, (1..20).map { slip(it) }).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendMediaGroup", "sendMediaGroup"), twenty.methods)
        assertFalse(twenty.forms[1].second.field("media").text().contains("caption"))
        assertEquals("attach://photo10", Regex("attach://photo\\d+").find(twenty.forms[1].second.field("media").text())!!.value)
    }

    @Test
    fun aTextOverTheCaptionLimitIsAMessageAndTheImagesFollowWithoutCaption() {
        val cfg = config("chat-ids" to listOf("111"), "templates.entry-created.en" to "x".repeat(1020) + "{item}")
        val t = FakeTransport()
        assertTrue(notifier(t, listOf(slip(1), slip(2)), cfg = cfg).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendMessage", "sendMediaGroup"), t.methods)
        assertTrue(t.posted.single().second.contains("x".repeat(1020) + "coffee"))
        assertFalse(t.forms.single().second.field("media").text().contains("caption"))

        val one = FakeTransport()
        assertTrue(notifier(one, listOf(slip(1)), cfg = cfg).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendMessage", "sendPhoto"), one.methods)
        assertEquals(listOf("chat_id", "photo"), one.forms.single().second.map { it.name })

        // exactly 1024 characters still fits as a caption
        val fits = FakeTransport()
        val exact = config("chat-ids" to listOf("111"), "templates.entry-created.en" to "y".repeat(1024))
        assertTrue(notifier(fits, listOf(slip(1)), cfg = exact).deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendPhoto"), fits.methods)
    }

    @Test
    fun aFailingPhotoRequestIsRetriedForThatChatOnlyAndTheChatIsOkOnlyWhenItWorked() {
        val ledger = MemoryLedger()
        val t = FakeTransport(ArrayDeque(listOf({ HttpAnswer(200, "") }, { HttpAnswer(502, "") })))
        val n = notifier(t, listOf(slip(1)), ledger, config())
        assertTrue(n.deliver(note(fields = entryFields)) is DeliveryResult.Retry)
        assertEquals(mapOf("111" to "ok"), ledger.read("20261003-150000-001"))
        assertTrue(n.deliver(note(fields = entryFields)) is DeliveryResult.Sent)
        assertEquals(listOf("sendPhoto", "sendPhoto", "sendPhoto"), t.methods)
        assertEquals("-100222", t.forms[2].second.field("chat_id").text())
        assertEquals(mapOf("111" to "ok", "-100222" to "ok"), ledger.read("20261003-150000-001"))
    }

    @Test
    fun theMessageOfALongTextDoesNotMakeAChatOkWhenItsPhotosFailed() {
        val ledger = MemoryLedger()
        val cfg = config("chat-ids" to listOf("111"), "templates.entry-created.en" to "z".repeat(1100))
        val t = FakeTransport(ArrayDeque(listOf({ HttpAnswer(200, "") }, { HttpAnswer(429, "{\"description\":\"Too Many Requests: $TOKEN\"}") })))
        val r = notifier(t, listOf(slip(1)), ledger, cfg).deliver(note(fields = entryFields))
        assertTrue(r is DeliveryResult.Retry)
        assertFalse(r.reason.contains(TOKEN))
        assertEquals(listOf("sendMessage", "sendPhoto"), t.methods)
        assertTrue(ledger.read("20261003-150000-001").isEmpty())
    }

    @Test
    fun aPhotoTheChatRefusesFailsTheNotificationAndTimeoutsAndNetworkErrorsKeepTheirMeaning() {
        val refused = FakeTransport(ArrayDeque(listOf({ HttpAnswer(400, "{\"description\":\"Bad Request: PHOTO_INVALID_DIMENSIONS\"}") })))
        val ledger = MemoryLedger()
        assertTrue(notifier(refused, listOf(slip(1)), ledger).deliver(note(fields = entryFields)) is DeliveryResult.Failed)
        assertEquals(mapOf("111" to "dead"), ledger.read("20261003-150000-001"))
        val slow = FakeTransport(ArrayDeque(listOf({ throw java.net.http.HttpTimeoutException("x $TOKEN") })))
        assertFailsWith<TimeoutException> { notifier(slow, listOf(slip(1), slip(2))).deliver(note(fields = entryFields)) }
        val down = FakeTransport(ArrayDeque(listOf({ throw java.io.IOException("connect to https://api/bot$TOKEN failed") })))
        val again = notifier(down, listOf(slip(1))).deliver(note(fields = entryFields))
        assertTrue(again is DeliveryResult.Retry && !again.reason.contains(TOKEN))
    }

    @Test
    fun theMultipartBodyHasBoundariesFilenamesAndContentTypes() {
        val body = multipartBody("BND", listOf(FormPart("chat_id", "111"), FormPart("photo", byteArrayOf(1, 2, 3), "slip-1.jpg", "image/jpeg")))
        val head = "--BND\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n111\r\n" +
            "--BND\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"slip-1.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
        assertContentEquals(head.toByteArray(Charsets.UTF_8) + byteArrayOf(1, 2, 3) + "\r\n--BND--\r\n".toByteArray(Charsets.UTF_8), body)
    }

    @Test
    fun theRealHttpTransportSendsAMultipartFormWithItsBoundary() {
        var contentType = ""
        var body = ByteArray(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            contentType = ex.requestHeaders.getFirst("Content-Type")
            body = ex.requestBody.readAllBytes()
            val out = "{\"ok\":true}".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val parts = listOf(FormPart("chat_id", "111"), FormPart("photo", byteArrayOf(9, 8, 7), "slip-1.jpg", "image/jpeg"))
            val answer = JdkHttpTransport().postMultipart("http://127.0.0.1:${server.address.port}/botX/sendPhoto", parts, 5)
            assertEquals(200, answer.status)
            assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType)
            assertContentEquals(multipartBody(contentType.substringAfter("boundary="), parts), body)
        } finally { server.stop(0) }
    }
}
