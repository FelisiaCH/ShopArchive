package xyz.felismp.shoparchive.server.auth

import io.ktor.client.request.header
import xyz.felismp.shoparchive.server.DataBarrier
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.CertificateService
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.CommandService
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.api.PairingService
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.api.UpdateService
import xyz.felismp.shoparchive.server.net.DefaultUpdateService
import xyz.felismp.shoparchive.server.notify.DefaultShopEvents
import xyz.felismp.shoparchive.server.notify.FakeDeliverer
import xyz.felismp.shoparchive.server.notify.Notify
import xyz.felismp.shoparchive.server.notify.registerNotifyCommand
import xyz.felismp.shoparchive.server.notify.registerNotifyNodes
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.DefaultCommandService
import xyz.felismp.shoparchive.server.registerCommandNodes
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.write
import xyz.felismp.shoparchive.server.net.apiModule
import xyz.felismp.shoparchive.server.net.registerSayCommand
import xyz.felismp.shoparchive.server.records.Records
import xyz.felismp.shoparchive.server.records.registerRecordCommands
import xyz.felismp.shoparchive.server.records.registerRecordNodes
import xyz.felismp.shoparchive.server.registerCoreCommands
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.server.users.registerUserCommands
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.PairPayload
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

/** A clock the test moves by hand. */
internal class TestClock(var now: Instant = Instant.parse("2026-10-03T08:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = zoned(zone)
    override fun instant(): Instant = now
    fun advance(by: Duration) {
        now = now.plus(by)
    }

    /** The same moving time in another zone. */
    private fun zoned(zone: ZoneId): Clock {
        val source = this
        return object : Clock() {
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = source.zoned(zone)
            override fun instant(): Instant = source.now
        }
    }
}

internal class RecordingSender : CommandSender {
    override val name = "test"
    val messages = mutableListOf<String>()
    override fun sendMessage(message: String) {
        messages += message
    }
}

internal const val NO_BACKOFF = "config-version: 1\nauth:\n  backoff:\n    start-seconds: 0\n"

/**
 * [config] with `auth.password.required-for` set to the nodes most tests rely on (the shipped default is empty: PIN alone), unless it sets that list itself.
 * A test that wants nobody to need a password says `required-for: []`.
 */
internal fun withPasswordNodes(config: String): String {
    if ("required-for" in config) return config
    val nodes = "    required-for: [op, shoparchive.users.manage, shoparchive.branches.manage, shoparchive.devices.revoke]\n"
    return when {
        "\n  password:\n" in config -> config.replace("\n  password:\n", "\n  password:\n" + nodes)
        "\nauth:\n" in config -> config.replace("\nauth:\n", "\nauth:\n  password:\n" + nodes)
        else -> config + "auth:\n  password:\n" + nodes
    }
}

internal const val FINGERPRINT = "AB12 CD34 EF56 AB12 CD34 EF56 AB12 CD34 EF56 AB12 CD34 EF56 AB12 CD34 EF56 AB12"
internal val SERVER_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

/** Cheap Argon2 for tests; the real cost is for the real server. */
internal val TEST_COST = Argon2Cost(1024, 1, 1)

/**
 * The whole server side of login without the network listener: config, users, devices, services and commands on a
 * temporary root. [config] is the text of `config/shoparchive.yml` (the rest stays default).
 */
internal class AuthEnv(
    val root: Path,
    config: String? = null,
    val clock: TestClock = TestClock(),
    hasher: Hasher? = null,
    /** Also the records code: branches, categories, day sessions and entries, with their nodes and console commands. */
    withRecords: Boolean = false,
    /** Also the notifications: the events, the outbox (listening, but its worker not started: a test calls `notify.outbox.processDue()`), their nodes, routes and console command. Implies [withRecords]. */
    withNotify: Boolean = false,
    val deliverer: FakeDeliverer = FakeDeliverer(),
) {
    val log = RecordingLog()
    val nodes = Permissions().apply {
        registerAuthNodes(this)
        register(PermissionNode("test.view", "View", default = true))
        registerCommandNodes(this)
        if (withRecords || withNotify) registerRecordNodes(this)
        if (withNotify) registerNotifyNodes(this)
    }
    val barrier = DataBarrier()
    val services = Services()
    val settings: ConfigService
    val users: UserStore
    val records: Records?
    val notify: Notify?
    /** Plugin listeners run on the publishing thread here, so a test sees their effect when the request is answered. */
    val events: DefaultShopEvents = DefaultShopEvents(Runnable::run)
    val auth: Auth
    val commands = Commands()
    val sender = RecordingSender()

    /** The device each user signed in on through [xyz.felismp.shoparchive.server.records.login], so a second sign-in unlocks the same one. */
    val loginDevices = HashMap<String, EnrollResponse>()

    /** What the console printed for the secrets: the pairing text, one entry per line. */
    val terminal = mutableListOf<String>()

    /** Shows pairings like the server's console does, printing to [terminal]. */
    val pairingConsole: PairingConsole

    init {
        prepareRoot(root)
        // Most tests make many wrong tries in a row and must not wait between them; the backoff tests say so in their own config.
        if (config != null) root.write("config/shoparchive.yml", withPasswordNodes(config))
        else if (!Files.exists(root.resolve("config/shoparchive.yml"))) root.write("config/shoparchive.yml", withPasswordNodes(NO_BACKOFF))
        settings = ConfigService(root, log = log, clock = clock, barrier = barrier).also { it.load() }
        users = UserStore(root, nodes, settings, log, clock, barrier = barrier).also { it.load() }
        services.register(InfoService::class.java, object : InfoService {
            override fun info() = InfoResponse(SERVER_ID.toString(), "Test Shop", "hello", "9.9", PROTOCOL_VERSION)
        }, 0, "test")
        services.register(IpBanService::class.java, object : IpBanService { override fun isBanned(ip: String) = false }, 0, "test")
        services.register(UpdateService::class.java, DefaultUpdateService(root.resolve("downloads")), 0, "core")
        services.register(CertificateService::class.java, object : CertificateService {
            override val fingerprint = FINGERPRINT
            override val expiresAt: Instant = Instant.parse("2027-01-01T00:00:00Z")
        }, 0, "test")
        records = if (withRecords || withNotify) Records(root, settings, users, services, log, clock, barrier = barrier).also { it.load() } else null
        services.register(ShopEvents::class.java, events, 0, "core")
        notify = if (withNotify) Notify(root, settings, services, records!!::closedDay, records::eventsSince, clock, deliverer, barrier).also { it.listen() } else null
        auth = Auth(
            root, settings, users, services, SERVER_ID, { listOf("shop.example.com:25655", "192.168.1.20:25655") }, clock, TEST_COST,
            hasher ?: Hasher(settings.auth.hashConcurrency, TEST_COST), records ?: NoRecords, barrier,
        )
        services.register(CommandService::class.java, DefaultCommandService(commands, auth.audit, services), 0, "core")
        pairingConsole = PairingConsole(root, settings, auth.pairing) { terminal += it }
        registerCoreCommands(
            commands, settings, mapOf("devices" to auth.devices::load) + (if (records != null) mapOf("data" to records::loadData) else emptyMap()),
            alsoOnReload = setOf("devices"),
        )
        val accounts = AccountConsole(users, auth.devices, auth.sessions, auth.audit, barrier)
        registerUserCommands(commands, users, accounts::reset, accounts::disable)
        accounts.register(commands)
        registerSayCommand(commands, services)
        if (records != null) registerRecordCommands(commands, records)
        if (notify != null) registerNotifyCommand(commands, notify.outbox)
    }

    /** Runs a console command and returns what it said to the sender. */
    fun console(line: String): List<String> {
        sender.messages.clear()
        commands.run(sender, line)
        return sender.messages.toList()
    }

    /** Shows a pairing for [name] as the console does and returns what it said to the sender. */
    fun showPairing(name: String, png: Boolean = false): List<String> {
        sender.messages.clear()
        pairingConsole.show(sender, name, png)
        return sender.messages.toList()
    }

    /** What an admin does on a running server: writes [config] as the constructor does and reloads it. */
    fun reconfigure(config: String) {
        root.write("config/shoparchive.yml", withPasswordNodes(config))
        settings.reload()
    }

    /** A user, optionally an op, made through the console. */
    fun addUser(name: String, op: Boolean = false) {
        users.addUser(name, "none", emptyList())
        if (op) users.setOp(name, true)
    }

    /** A new pairing from the console and what it holds. */
    fun pair(name: String): Pairing {
        val created = auth.pairing.createPairing(null, name, "127.0.0.1")
        return Pairing(created.response.link, created.response.manualCode)
    }

    /** Everything stored under [relative] below the root, as text. */
    fun filesUnder(relative: String): Map<String, String> {
        val base = root.resolve(relative)
        if (!Files.exists(base)) return emptyMap()
        return Files.walk(base).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().associate { base.relativize(it).toString() to Files.readString(it) }
        }
    }

    fun audit(): String = filesUnder("data/audit").values.joinToString("\n")
}

