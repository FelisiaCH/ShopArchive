package xyz.felismp.shoparchive.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigServiceTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun service(overrides: Overrides = Overrides.NONE) = ConfigService(root, overrides, log, FIXED_CLOCK)

    private fun loaded(overrides: Overrides = Overrides.NONE) = service(overrides).also { it.load() }

    // --- first start, defaults, template ---

    @Test
    fun firstStartCreatesThreeFilesWithDefaultsAndFullComments() {
        val config = loaded()

        assertEquals("ShopArchive", config.serverName)
        assertEquals("", config.motd)
        assertEquals(25655, config.port)
        assertEquals("0.0.0.0", config.bindAddress)
        assertTrue(config.lanDiscovery)
        assertEquals("info", config.logLevel)
        assertEquals(ZoneId.of("Asia/Vientiane"), config.timezone)
        assertEquals(Locale.forLanguageTag("lo"), config.locale)
        assertEquals(30_000, config.shutdownHookTimeoutMs)
        assertEquals(listOf("LAK" to 0, "THB" to 2, "USD" to 2), config.currencies.map { it.code to it.exponent })

        for (key in ServerProperties.keys) {
            val text = root.text("server.properties")
            assertTrue("# ${key.comment.lines().first()}" in text, "no comment for ${key.path}")
            assertTrue("\n${key.path}=" in text, "no line for ${key.path}")
        }
        val yaml = root.text("config/shoparchive.yml")
        assertTrue(yaml.lines().first { !it.startsWith("#") }.startsWith("config-version: 1"), yaml)
        for (key in CoreConfig.keys) assertTrue("# ${key.comment}" in yaml, "no comment for ${key.path}")
        assertTrue("shutdown:\n  # " in yaml && "\n  hook-timeout-ms: 30000" in yaml, yaml)
        assertTrue("your own comments are not" in root.text("server.properties") && "unknown keys are dropped" in root.text("server.properties"))
        assertTrue("config-version: 1" in root.text("config/currencies.yml"))
        assertTrue("  - code: LAK\n    exponent: 0\n  - code: THB\n    exponent: 2\n  - code: USD\n    exponent: 2\n" in root.text("config/currencies.yml"))
        assertEquals(emptyList(), root.backups())
        assertTrue(log.warnings.isEmpty(), log.warnings.toString())
        assertEquals(3, log.infos.count { "created" in it })
    }

    @Test
    fun deletedKeyIsPutBackWithItsCommentAndTheOldFileIsKeptByteForByte() {
        loaded()
        val before = Files.readAllBytes(root.resolve("server.properties"))
        val withoutKey = String(before).lines().filter { !it.startsWith("lan-discovery=") }.joinToString("\n")
        root.write("server.properties", withoutKey)
        val edited = Files.readAllBytes(root.resolve("server.properties"))

        val config = loaded()

        assertTrue(config.lanDiscovery)
        assertContentEquals(before, Files.readAllBytes(root.resolve("server.properties")))
        assertEquals(listOf("$FIXED_STAMP/server.properties"), root.backups())
        assertContentEquals(edited, Files.readAllBytes(root.resolve("data/migration/$FIXED_STAMP/server.properties")))
        assertTrue(log.infos.any { "server.properties" in it && "rewritten" in it && "data/migration/$FIXED_STAMP/server.properties" in it }, log.infos.toString())
    }

    @Test
    fun deletedYamlKeyComesBackWithItsComment() {
        loaded()
        val original = root.text("config/shoparchive.yml")
        root.write("config/shoparchive.yml", original.lines().filter { !it.startsWith("locale:") }.joinToString("\n"))

        assertEquals(Locale.forLanguageTag("lo"), loaded().locale)

        assertEquals(original, root.text("config/shoparchive.yml"))
        assertEquals(listOf("$FIXED_STAMP/config/shoparchive.yml"), root.backups())
    }

    @Test
    fun unchangedFilesAreNeitherRewrittenNorBackedUp() {
        loaded()
        val files = listOf("server.properties", "config/shoparchive.yml", "config/currencies.yml")
        val old = FileTime.fromMillis(1_000_000_000_000L)
        val bytes = files.associateWith { Files.readAllBytes(root.resolve(it)) }
        for (f in files) Files.setLastModifiedTime(root.resolve(f), old)
        log.infos.clear()

        loaded()

        for (f in files) {
            assertEquals(old, Files.getLastModifiedTime(root.resolve(f)), "$f was rewritten")
            assertContentEquals(bytes.getValue(f), Files.readAllBytes(root.resolve(f)))
        }
        assertEquals(emptyList(), root.backups())
        assertEquals(3, log.infos.count { "unchanged" in it })
    }

    // --- value rules ---

    @Test
    fun pluginSecretKeysHaveDefaultsAreLowerCasedAndAnInvalidListFallsBackWithAWarning() {
        assertTrue("token" in loaded().plugins.secretKeys && "api-key" in loaded().plugins.secretKeys)

        root.write("config/shoparchive.yml", "config-version: 1\nplugins:\n  secret-keys: [PIN, Bot-Token]\n")
        assertEquals(listOf("pin", "bot-token"), loaded().plugins.secretKeys)

        root.write("config/shoparchive.yml", "config-version: 1\nplugins:\n  secret-keys: [\"two words\"]\n")
        val config = loaded()
        assertEquals(DEFAULT_SECRET_KEYS, config.plugins.secretKeys)
        assertEquals(1, log.warningsWith("shoparchive.yml", "plugins.secret-keys", "two words").size, log.warnings.toString())
    }

    @Test
    fun pluginSettingsDefaultToApprovalOnAndAreClamped() {
        assertEquals(PluginSettings(requireApproval = true, disableTimeoutMs = 5_000), loaded().plugins)

        root.write("config/shoparchive.yml", "config-version: 1\nplugins:\n  require-approval: false\n  disable-timeout-ms: 1\n")
        val config = loaded()

        assertEquals(PluginSettings(requireApproval = false, disableTimeoutMs = 100), config.plugins)
        assertEquals(1, log.warningsWith("shoparchive.yml", "plugins.disable-timeout-ms", "'1'", "'100'").size, log.warnings.toString())
        assertTrue("plugins.require-approval" !in root.text("config/shoparchive.yml") && "require-approval: false" in root.text("config/shoparchive.yml"))
    }

    @Test
    fun outOfRangeNumbersAreClampedWithAWarningNamingKeyBadValueAndValueUsed() {
        root.write("server.properties", "port=70000\n")
        root.write("config/shoparchive.yml", "config-version: 1\nshutdown:\n  hook-timeout-ms: 5\n")

        val config = loaded()

        assertEquals(65535, config.port)
        assertEquals(1000, config.shutdownHookTimeoutMs)
        assertEquals(1, log.warningsWith("server.properties", "port", "70000", "65535").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("shoparchive.yml", "shutdown.hook-timeout-ms", "'5'", "'1000'").size, log.warnings.toString())
        // the clamped value is what the rewritten file now says
        assertTrue("\nport=65535\n" in root.text("server.properties"))
    }

    @Test
    fun portBelowRangeAndAbsurdlyLargeNumbersClampToTheNearestEnd() {
        root.write("server.properties", "port=0\n")
        assertEquals(1, loaded().port)

        root.write("server.properties", "port=99999999999999\n")
        assertEquals(65535, loaded().port)
    }

    @Test
    fun invalidValuesFallBackToTheDefaultWithAWarning() {
        root.write("server.properties", "log-level=loud\nport=abc\nlan-discovery=maybe\nserver-name=   \n")
        root.write("config/shoparchive.yml", "config-version: 1\ntimezone: Mars/Olympus\nlocale: fr\nshutdown:\n  hook-timeout-ms: soon\n")

        val config = loaded()

        assertEquals("info", config.logLevel)
        assertEquals(25655, config.port)
        assertTrue(config.lanDiscovery)
        assertEquals("ShopArchive", config.serverName)
        assertEquals(ZoneId.of("Asia/Vientiane"), config.timezone)
        assertEquals(Locale.forLanguageTag("lo"), config.locale)
        assertEquals(30_000, config.shutdownHookTimeoutMs)
        for (key in listOf("log-level", "port", "lan-discovery", "server-name", "timezone", "locale", "shutdown.hook-timeout-ms")) {
            assertEquals(1, log.warnings.count { it.contains("$key:") && it.contains("invalid") }, "$key: ${log.warnings}")
        }
        assertTrue(log.warningsWith("log-level", "loud", "'info'").isNotEmpty())
    }

    @Test
    fun validNonDefaultValuesAreKept() {
        root.write("server.properties", "server-name=Corner Shop\nmotd=hello\nport=26000\nbind-address=127.0.0.1\nlan-discovery=false\nlog-level=DEBUG\n")
        root.write("config/shoparchive.yml", "config-version: 1\ntimezone: Asia/Bangkok\nlocale: th\nshutdown:\n  hook-timeout-ms: 5000\n")

        val config = loaded()

        assertEquals("Corner Shop", config.serverName)
        assertEquals("hello", config.motd)
        assertEquals(26000, config.port)
        assertEquals("127.0.0.1", config.bindAddress)
        assertEquals(false, config.lanDiscovery)
        assertEquals("debug", config.logLevel)
        assertEquals(ZoneId.of("Asia/Bangkok"), config.timezone)
        assertEquals(Locale.forLanguageTag("th"), config.locale)
        assertEquals(5000, config.shutdownHookTimeoutMs)
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun unknownKeyWarnsAndIsDroppedAfterABackup() {
        root.write("server.properties", "port=25655\nmax-players=50\n")
        root.write("config/shoparchive.yml", "config-version: 1\ntimezone: Asia/Vientiane\ncolour: blue\nshutdown:\n  panic: yes\n")

        loaded()

        assertEquals(1, log.warningsWith("server.properties", "unknown key", "max-players").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("shoparchive.yml", "unknown key", "colour").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("shoparchive.yml", "unknown key", "shutdown.panic").size, log.warnings.toString())
        assertTrue("max-players" !in root.text("server.properties"))
        assertTrue("colour" !in root.text("config/shoparchive.yml") && "panic" !in root.text("config/shoparchive.yml"))
        assertTrue("max-players=50" in root.text("data/migration/$FIXED_STAMP/server.properties"))
        assertTrue("colour: blue" in root.text("data/migration/$FIXED_STAMP/config/shoparchive.yml"))
    }

    @Test
    fun propertiesValueIsEverythingAfterTheFirstEqualsSignTrimmed() {
        root.write("server.properties", "  motd =  a = b \\n c  \n# server-name=ignored\n")

        val config = loaded()

        assertEquals("a = b \\n c", config.motd)
        assertEquals("ShopArchive", config.serverName)
    }

    @Test
    fun linesWithoutEqualsAndDuplicateKeysWarn() {
        root.write("server.properties", "this is not a setting\nmotd=one\nmotd=two\n")

        val config = loaded()

        assertEquals("two", config.motd)
        assertEquals(1, log.warningsWith("line 1").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("duplicate", "motd").size, log.warnings.toString())
    }

    // --- Lao / Thai text ---

    @Test
    fun laoAndThaiTextRoundTripsByteExact() {
        val name = "ຮ້ານສະບາຍດີ ร้านสบายดี"
        val motd = "ຍິນດີຕ້ອນຮັບ \\n ยินดีต้อนรับ = 100%"
        root.write("server.properties", "server-name=$name\nmotd=$motd\n")

        val config = loaded()
        assertEquals(name, config.serverName)
        assertEquals(motd, config.motd)
        val afterFirstLoad = Files.readAllBytes(root.resolve("server.properties"))
        assertTrue("server-name=$name\n".toByteArray().let { needle -> indexOf(afterFirstLoad, needle) >= 0 })
        assertTrue("motd=$motd\n".toByteArray().let { needle -> indexOf(afterFirstLoad, needle) >= 0 })

        val again = loaded()
        assertEquals(name, again.serverName)
        assertEquals(motd, again.motd)
        assertContentEquals(afterFirstLoad, Files.readAllBytes(root.resolve("server.properties")))
    }

    @Test
    fun utf8BomIsAcceptedAndDroppedOnRewrite() {
        Files.write(root.resolve("server.properties"), byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "server-name=Bom Shop\n".toByteArray())

        assertEquals("Bom Shop", loaded().serverName)
        assertTrue(Files.readAllBytes(root.resolve("server.properties")).take(3) != listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    }

    @Test
    fun fileThatIsNotUtf8IsRefusedAndKeptUntouched() {
        val latin1 = "server-name=café\n".toByteArray(Charsets.ISO_8859_1)
        Files.write(root.resolve("server.properties"), latin1)

        val e = assertFailsWith<ConfigFileException> { service().load() }

        assertTrue("server.properties" in e.message!! && "UTF-8" in e.message!!, e.message)
        assertContentEquals(latin1, Files.readAllBytes(root.resolve("server.properties")))
        assertEquals(emptyList(), root.backups())
    }

    // --- files we must not overwrite ---

    @Test
    fun unparsableYamlFailsLoadAndLeavesTheFileUntouched() {
        val broken = "config-version: 1\ntimezone: [unclosed\n  : :\n"
        root.write("config/shoparchive.yml", broken)

        val e = assertFailsWith<ConfigFileException> { service().load() }

        assertTrue("config/shoparchive.yml" in e.message!! && "cannot be parsed" in e.message!!, e.message)
        assertEquals(broken, root.text("config/shoparchive.yml"))
        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun yamlThatIsNotAMappingIsRefusedToo() {
        root.write("config/currencies.yml", "- just\n- a list\n")

        val e = assertFailsWith<ConfigFileException> { service().load() }

        assertTrue("config/currencies.yml" in e.message!!, e.message)
        assertEquals("- just\n- a list\n", root.text("config/currencies.yml"))
    }

    @Test
    fun newerConfigVersionIsRefusedAndTheFileIsLeftAlone() {
        val future = "config-version: 2\ntimezone: Asia/Vientiane\nsomething-new: 1\n"
        root.write("config/shoparchive.yml", future)

        val e = assertFailsWith<ConfigFileException> { service().load() }

        assertTrue("config-version 2" in e.message!! && "untouched" in e.message!! && "config/shoparchive.yml" in e.message!!, e.message)
        assertEquals(future, root.text("config/shoparchive.yml"))
        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun configVersionThatIsNotANumberIsRefused() {
        root.write("config/shoparchive.yml", "config-version: latest\n")

        assertFailsWith<ConfigFileException> { service().load() }

        assertEquals("config-version: latest\n", root.text("config/shoparchive.yml"))
    }

    @Test
    fun missingConfigVersionIsTakenAsTheFirstVersionAndAdded() {
        root.write("config/shoparchive.yml", "timezone: Asia/Bangkok\n")

        assertEquals(ZoneId.of("Asia/Bangkok"), loaded().timezone)

        assertTrue(root.text("config/shoparchive.yml").lines().first { !it.startsWith("#") }.startsWith("config-version: 1"))
    }

    // --- currencies ---

    @Test
    fun currencyRulesCodeShapeUniquenessAndExponentRange() {
        root.write(
            "config/currencies.yml",
            """
            config-version: 1
            currencies:
              - code: LAK
                exponent: 0
              - code: usd
                exponent: 2
              - code: LAK
                exponent: 3
              - code: EUR
                exponent: 9
              - code: JPY
                exponent: many
              - code: GBP
                exponent: 2
                name: Pound
            """.trimIndent(),
        )

        val config = loaded()

        assertEquals(listOf("LAK" to 0, "EUR" to 4, "GBP" to 2), config.currencies.map { it.code to it.exponent })
        assertEquals(1, log.warningsWith("usd").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("LAK", "twice").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("EUR", "exponent 9", "using 4").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("JPY", "exponent").size, log.warnings.toString())
        assertEquals(1, log.warningsWith("unknown key", "name").size, log.warnings.toString())
    }

    @Test
    fun emptyCurrencyListFallsBackToTheDefaultsWithAWarning() {
        root.write("config/currencies.yml", "config-version: 1\ncurrencies: []\n")

        val config = loaded()

        assertEquals(CurrencyConfig.defaultCurrencies, config.currencies)
        assertEquals(1, log.warningsWith("currencies", "defaults").size, log.warnings.toString())
    }

    // --- command line, reload ---

    @Test
    fun commandLineBeatsTheFileAndIsNeverWrittenBack() {
        root.write("server.properties", "port=25000\nlog-level=warn\n")
        loaded() // normalizes the partial file once
        val onDisk = Files.readAllBytes(root.resolve("server.properties"))
        val backupsBefore = root.backups()

        val config = loaded(Overrides.parse(arrayOf("--port", "26000", "--bind-address", "10.0.0.5")))

        assertEquals(26000, config.port)
        assertEquals("10.0.0.5", config.bindAddress)
        assertEquals("warn", config.logLevel) // not overridden: the file wins over the default
        assertContentEquals(onDisk, Files.readAllBytes(root.resolve("server.properties")))
        assertTrue("\nport=25000\n" in root.text("server.properties"))
        assertTrue("10.0.0.5" !in root.text("server.properties"))
        assertEquals(backupsBefore, root.backups()) // nothing was rewritten
    }

    @Test
    fun overrideOnAFirstStartLeavesTheDefaultInTheNewFile() {
        val config = loaded(Overrides.parse(arrayOf("--port", "26000", "--log-level", "debug")))

        assertEquals(26000, config.port)
        assertEquals("debug", config.logLevel)
        assertTrue("\nport=25655\n" in root.text("server.properties") && "\nlog-level=info\n" in root.text("server.properties"))
    }

    @Test
    fun reloadPicksUpAnEditedValueAndNamesTheChangedKey() {
        val config = loaded()
        root.write("server.properties", root.text("server.properties").replace("motd=", "motd=Open late"))

        val changed = config.reload()

        assertEquals(listOf("motd"), changed)
        assertEquals("Open late", config.motd)
        assertEquals(emptyList(), config.reload())
    }

    @Test
    fun reloadReportsKeysOfAllThreeFilesInFileOrder() {
        val config = loaded()
        root.write("server.properties", root.text("server.properties").replace("port=25655", "port=25656"))
        root.write("config/shoparchive.yml", root.text("config/shoparchive.yml").replace("locale: lo", "locale: en"))
        root.write("config/currencies.yml", root.text("config/currencies.yml").replace("code: USD", "code: EUR"))

        assertEquals(listOf("port", "locale", "currencies"), config.reload())
        assertEquals(Locale.forLanguageTag("en"), config.locale)
        assertEquals(listOf("LAK", "THB", "EUR"), config.currencies.map { it.code })
    }

    @Test
    fun overridesStayAppliedAfterReload() {
        val config = loaded(Overrides.parse(arrayOf("--port", "26000")))
        root.write("server.properties", root.text("server.properties").replace("port=25655", "port=27000"))

        assertEquals(emptyList(), config.reload()) // the effective port did not change
        assertEquals(26000, config.port)
    }

    @Test
    fun failedReloadKeepsTheOldValues() {
        val config = loaded()
        root.write("server.properties", root.text("server.properties").replace("motd=", "motd=changed"))
        root.write("config/shoparchive.yml", "config-version: 99\n")

        assertFailsWith<ConfigFileException> { config.reload() }

        assertEquals("", config.motd)
    }

    @Test
    fun gettersBeforeLoadAreAnError() {
        assertFailsWith<IllegalStateException> { service().port }
    }

    // --- backups ---

    @Test
    fun twoBackupsInTheSameSecondDoNotOverwriteEachOther() {
        loaded()
        root.write("server.properties", "motd=first edit\n")
        loaded()
        root.write("server.properties", "motd=second edit\n")
        loaded()

        assertEquals(listOf("$FIXED_STAMP-1/server.properties", "$FIXED_STAMP/server.properties"), root.backups())
        assertEquals("motd=first edit\n", root.text("data/migration/$FIXED_STAMP/server.properties"))
        assertEquals("motd=second edit\n", root.text("data/migration/$FIXED_STAMP-1/server.properties"))
    }

    @Test
    fun backupsOfDifferentFilesFromOneBootShareADirectory() {
        root.write("server.properties", "motd=x\n")
        root.write("config/shoparchive.yml", "config-version: 1\n")

        loaded()

        assertEquals(listOf("$FIXED_STAMP/config/shoparchive.yml", "$FIXED_STAMP/server.properties"), root.backups())
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1
}
