package xyz.felismp.shoparchive.server.config

import xyz.felismp.shoparchive.api.ShopEventTypes
import xyz.felismp.shoparchive.shared.DEFAULT_PORT
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** `server.properties`: how the server starts and where it listens. No version key - new keys are simply added with defaults. */
internal object ServerProperties : PropertiesConfigFile() {
    override val path = "server.properties"
    override val title = "ShopArchive server settings (network and boot). Changes apply on restart or reload."

    val serverName = StringKey("server-name", "ShopArchive", "Name of this server, shown to clients.")
    val motd = StringKey("motd", "", "Short message shown to clients when they connect.", allowBlank = true)
    val port = IntKey(
        "port", DEFAULT_PORT,
        "TCP port the server listens on. Forward this one port on the router to reach the server from the internet. Needs a restart.",
        min = 1, max = 65535,
    )
    val bindAddress = StringKey("bind-address", "0.0.0.0", "Address to listen on. 0.0.0.0 means all network interfaces. Needs a restart.")
    val lanDiscovery = BoolKey("lan-discovery", true, "Announce the server on the local network so clients on the same Wi-Fi find it. Needs a restart.")
    val logLevel = ChoiceKey(
        "log-level", "info", "Least severe level that is written to the console and the log files.",
        listOf("trace", "debug", "info", "warn", "error"),
    )

    override val keys = listOf(serverName, motd, port, bindAddress, lanDiscovery, logLevel)
}

/** `config/shoparchive.yml`: how the core behaves. Sections for later features are added by the phase that uses them. */
internal object CoreConfig : YamlConfigFile<Values>("config-version", 1, emptyList()) {
    override val path = "config/shoparchive.yml"
    override val title = "ShopArchive core settings. Changes apply on restart or reload."

    val timezone = ZoneKey(
        "timezone", ZoneId.of("Asia/Vientiane"),
        "Time zone that cuts log days and business dates.",
    )
    val locale = LocaleKey("locale", Locale.forLanguageTag("lo"), "Language of server messages.", listOf("lo", "th", "en"))
    val shutdownHookTimeoutMs = IntKey(
        "shutdown.hook-timeout-ms", 30_000,
        "How long a stop waits for the shutdown hooks to finish before the JVM goes on exiting, in milliseconds.",
        min = 1_000, max = 600_000,
    )

    val networkDomains = HostListKey(
        "network.domains",
        "Domain names the certificate must also cover, e.g. [shop.example.com]. Add the name clients use to reach the server from the internet. Needs a restart.",
    )
    val certValidityDays = IntKey(
        "network.cert-validity-days", 825,
        "How many days a new certificate is valid. A certificate with less than 30 days left is replaced (same key) when the server starts. Needs a restart.",
        min = 30, max = 825,
    )
    val maxBodyKb = IntKey(
        "network.max-body-kb", 1024,
        "Largest request body the server accepts, in KB. Bigger requests get 413. Needs a restart.",
        min = 16, max = 51_200,
    )
    val requestTimeoutSeconds = IntKey(
        "network.request-timeout-seconds", 30,
        "Longest a request may take to arrive and a response to be taken, and longest a connection may stay silent, in seconds. Needs a restart.",
        min = 5, max = 300,
    )

