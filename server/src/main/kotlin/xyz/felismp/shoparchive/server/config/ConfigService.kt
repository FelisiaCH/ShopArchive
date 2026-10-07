package xyz.felismp.shoparchive.server.config

import xyz.felismp.shoparchive.server.DataBarrier
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

/**
 * The server's configuration: `server.properties`, `config/shoparchive.yml` and `config/currencies.yml`,
 * read and normalized by [load] and re-read by [reload]. Precedence is command-line [overrides] > file > default.
 *
 * All three files are held in one immutable snapshot that [reload] replaces with a single assignment, so a
 * reader sees either the old configuration or the new one, never a mix.
 */
internal class ConfigService(
    private val root: Path,
    private val overrides: Overrides = Overrides.NONE,
    private val log: ConfigLog = ConsoleConfigLog,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val barrier: DataBarrier = DataBarrier(),
) {
    private class Snapshot(val properties: Values, val core: Values, val currencies: List<Currency>) {
        /** Paths of everything that differs from [other], in file order. */
        fun changedFrom(other: Snapshot): List<String> =
            properties.changedPaths(other.properties) + core.changedPaths(other.core) +
                listOf("currencies").filter { currencies != other.currencies }
    }

    @Volatile
    private var snapshot: Snapshot? = null

    /** Reads all three files, creating or rewriting them as needed. Throws [ConfigFileException] if one cannot be understood. */
    fun load() = barrier.mutate { loadInLock() }

    @Synchronized
    private fun loadInLock() {
        snapshot = readAll()
    }

    /**
     * Reads the files again and swaps the values in. Returns the paths of the keys whose effective value
     * changed. If a file cannot be understood this throws and the old values stay in force.
     */
    fun reload(): List<String> = barrier.mutate { reloadInLock() }

    @Synchronized
    private fun reloadInLock(): List<String> {
        val before = current()
        val after = readAll()
        snapshot = after
        return after.changedFrom(before)
    }

    val serverName: String get() = current().properties[ServerProperties.serverName]
    val motd: String get() = current().properties[ServerProperties.motd]
    val port: Int get() = current().properties[ServerProperties.port]
    val bindAddress: String get() = current().properties[ServerProperties.bindAddress]
    val lanDiscovery: Boolean get() = current().properties[ServerProperties.lanDiscovery]
    val logLevel: String get() = current().properties[ServerProperties.logLevel]
    val timezone: ZoneId get() = current().core[CoreConfig.timezone]
    val locale: Locale get() = current().core[CoreConfig.locale]
    val shutdownHookTimeoutMs: Int get() = current().core[CoreConfig.shutdownHookTimeoutMs]
    val networkDomains: List<String> get() = current().core[CoreConfig.networkDomains]
    val certValidityDays: Int get() = current().core[CoreConfig.certValidityDays]
    val maxBodyKb: Int get() = current().core[CoreConfig.maxBodyKb]
    val requestTimeoutSeconds: Int get() = current().core[CoreConfig.requestTimeoutSeconds]
    val currencies: List<Currency> get() = current().currencies
    val auth: AuthSettings get() = AuthSettings(current().core)
    val records: RecordSettings get() = RecordSettings(current().core)
    val notify: NotifySettings get() = NotifySettings(current().core)
    val backup: BackupSettings get() = BackupSettings(current().core)
    val setup: SetupSettings get() = SetupSettings(current().core)
    val plugins: PluginSettings get() = PluginSettings(current().core[CoreConfig.pluginsRequireApproval], current().core[CoreConfig.pluginsDisableTimeoutMs], current().core[CoreConfig.pluginsSecretKeys])

    private fun current(): Snapshot = snapshot ?: error("ConfigService.load() has not run")

    private fun readAll(): Snapshot {
        val properties = loadConfigFile(root, ServerProperties, log, clock).value
        val core = loadConfigFile(root, CoreConfig, log, clock).value
        val currencies = loadConfigFile(root, CurrencyConfig, log, clock).value
        return Snapshot(overrides.applyTo(properties), core, currencies)
    }
}