/**
 * What an admin does with a text editor: deletes [name]'s block from the device [file] and saves it. The modified time
 * moves on by five seconds, so that no coarse file system clock can make the edit look like no edit; the time it had is returned.
 */
internal fun removeUserBlock(file: Path, name: String): FileTime {
    val before = Files.getLastModifiedTime(file)
    val text = Files.readString(file)
    val changed = text.replace(Regex("(?m)^  ${Regex.escape(name)}:\\n(    .*\\n)+"), "")
    check(changed != text) { "no block of $name in $file" }
    Files.writeString(file, changed)
    Files.setLastModifiedTime(file, FileTime.fromMillis(before.toMillis() + 5_000))
    return before
}

internal class Pairing(val link: String, val manualCode: String?) {
    val payload: PairPayload = Json.decodeFromString(
        PairPayload.serializer(),
        String(Base64.getUrlDecoder().decode(link.removePrefix("shoparchive://pair?d="))),
    )
    val secret: String get() = payload.sec
}

internal val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal suspend fun <T> HttpResponse.parsed(serializer: KSerializer<T>): T = testJson.decodeFromString(serializer, bodyAsText())

internal suspend fun HttpResponse.errorCode(): ErrorCode = parsed(ErrorResponse.serializer()).code

internal suspend fun HttpResponse.errorReason(): String? = parsed(ErrorResponse.serializer()).reason