    val pinLength = IntKey("auth.pin.length", 6, "Number of digits in a new PIN. A PIN already set keeps working when this changes.", min = 4, max = 12)
    val pinMaxFailures = IntKey(
        "auth.pin.max-failures", 0,
        "Wrong PINs or passwords on one device before that user is removed from that device (the user must log in on it again). 0 turns this off.",
        min = 0, max = 100,
    )
    val deviceIdleExpiryDays = IntKey(
        "auth.device.idle-expiry-days", 0,
        "A user not used on a device for this many days is taken off it (the user must log in on it again), in days. 0 turns this off.",
        min = 0, max = 3650,
    )
    val autoLockSharedMinutes = IntKey(
        "auth.device.auto-lock-shared-minutes", 3,
        "Minutes of no use after which the app locks on a shared device. Policy for the app: the server only tells it (GET /api/v1/config).",
        min = 1, max = 120,
    )
    val autoLockPersonalMinutes = IntKey(
        "auth.device.auto-lock-personal-minutes", 15,
        "Minutes of no use after which the app locks on a personal device. Policy for the app: the server only tells it (GET /api/v1/config).",
        min = 1, max = 1440,
    )
    val biometricsPersonal = BoolKey(
        "auth.device.biometrics-personal", true,
        "Let the app on a personal device unlock with fingerprint or face. Policy for the app: the server only tells it (GET /api/v1/config).",
    )
    val unlockWithoutPin = BoolKey(
        "auth.device.unlock-without-pin", true,
        "A device that holds one user unlocks with its device credential alone; the PIN is asked only for deleting entries and managing users, devices and the console. A device with more users always asks for the PIN.",
    )
    val passwordRequiredFor = WordListKey(
        "auth.password.required-for",
        emptyList(),
        "A user needs a password, not only a PIN, if they are an op (the word op) or have any of these permission nodes. Empty (the default): everyone signs in with the PIN alone.",
    )
    val passwordMin = IntKey("auth.password.min", 8, "Suggested shortest password, in characters, for everyone; shorter ones are allowed, the app only warns.", min = 1, max = 128)
    val passwordMax = IntKey("auth.password.max", 128, "Longest password, in characters. Anything longer is refused before it is hashed.", min = 64, max = 1024)
    val accessTokenMinutes = IntKey(
        "auth.session.access-token-minutes", 15,
        "How long an access token (what a device holds after it unlocks) works, in minutes.",
        min = 1, max = 1440,
    )
    val reauthWindowMinutes = IntKey(
        "auth.session.reauth-window-minutes", 5,
        "An important action (such as taking a device off) needs the PIN or password to have been entered within this many minutes.",
        min = 1, max = 60,
    )
    val reauthEveryDays = IntKey(
        "auth.session.reauth-every-days", 0,
        "Unlocking asks for the password (not the PIN) if the user has one and the password was last entered more than this many days ago. 0 turns this off.",
        min = 0, max = 365,
    )
    val reauthIdleDays = IntKey(
        "auth.session.reauth-idle-days", 0,
        "Unlocking asks for the password (not the PIN) if the user has one and the device was last unlocked by the user more than this many days ago. 0 turns this off.",
        min = 0, max = 365,
    )
    val backoffStartSeconds = IntKey(
        "auth.backoff.start-seconds", 1,
        "After a wrong PIN or password the account is locked this long, in seconds; each further wrong one doubles it. 0 turns the delay off.",
        min = 0, max = 60,
    )
    val backoffMaxMinutes = IntKey("auth.backoff.max-minutes", 15, "The longest an account is locked after a wrong PIN or password, in minutes.", min = 1, max = 1440)
    val backoffDisableAt = IntKey(
        "auth.backoff.disable-at", 0,
        "Wrong PINs or passwords in a row on one account (without a right one between) after which the account is disabled until the admin runs: user unlock <name>. 0 turns this off.",
        min = 0, max = 10_000,
    )
    val rateLimitPerMinute = IntKey(
        "auth.rate-limit-per-minute", 10,
        "Requests per minute one address may send to the endpoints open to everyone (logging in, unlocking); more get 429. Needs a restart.",
        min = 1, max = 1000,
    )
    val hashConcurrency = IntKey(
        "auth.hash-concurrency", 2,
        "Password and PIN hashes computed at the same time; each needs about 46 MB of memory. Needs a restart.",
        min = 1, max = 8,
    )

    val requireOpenDay = BoolKey(
        "records.require-open-day", true,
        "Entries can only be recorded while the branch has an open day (a session opened with the change counted in the drawer).",
    )
    val requireCategory = BoolKey("records.require-category", false, "Every entry must have a category. Off: a category is optional (if given, it must fit the entry).")
    val editWindowDays = IntKey(
        "records.edit-window-days", 0,
        "How many days back a user may still change or delete their own entries (with entry.edit.own / entry.delete.own). 0 = the same business day only. Anyone with entry.edit.all / entry.delete.all is not limited.",
        min = 0, max = 30,
    )
    val slipsMaxCount = IntKey("records.slips.max-count", 5, "Most slips (payment screenshots) one entry may have.", min = 0, max = 20)
    val slipsMaxSizeKb = IntKey(
        "records.slips.max-size-kb", 5120,
        "Largest slip image, in KB. JPEG and PNG only. An entry with slips may be sent in a request up to this size times max-count, plus network.max-body-kb.",
        min = 64, max = 51_200,
    )

    val pluginsRequireApproval = BoolKey(
        "plugins.require-approval", true,
        "A plugin jar that is new or has changed since it was approved is not loaded until the admin types: plugins approve <name>. Off: every jar in plugins/ is loaded. A plugin is code with full access to this server, so only install jars you built or audited. Needs a restart.",
    )
    val pluginsDisableTimeoutMs = IntKey(
        "plugins.disable-timeout-ms", 5_000,
        "How long a stop waits for one plugin's onDisable before going on without it, in milliseconds.",
        min = 100, max = 120_000,
    )

    val pluginsSecretKeys = WordListKey(
        "plugins.secret-keys", DEFAULT_SECRET_KEYS,
        "A value in a plugin's config.yml is a secret when the last part of its key is one of these words, or ends in -word or _word (bot-token, discordWebhook, API_KEY: case is ignored, camelCase counts as words). " +
            "A secret is shown as *** by `plugins info` and replaced by *** in what that plugin logs. Needs a restart.",
    )