/** The `auth:` section of `config/shoparchive.yml`, read from one snapshot so a reload never shows a mix. */
internal class AuthSettings(core: Values) {
    val pairingTtlMinutes = core[CoreConfig.pairingTtlMinutes]
    val manualCodeAttempts = core[CoreConfig.manualCodeAttempts]
    val manualCode = core[CoreConfig.manualCode]
    val pairingSources = core[CoreConfig.pairingSources]
    val pinLength = core[CoreConfig.pinLength]
    val pinMaxFailures = core[CoreConfig.pinMaxFailures]
    val passwordRequiredFor = core[CoreConfig.passwordRequiredFor]
    val passwordMin = core[CoreConfig.passwordMin]
    val passwordMax = core[CoreConfig.passwordMax]
    val accessTokenMinutes = core[CoreConfig.accessTokenMinutes]
    val reauthWindowMinutes = core[CoreConfig.reauthWindowMinutes]
    val reauthEveryDays = core[CoreConfig.reauthEveryDays]
    val reauthIdleDays = core[CoreConfig.reauthIdleDays]
    val deviceIdleExpiryDays = core[CoreConfig.deviceIdleExpiryDays]
    val autoLockSharedMinutes = core[CoreConfig.autoLockSharedMinutes]
    val autoLockPersonalMinutes = core[CoreConfig.autoLockPersonalMinutes]
    val biometricsPersonal = core[CoreConfig.biometricsPersonal]
    val unlockWithoutPin = core[CoreConfig.unlockWithoutPin]
    val backoffStartSeconds = core[CoreConfig.backoffStartSeconds]
    val backoffMaxMinutes = core[CoreConfig.backoffMaxMinutes]
    val backoffDisableAt = core[CoreConfig.backoffDisableAt]
    val rateLimitPerMinute = core[CoreConfig.rateLimitPerMinute]
    val hashConcurrency = core[CoreConfig.hashConcurrency]
}

/** The `records:` section of `config/shoparchive.yml`, read from one snapshot so a reload never shows a mix. */
internal class RecordSettings(core: Values) {
    val requireOpenDay = core[CoreConfig.requireOpenDay]
    val requireCategory = core[CoreConfig.requireCategory]
    val editWindowDays = core[CoreConfig.editWindowDays]
    val slipMaxCount = core[CoreConfig.slipsMaxCount]
    val slipMaxSizeKb = core[CoreConfig.slipsMaxSizeKb]

    /** The largest request that creates or changes an entry: every slip at its largest, and the rest of the request as for any other. */
    fun entryBodyBytes(otherBodyBytes: Long): Long = slipMaxCount.toLong() * slipMaxSizeKb * 1024 + otherBodyBytes
}

/** The `notify:` section of `config/shoparchive.yml`, read from one snapshot so a reload never shows a mix. */
internal class NotifySettings(core: Values) {
    val events = core[CoreConfig.notifyEvents]
    val timeoutSeconds = core[CoreConfig.notifyTimeoutSeconds]
    val maxAttempts = core[CoreConfig.notifyMaxAttempts]
    val backoffBaseSeconds = core[CoreConfig.notifyBackoffBaseSeconds]
    val backoffMaxSeconds = core[CoreConfig.notifyBackoffMaxSeconds]
    val reconcileDays = core[CoreConfig.notifyReconcileDays]
}

/** The `backup:` section of `config/shoparchive.yml`, read from one snapshot so a reload never shows a mix. */
internal class BackupSettings(core: Values) {
    val enabled = core[CoreConfig.backupEnabled]
    val time = core[CoreConfig.backupTime]
    val keep = core[CoreConfig.backupKeep]
    val pauseTimeoutSeconds = core[CoreConfig.backupPauseTimeoutSeconds]

    /** Empty when there is no second folder. */
    val copyTo = core[CoreConfig.backupCopyTo].trim()
}

/** The `setup:` section of `config/shoparchive.yml`: what the first start makes. Empty names are "make nothing". */
internal class SetupSettings(core: Values) {
    val firstBranch = core[CoreConfig.setupFirstBranch].trim()
    val firstUser = core[CoreConfig.setupFirstUser].trim()
}

/** The `plugins:` section of `config/shoparchive.yml`. */
internal data class PluginSettings(
    val requireApproval: Boolean = true,
    val disableTimeoutMs: Int = 5_000,
    val secretKeys: List<String> = DEFAULT_SECRET_KEYS,
)

/** Config key words that mark a plugin's config value as a secret, unless `plugins.secret-keys` says otherwise. */
internal val DEFAULT_SECRET_KEYS = listOf("token", "password", "secret", "api-key", "apikey", "key", "webhook", "webhook-url")
