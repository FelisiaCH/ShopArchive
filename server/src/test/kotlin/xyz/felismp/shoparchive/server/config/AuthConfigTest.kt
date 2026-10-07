package xyz.felismp.shoparchive.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthConfigTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun loaded(auth: String? = null): ConfigService {
        if (auth != null) root.write("config/shoparchive.yml", "config-version: 1\n\nauth:\n$auth")
        return ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }
    }

    @Test
    fun theDefaultsAreThePlansAndAreWrittenUnderAuth() {
        val auth = loaded().auth

        assertEquals(6, auth.pinLength)
        assertEquals(0, auth.pinMaxFailures)
        assertEquals(emptyList(), auth.passwordRequiredFor)
        assertEquals(8, auth.passwordMin)
        assertEquals(128, auth.passwordMax)
        assertEquals(15, auth.accessTokenMinutes)
        assertEquals(5, auth.reauthWindowMinutes)
        assertEquals(2, auth.hashConcurrency)

        val text = root.text("config/shoparchive.yml")
        val tail = text.substring(text.indexOf("\nauth:\n"))
        for (line in listOf(
            "auth:\n",
            "\n  pin:\n", "    length: 6\n", "    max-failures: 0\n",
            "\n  password:\n", "    required-for: []\n",
            "    min: 8\n", "    max: 128\n",
            "\n  session:\n", "    access-token-minutes: 15\n", "    reauth-window-minutes: 5\n",
            "  hash-concurrency: 2\n",
        )) assertTrue(line in tail, "missing ${line.trim()} in:\n$tail")
        // Each key has its comment with the allowed range.
        assertTrue("# Allowed: integer from 1 to 1440. Default: 15" in tail, tail)
    }

    @Test
    fun theSectionsOfOneLevelStillRenderAsBefore() {
        loaded()

        val text = root.text("config/shoparchive.yml")
        assertTrue("\nshutdown:\n  # How long a stop waits" in text, text)
        assertTrue("\nnetwork:\n  # Domain names" in text, text)
        assertEquals(text, root.text("config/shoparchive.yml").also { loaded() }, "a second load must not change the file")
        assertTrue(root.backups().isEmpty(), root.backups().toString())
    }

    @Test
    fun outOfRangeValuesAreClampedWithAWarning() {
        val auth = loaded(
            "  pin:\n    length: 2\n    max-failures: 1000\n" +
                "  password:\n    min: 0\n    max: 3\n" +
                "  session:\n    access-token-minutes: 99999\n    reauth-window-minutes: 0\n" +
                "  hash-concurrency: 50\n",
        ).auth

        assertEquals(4, auth.pinLength)
        assertEquals(100, auth.pinMaxFailures)
        assertEquals(1, auth.passwordMin)
        assertEquals(1440, auth.accessTokenMinutes)
        assertEquals(1, auth.reauthWindowMinutes)
        assertEquals(8, auth.hashConcurrency)
        assertEquals(1, log.warningsWith("auth.session.access-token-minutes", "'99999'", "'1440'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("auth.password.min", "'0'", "'1'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("auth.pin.max-failures", "'1000'", "'100'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("auth.hash-concurrency", "'50'", "'8'").size, log.warnings.toString())
    }

    @Test
    fun aMaxBelowTheMinimumIsRaisedSoAPasswordCanExist() {
        val auth = loaded("  password:\n    min: 100\n    max: 64\n").auth

        assertEquals(100, auth.passwordMax)
        assertEquals(1, log.warningsWith("auth.password.max", "64", "100").size, log.warnings.toString())
    }

    @Test
    fun aLeftoverMinOpKeyIsIgnoredWithAnUnknownKeyWarning() {
        val auth = loaded("  password:\n    min: 10\n    min-op: 15\n").auth

        assertEquals(10, auth.passwordMin)
        assertEquals(1, log.warningsWith("unknown key", "auth.password.min-op").size, log.warnings.toString())
    }

    @Test
    fun listsAreReadInLowerCaseWithoutRepeatsAndMayBeEmpty() {
        val auth = loaded("  password:\n    required-for: [OP, op, Shoparchive.Users.Manage]\n").auth

        assertEquals(listOf("op", "shoparchive.users.manage"), auth.passwordRequiredFor)
        assertTrue("required-for: [op, shoparchive.users.manage]" in root.text("config/shoparchive.yml"))
        assertFalse(log.warnings.any { "auth." in it }, log.warnings.toString())

        assertEquals(emptyList(), loaded("  password:\n    required-for: []\n").auth.passwordRequiredFor)
        assertTrue("required-for: []" in root.text("config/shoparchive.yml"))
        assertFalse(log.warnings.any { "auth." in it }, log.warnings.toString())
    }

    @Test
    fun aReloadBringsTheNewValuesInOneStep() {
        val config = loaded()
        assertEquals(15, config.auth.accessTokenMinutes)
        root.write("config/shoparchive.yml", "config-version: 1\nauth:\n  session:\n    access-token-minutes: 3\n")

        val changed = config.reload()

        assertEquals(3, config.auth.accessTokenMinutes)
        assertTrue("auth.session.access-token-minutes" in changed, changed.toString())
    }

    @Test
    fun thePolicyKeysHaveTheirDefaultsAndAreWrittenInTheirSections() {
        val auth = loaded().auth

        assertEquals(0, auth.deviceIdleExpiryDays)
        assertEquals(3, auth.autoLockSharedMinutes)
        assertEquals(15, auth.autoLockPersonalMinutes)
        assertTrue(auth.biometricsPersonal)
        assertTrue(auth.unlockWithoutPin)
        assertEquals(0, auth.reauthEveryDays)
        assertEquals(0, auth.reauthIdleDays)
        assertEquals(1, auth.backoffStartSeconds)
        assertEquals(15, auth.backoffMaxMinutes)
        assertEquals(0, auth.backoffDisableAt)
        assertEquals(10, auth.rateLimitPerMinute)
        val text = root.text("config/shoparchive.yml")
        for (line in listOf(
            "\n  device:\n", "    idle-expiry-days: 0\n", "    auto-lock-shared-minutes: 3\n", "    auto-lock-personal-minutes: 15\n", "    biometrics-personal: true\n", "    unlock-without-pin: true\n",
            "    reauth-every-days: 0\n", "    reauth-idle-days: 0\n",
            "\n  backoff:\n", "    start-seconds: 1\n", "    max-minutes: 15\n", "    disable-at: 0\n", "  rate-limit-per-minute: 10\n",
        )) assertTrue(line in text, "missing ${line.trim()} in:\n$text")
    }

    @Test
    fun thePolicyKeysOutsideTheirRangeAreClampedWithAWarning() {
        val auth = loaded(
            "  pin:\n    max-failures: -3\n" +
                "  device:\n    idle-expiry-days: -1\n    auto-lock-shared-minutes: 999\n    auto-lock-personal-minutes: 0\n" +
                "  session:\n    reauth-every-days: 9999\n    reauth-idle-days: -5\n" +
                "  backoff:\n    start-seconds: 600\n    max-minutes: 0\n    disable-at: -1\n" +
                "  rate-limit-per-minute: 100000\n",
        ).auth

        assertEquals(0, auth.pinMaxFailures)
        assertEquals(0, auth.deviceIdleExpiryDays)
        assertEquals(120, auth.autoLockSharedMinutes)
        assertEquals(1, auth.autoLockPersonalMinutes)
        assertEquals(365, auth.reauthEveryDays)
        assertEquals(0, auth.reauthIdleDays)
        assertEquals(60, auth.backoffStartSeconds)
        assertEquals(1, auth.backoffMaxMinutes)
        assertEquals(0, auth.backoffDisableAt)
        assertEquals(1000, auth.rateLimitPerMinute)
        assertEquals(1, log.warningsWith("auth.backoff.disable-at", "'-1'", "'0'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("auth.rate-limit-per-minute", "'100000'", "'1000'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("auth.device.idle-expiry-days", "'-1'", "'0'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("auth.session.reauth-idle-days", "'-5'", "'0'").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("auth.pin.max-failures", "'-3'", "'0'").size, log.warnings.toString())
    }

    @Test
    fun zeroTurnsTheAuthLimitsOffAndIsNoWarning() {
        val auth = loaded(
            "  pin:\n    max-failures: 0\n" +
                "  device:\n    idle-expiry-days: 0\n" +
                "  session:\n    reauth-every-days: 0\n    reauth-idle-days: 0\n" +
                "  backoff:\n    disable-at: 0\n",
        ).auth

        assertEquals(0, auth.pinMaxFailures)
        assertEquals(0, auth.deviceIdleExpiryDays)
        assertEquals(0, auth.reauthEveryDays)
        assertEquals(0, auth.reauthIdleDays)
        assertEquals(0, auth.backoffDisableAt)
        assertEquals(emptyList(), log.warnings, log.warnings.toString())
    }
}