    val notifyEvents = WordListKey(
        "notify.events", listOf(ShopEventTypes.DAY_CLOSED, ShopEventTypes.DEVICE_NEW, ShopEventTypes.ENTRY_CREATED),
        "The events that put a message into the outbox for the channel plugin: ${ShopEventTypes.all.joinToString(", ")}. An empty list turns messages off. A name the server does not publish is ignored with a warning.",
    )
    val notifyTimeoutSeconds = IntKey(
        "notify.timeout-seconds", 30,
        "How long one try to send a message may take, in seconds. A try that takes longer counts as unknown (it may have arrived), not as a failure, and is tried again later.",
        min = 1, max = 600,
    )
    val notifyMaxAttempts = IntKey(
        "notify.max-attempts", 10,
        "Tries that may fail for a message (retry and failed answers, and errors; unknown tries do not count) before it is given up as failed. A failed message can be sent again by hand.",
        min = 1, max = 1000,
    )
    val notifyBackoffBaseSeconds = IntKey(
        "notify.backoff.base-seconds", 30,
        "The wait before the second try of a message, in seconds; each further try waits twice as long, up to notify.backoff.max-seconds.",
        min = 1, max = 3600,
    )
    val notifyBackoffMaxSeconds = IntKey(
        "notify.backoff.max-seconds", 3600,
        "The longest wait between two tries of one message, in seconds. Never below notify.backoff.base-seconds.",
        min = 1, max = 86_400,
    )

    val notifyReconcileDays = IntKey(
        "notify.reconcile-days", 2,
        "A record whose message was not queued (the server stopped between the two) is queued when the server starts or after a failure, if it is not older than this many days and not older than the first start with notifications. 0 turns this off.",
        min = 0, max = 31,
    )

    val backupEnabled = BoolKey(
        "backup.enabled", true,
        "Make a backup every day at backup.time while the server runs (and once shortly after a start when the last one is older than 24 hours). The console command backup works whichever this is.",
    )
    val backupTime = TimeKey("backup.time", LocalTime.of(3, 0), "Time of day (in the server time zone) of the daily backup.")
    val backupKeep = IntKey(
        "backup.keep", 14,
        "How many backup zips to keep in backups/ (and in copy-to); older ones are deleted after a good backup. The slip images in backups/slips/ are never deleted.",
        min = 1, max = 3650,
    )
    val backupCopyTo = PathKey(
        "backup.copy-to",
        "A second folder that gets every backup zip and slip image too, e.g. a USB drive or another disk. The folder must already exist: if it is missing (drive unplugged) or cannot be written, a warning is logged and the next backup tries again. A relative path is taken from the server root. Empty = no copy.",
    )

    val backupPauseTimeoutSeconds = IntKey(
        "backup.pause-timeout-seconds", 30,
        "How long a backup waits, while saving of records is held, for changes to users, devices and other data to finish before it gives up (the backup then fails and is tried again later), in seconds.",
        min = 1, max = 600,
    )

    val setupFirstBranch = StringKey(
        "setup.first-branch", "main",
        "The branch (its key; the display name is the key with a capital) made the first time the server starts with no users, if there is no branch yet. Empty: none is made.",
        allowBlank = true,
    )
    val setupFirstUser = StringKey(
        "setup.first-user", "owner",
        "The admin (an op, in that branch) made the first time the server starts with no users. Every start prints its user name and a new PIN while it has not logged in on any device. Empty: none is made and nothing is printed.",
        allowBlank = true,
    )

    val keys: List<Key<*>> = listOf(
        timezone, locale, shutdownHookTimeoutMs, networkDomains, certValidityDays, maxBodyKb, requestTimeoutSeconds,
        pinLength, pinMaxFailures,
        deviceIdleExpiryDays, autoLockSharedMinutes, autoLockPersonalMinutes, biometricsPersonal, unlockWithoutPin,
        passwordRequiredFor, passwordMin, passwordMax, accessTokenMinutes, reauthWindowMinutes, reauthEveryDays, reauthIdleDays,
        backoffStartSeconds, backoffMaxMinutes, backoffDisableAt, rateLimitPerMinute, hashConcurrency,
        requireOpenDay, requireCategory, editWindowDays, slipsMaxCount, slipsMaxSizeKb,
        pluginsRequireApproval, pluginsDisableTimeoutMs, pluginsSecretKeys,
        notifyEvents, notifyTimeoutSeconds, notifyMaxAttempts, notifyBackoffBaseSeconds, notifyBackoffMaxSeconds, notifyReconcileDays,
        backupEnabled, backupTime, backupKeep, backupCopyTo, backupPauseTimeoutSeconds,
        setupFirstBranch, setupFirstUser,
    )

