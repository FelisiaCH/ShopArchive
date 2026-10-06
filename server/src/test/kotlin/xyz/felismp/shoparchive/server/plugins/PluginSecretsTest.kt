package xyz.felismp.shoparchive.server.plugins

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.plugin.PluginConfig
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.config.DEFAULT_SECRET_KEYS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginSecretsTest {
    @TempDir
    lateinit var root: Path

    @AfterTest
    fun tearDown() = Log.close()

    private fun secrets(values: Map<String, Any?>, keys: List<String> = DEFAULT_SECRET_KEYS): PluginSecrets {
        val config: PluginConfig = FlatPluginConfig(values)
        return PluginSecrets(keys) { config }
    }

    private fun latestLog() = Files.readString(root.resolve("logs/latest.log"))

    @Test
    fun aKeyIsSecretWhenItsLastSegmentIsAWordOrEndsInOneAfterADashUnderscoreOrCapital() {
        val s = secrets(emptyMap())
        for (key in listOf("token", "mail.password", "bot-token", "bot_token", "botToken", "discordWebhook", "API_KEY", "apiKey", "apikey", "a.b.Secret", "webhook-url", "Token")) {
            assertTrue(s.isSecret(key), key)
        }
        for (key in listOf("tokens", "monkey", "key-count", "password.min-length", "mail.host", "keyboard", "tokenizer", "")) {
            assertFalse(s.isSecret(key), key)
        }
    }

    @Test
    fun theWordsComeFromTheListSoAnAdminCanAddOrRemoveSome() {
        val s = secrets(emptyMap(), listOf("pin"))
        assertTrue(s.isSecret("admin-pin"))
        assertFalse(s.isSecret("token"))
    }

    @Test
    fun onlyValuesOfSecretKeysAreMaskedAndOnlyWhenLongEnoughAndNotBlank() {
        val s = secrets(mapOf("token" to "abcd1234", "pin-code" to "9999", "password" to "abc", "secret" to "   ", "greeting" to "hello world", "api-key" to listOf("k-one-111", "k-two-222")))

        assertEquals("sent *** and *** and *** (abc, 9999, hello world)", s.mask("sent abcd1234 and k-one-111 and k-two-222 (abc, 9999, hello world)"))
    }

    @Test
    fun aSecretThatContainsAnotherIsMaskedWhole() {
        val s = secrets(mapOf("token" to "abcdef", "secret" to "abcdefgh"))
        assertEquals("***/***", s.mask("abcdefgh/abcdef"))
    }

    @Test
    fun theLoggerMasksMessagesAndExceptionMessagesButKeepsTheStackAndThePrefix() {
        Log.start(root)
        val logger = PrefixedLogger("Mailer", secrets(mapOf("mail.password" to "hunter2hunter2")))

        logger.info("logging in with hunter2hunter2")
        logger.error("login failed", IllegalStateException("bad password hunter2hunter2", RuntimeException("inner hunter2hunter2")))

        val log = latestLog()
        assertContains(log, "[Mailer] logging in with ***")
        assertContains(log, "IllegalStateException: bad password ***")
        assertContains(log, "RuntimeException: inner ***")
        assertFalse("hunter2hunter2" in log, log)
        assertContains(log, "theLoggerMasksMessagesAndExceptionMessagesButKeepsTheStackAndThePrefix")
    }

    @Test
    fun anExceptionWithoutASecretIsPassedOnAsItIs() {
        val e = IllegalStateException("fine", RuntimeException("also fine"))
        val s = secrets(mapOf("token" to "abcd1234"))
        assertSame(e, s.mask(e))
        assertNotSame(e, s.mask(IllegalStateException("abcd1234")))
    }

    @Test
    fun theValuesFollowTheConfigAfterAReload() {
        var config: PluginConfig = FlatPluginConfig(mapOf("token" to "oldoldold"))
        val s = PluginSecrets(DEFAULT_SECRET_KEYS) { config }
        config = FlatPluginConfig(mapOf("token" to "newnewnew"))
        assertEquals("oldoldold ***", s.mask("oldoldold newnewnew"))
    }

    @Test
    fun suppressedExceptionsAreMaskedToo() {
        val s = secrets(mapOf("token" to "abcd1234"))
        val e = IllegalStateException("fine")
        e.addSuppressed(RuntimeException("closing failed for abcd1234", IllegalArgumentException("deep abcd1234")))

        val masked = s.mask(e)!!

        assertNotSame(e, masked)
        assertEquals(1, masked.suppressed.size)
        assertEquals("java.lang.RuntimeException: closing failed for ***", masked.suppressed[0].message)
        assertEquals("java.lang.IllegalArgumentException: deep ***", masked.suppressed[0].cause!!.message)
        assertFalse("abcd1234" in masked.stackTraceToString(), masked.stackTraceToString())
    }

    @Test
    fun anExceptionThatIsItsOwnCauseOrSuppressedDoesNotLoopForever() {
        val s = secrets(mapOf("token" to "abcd1234"))
        val a = RuntimeException("a abcd1234")
        val b = RuntimeException("b", a)
        a.addSuppressed(b)

        val masked = s.mask(b)!!

        assertFalse("abcd1234" in masked.stackTraceToString())
    }
}