/** Runs [block] against the real API module on the env's services. */
internal fun AuthEnv.api(rateLimitPerMinute: Int = 1000, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
    application { apiModule(services, 1024 * 1024, rateLimitPerMinute = rateLimitPerMinute, entryBodyBytes = { settings.records.entryBodyBytes(1024 * 1024) }) }
    block()
}

internal suspend fun <B> ApplicationTestBuilder.postJson(
    path: String, serializer: KSerializer<B>, body: B, token: String? = null,
): HttpResponse = client.post(path) {
    header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    contentType(ContentType.Application.Json)
    setBody(testJson.encodeToString(serializer, body))
}

internal suspend fun ApplicationTestBuilder.postRaw(path: String, text: String, token: String? = null): HttpResponse = client.post(path) {
    header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    contentType(ContentType.Application.Json)
    setBody(text)
}

internal suspend fun <B> ApplicationTestBuilder.putJson(path: String, serializer: KSerializer<B>, body: B, token: String? = null): HttpResponse = client.put(path) {
    header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    contentType(ContentType.Application.Json)
    setBody(testJson.encodeToString(serializer, body))
}

internal suspend fun ApplicationTestBuilder.deletePath(path: String, token: String? = null): HttpResponse = client.delete(path) {
    header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
}

internal suspend fun ApplicationTestBuilder.getPath(path: String, token: String? = null): HttpResponse = client.get(path) {
    header(PROTOCOL_HEADER, "$PROTOCOL_VERSION")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
}

// --- people through the services, for tests of policy (the HTTP side of login is AuthApiTest's) ---

internal const val TEST_PIN = "482915"
internal const val TEST_PASSWORD = "Correct-Horse-Battery-9"

internal fun AuthEnv.authService() = services.get(AuthService::class.java)!!
internal fun AuthEnv.pairingService() = services.get(PairingService::class.java)!!

/** A pairing for [name] (made if the user is new), redeemed and enrolled: the PIN (and the password if the policy asks) are the test ones. [onto] adds the user to a shared device. */
internal fun AuthEnv.enroll(name: String, mode: DeviceMode = DeviceMode.PERSONAL, onto: EnrollResponse? = null): EnrollResponse {
    if (name !in users.userNames()) addUser(name)
    val redeemed = pairingService().redeem(RedeemRequest(secret = pair(name).secret), "127.0.0.1")
    return authService().enroll(
        redeemed.enrollmentToken,
        EnrollRequest(
            "Phone of $name", "android", mode,
            password = TEST_PASSWORD.takeIf { redeemed.hasPassword }, newPassword = TEST_PASSWORD.takeIf { redeemed.passwordRequired && !redeemed.hasPassword },
            pin = TEST_PIN.takeIf { redeemed.hasPin }, newPin = TEST_PIN.takeUnless { redeemed.hasPin },
            deviceId = onto?.deviceId, deviceCredential = onto?.credential,
        ),
        "127.0.0.1",
    )
}

internal fun AuthEnv.unlock(device: EnrollResponse, name: String, pin: String? = TEST_PIN, password: String? = null) =
    authService().unlock(UnlockRequest(device.deviceId, name, device.credential, pin = pin, password = password), "127.0.0.1")

/** The access token of [name] on [device]. */
internal fun AuthEnv.token(device: EnrollResponse, name: String, secretIsPassword: Boolean = false): String =
    (if (secretIsPassword) unlock(device, name, pin = null, password = TEST_PASSWORD) else unlock(device, name)).accessToken

/** Counts the secrets checked, so a test can tell that a refused try cost no hash. */
internal class CountingHasher : Hasher(2, TEST_COST) {
    var verifies = 0
    override fun verify(secret: String, stored: String?): Boolean {
        verifies++
        return super.verify(secret, stored)
    }
}