    override fun resolve(content: Map<String, Any?>, warn: Warn): Values {
        val values = resolveKeys(keys, flatten(content), warn)
        // Each key is clamped alone, so a max below a suggested min could still make the suggestion impossible to follow.
        val floor = values[passwordMin]
        val fixed = HashMap<Key<*>, Any>()
        if (values[passwordMax] < floor) {
            warn("auth.password.max: ${values[passwordMax]} is below the suggested shortest password ($floor); using $floor")
            fixed[passwordMax] = floor
        }
        val events = values[notifyEvents]
        val known = events.filter { it in ShopEventTypes.all }
        if (known.size != events.size) {
            warn("notify.events: ${(events - known.toSet()).joinToString()} is not an event the server publishes (${ShopEventTypes.all.joinToString()}); ignored")
            fixed[notifyEvents] = known
        }
        if (values[notifyBackoffMaxSeconds] < values[notifyBackoffBaseSeconds]) {
            warn("notify.backoff.max-seconds: ${values[notifyBackoffMaxSeconds]} is below notify.backoff.base-seconds (${values[notifyBackoffBaseSeconds]}); using ${values[notifyBackoffBaseSeconds]}")
            fixed[notifyBackoffMaxSeconds] = values[notifyBackoffBaseSeconds]
        }
        return if (fixed.isEmpty()) values else values.withAll(fixed)
    }

    override fun renderBody(value: Values) = renderYamlKeys(keys, value)
}

internal data class Currency(val code: String, val exponent: Int)

/** `config/currencies.yml`: the currencies a record can be in, and how many decimal places each has. */
internal object CurrencyConfig : YamlConfigFile<List<Currency>>("config-version", 1, emptyList()) {
    override val path = "config/currencies.yml"
    override val title = "ShopArchive currencies. Changes apply on restart or reload."

    private val CODE = Regex("[A-Z]{3}")
    private val exponent = IntKey(
        "exponent", 2, "decimal places of the smallest unit, e.g. 0 for LAK, 2 for THB and USD.",
        min = 0, max = 4,
    )

    val defaultCurrencies = listOf(Currency("LAK", 0), Currency("THB", 2), Currency("USD", 2))

    override fun resolve(content: Map<String, Any?>, warn: Warn): List<Currency> {
        for (name in content.keys) {
            if (name != "currencies") warn("unknown key '$name' (ignored, and not kept when the file is rewritten)")
        }
        if ("currencies" !in content) return defaultCurrencies
        val entries = content["currencies"] as? List<*>
        if (entries == null) {
            warn("currencies: invalid value (expected a list of code/exponent entries); using the defaults")
            return defaultCurrencies
        }
        val result = LinkedHashMap<String, Currency>()
        entries.forEachIndexed { index, entry -> readEntry(index, entry, result, warn) }
        if (result.isEmpty()) {
            warn("currencies: no valid entry; using the defaults ${defaultCurrencies.joinToString()}")
            return defaultCurrencies
        }
        return result.values.toList()
    }

    private fun readEntry(index: Int, entry: Any?, result: MutableMap<String, Currency>, warn: Warn) {
        val where = "currencies[$index]"
        @Suppress("UNCHECKED_CAST")
        val fields = entry as? Map<String, Any?>
        if (fields == null) {
            warn("$where: not a code/exponent entry; ignored")
            return
        }
        for (name in fields.keys) {
            if (name != "code" && name != "exponent") warn("$where: unknown key '$name' (ignored, and not kept when the file is rewritten)")
        }
        val code = (fields["code"] as? String)?.trim()
        if (code == null || !CODE.matches(code)) {
            warn("$where: code '${fields["code"] ?: "(empty)"}' is not 3 uppercase letters A-Z; entry ignored")
            return
        }
        if (code in result) {
            warn("$where: code $code is listed twice; the later entry is ignored")
            return
        }
        // The key's default (2) is never used here: a wrong guess would silently misread every amount in that currency.
        val parsed = (fields["exponent"] as? String)?.trim()?.let(exponent::parse)
        if (parsed == null) {
            warn("$where: $code has no valid exponent (expected ${exponent.allowed}); entry ignored")
            return
        }
        val used = exponent.clamp(parsed)
        if (used != parsed) warn("$where: $code exponent $parsed is outside the allowed range (${exponent.allowed}); using $used")
        result[code] = Currency(code, used)
    }

    override fun renderBody(value: List<Currency>): String {
        val out = StringBuilder()
        out.append("# Currencies the server accepts, in the order clients list them.\n")
        out.append("# code: 3 uppercase letters, each used once.\n")
        out.append("# exponent: ").append(exponent.comment).append(" Allowed: ").append(exponent.allowed).append(".\n")
        out.append("currencies:\n")
        for (currency in value) {
            out.append("  - code: ").append(currency.code).append('\n')
            out.append("    exponent: ").append(currency.exponent).append('\n')
        }
        return out.toString()
    }
}
