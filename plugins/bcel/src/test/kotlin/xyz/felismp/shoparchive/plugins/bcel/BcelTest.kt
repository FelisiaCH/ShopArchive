package xyz.felismp.shoparchive.plugins.bcel

import xyz.felismp.shoparchive.api.plugin.PluginConfig
import xyz.felismp.shoparchive.shared.SlipCheckDto
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BCEL = "00020101021133650004BCEL0108ONEPROOF0206ONEPAY0312BAGSTL8O2VVK041520260918668438563047AD6"
private const val TRUST = "FQR26267XZTK2414"
private const val OTHER_BANK = "373700152026092361534420114202609231730595802LA6304F452"
private const val PAYER = "Somchai Customer"

private const val REPLY = """{"txtime":"2026-09-18 19:18:42","type":"ONP","fccref":"202609186684385","ticket":"BAGSTL8O2VVK","amount":850000,"ccy":"LAK","lakamount":850000,"sourcename":"$PAYER","sourceaccount":"130-12-XXXXXX08     ","sourceccy":"LAK","destinationname":"ຮ້ານ ໂກ້ໂມບາຍ","destinationaccount":"160-12-00-XXXXXX78-001","destinationccy":"LAK","result":0}"""

private class MapConfig(private val v: Map<String, Any>) : PluginConfig {
    override fun contains(path: String) = v.containsKey(path)
    override fun getString(path: String, default: String) = (v[path] as? String) ?: default
    override fun getInt(path: String, default: Int) = (v[path] as? Int) ?: default
    override fun getLong(path: String, default: Long) = default
    override fun getDouble(path: String, default: Double) = default
    override fun getBoolean(path: String, default: Boolean) = default
    @Suppress("UNCHECKED_CAST")
    override fun getStringList(path: String) = (v[path] as? List<String>) ?: emptyList()
    override fun keys(section: String) = v.keys.toList()
}

/** Answers what it is told and remembers what it was asked. */
private class FakeTransport(var answer: () -> HttpAnswer) : HttpTransport {
    val asked = mutableListOf<Pair<String, Int>>()
    override fun get(url: String, timeoutSeconds: Int): HttpAnswer {
        asked += url to timeoutSeconds
        return answer()
    }
}

private fun settings(accounts: List<String> = emptyList(), timeout: Int = 10) = BcelSettings(accounts, timeout)

class BcelTest {
    // --- reading the code ---

    @Test
    fun theRealCodeNamesTheTicketAndTheFccref() {
        val slip = OneProof.parse(BCEL)
        assertNotNull(slip)
        assertEquals("BAGSTL8O2VVK", slip.ticket)
        assertEquals("202609186684385", slip.fccref)
    }

    @Test
    fun tag38IsReadLikeTag33AndSpacesInTheNumbersAreRemoved() {
        val inner = "0004BCEL0108ONEPROOF0314BAGS TL8O 2VVK04" + "18" + "2026 0918 6684 385"
        val slip = OneProof.parse("000201" + "38" + inner.length + inner + "6304ABCD")
        assertNotNull(slip)
        assertEquals("BAGSTL8O2VVK", slip.ticket)
        assertEquals("202609186684385", slip.fccref)
    }

    @Test
    fun aCodeThatIsNotABcelOneProofSlipIsNotRead() {
        assertNull(OneProof.parse(TRUST))
        assertNull(OneProof.parse(OTHER_BANK))
        assertNull(OneProof.parse(""))
        assertNull(OneProof.parse(BCEL.replace("ONEPROOF", "ONEPROOX")), "another product of BCEL")
        assertNull(OneProof.parse(BCEL.replace("BCEL0108", "ACLB0108")), "another bank")
        assertNull(OneProof.parse(BCEL.replace("0312BAGSTL8O2VVK", "0000")), "no ticket")
    }

    @Test
    fun malformedTextIsNotReadAndNeverThrows() {
        for (code in listOf(BCEL.dropLast(30), BCEL.take(3), "0002", "00ab01", "0002010102", "33", "33zz", "3399" + "x".repeat(10), "\u0000\u0001", "ໃບໂອນ กาแฟ ☕", "-1-1")) {
            assertNull(OneProof.parse(code), code)
        }
        // The bank's tags cut short, though the outer text is whole.
        assertNull(OneProof.parse("3310" + "0004BCEL01"))
    }

    @Test
    fun theRequestAsksBcelByFccrefAndTicketAndNothingElse() {
        assertEquals("https://bcel.la:8083/onesure.php?fccref=202609186684385&ticket=BAGSTL8O2VVK", OneProof.parse(BCEL)!!.url())
        assertEquals("https://bcel.la:8083/onesure.php?fccref=a%26b&ticket=x%3Dy+z", OneProof("x=y z", "a&b").url())
    }

    @Test
    fun theTimeoutOfTheSettingsIsWhatTheTransportIsGiven() {
        val transport = FakeTransport { HttpAnswer(200, REPLY) }
        BcelChecker({ settings(timeout = 7) }, transport).check(BCEL)
        assertEquals(listOf("https://bcel.la:8083/onesure.php?fccref=202609186684385&ticket=BAGSTL8O2VVK" to 7), transport.asked)
    }

    // --- the answers ---

    @Test
    fun aSlipBcelKnowsIsKnownWithWhatBcelSays() {
        val check = BcelChecker({ settings() }, FakeTransport { HttpAnswer(200, REPLY) }).check(BCEL)
        assertEquals(
            SlipCheckDto("BCEL", true, "850000", "LAK", "2026-09-18 19:18:42", "ຮ້ານ ໂກ້ໂມບາຍ", toShop = null, toAccount = "160-12-00-XXXXXX78-001", from = PAYER, fromAccount = "130-12-XXXXXX08"),
            check,
        )
    }

    @Test
    fun theAmountIsInTheCurrencysNormalFormat() {
        fun amount(json: String, ccy: String) = BcelChecker({ settings() }, FakeTransport { HttpAnswer(200, REPLY.replace("\"amount\":850000", "\"amount\":$json").replace("\"ccy\":\"LAK\"", "\"ccy\":\"$ccy\"")) }).check(BCEL)?.amount
        assertEquals("120.50", amount("120.5", "THB"))
        assertEquals("120.00", amount("120", "THB"))
        assertEquals("25.25", amount("25.250", "USD"))
        assertEquals("1000", amount("1E3", "LAK"))
        assertEquals("850000", amount("\"850000\"", "LAK"))
        for (bad in listOf("0", "-5", "850000.5", "\"abc\"", "null", "1E999999999")) assertNull(amount(bad, "LAK"), "$bad is not an amount that BCEL can have sent; the slip is then not known")
    }

    @Test
    fun anotherResultOrMissingFieldsMeansBcelDoesNotKnowTheSlip() {
        val unknown = SlipCheckDto("BCEL", false)
        for (body in listOf(
            REPLY.replace("\"result\":0", "\"result\":1"), REPLY.replace("\"result\":0", "\"result\":-9"), REPLY.replace(",\"result\":0", ""),
            REPLY.replace("\"amount\":850000,", ""), REPLY.replace("\"ccy\":\"LAK\",", ""), REPLY.replace("\"txtime\":\"2026-09-18 19:18:42\",", ""),
            REPLY.replace("\"destinationname\":\"ຮ້ານ ໂກ້ໂມບາຍ\",", ""), REPLY.replace("\"destinationaccount\":\"160-12-00-XXXXXX78-001\",", ""),
            """{"result":0}""", """{"error":"not found"}""", "{}",
        )) {
            assertEquals(unknown, BcelChecker({ settings() }, FakeTransport { HttpAnswer(200, body) }).check(BCEL), body)
        }
    }

    @Test
    fun noAnswerIsNull() {
        val failures = listOf<() -> HttpAnswer>(
            { throw IOException("no route") }, { throw HttpTimeoutException("timed out") }, { HttpAnswer(500, REPLY) }, { HttpAnswer(404, "") }, { HttpAnswer(302, "") },
            { HttpAnswer(200, "<html>Bad gateway</html>") }, { HttpAnswer(200, "") }, { HttpAnswer(200, "[1,2]") }, { HttpAnswer(200, "\"result\"") }, { HttpAnswer(200, REPLY.take(100)) },
        )
        for (failure in failures) assertNull(BcelChecker({ settings() }, FakeTransport(failure)).check(BCEL))
    }

    @Test
    fun aCodeOfAnotherBankIsNotAskedAboutAtAll() {
        val transport = FakeTransport { HttpAnswer(200, REPLY) }
        val checker = BcelChecker({ settings() }, transport)
        assertNull(checker.check(TRUST))
        assertNull(checker.check(OTHER_BANK))
        assertEquals(emptyList(), transport.asked)
    }

    // --- who was paid ---

    @Test
    fun toShopIsNullWithoutAccountsAndOtherwiseWhetherTheSlipWasPaidToOne() {
        fun toShop(accounts: List<String>) = BcelChecker({ settings(accounts) }, FakeTransport { HttpAnswer(200, REPLY) }).check(BCEL)?.toShop
        assertNull(toShop(emptyList()))
        assertEquals(true, toShop(listOf("160-12-00-12345678-001")))
        assertEquals(true, toShop(listOf("999-99-99-99999999-999", "160-12-00-99999978-001")))
        assertEquals(false, toShop(listOf("160-12-00-12345679-001")), "the visible digits differ")
        assertEquals(false, toShop(listOf("160-12-00-1234567-001")), "a different length")
        assertEquals(false, toShop(listOf("999-99-99-99999999-999")))
    }

    @Test
    fun anAccountMatchesWhenEveryCharacterIsEqualOrHiddenByBcel() {
        assertTrue(OneProof.matches("160-12-00-12345678-001", "160-12-00-XXXXXX78-001"))
        assertTrue(OneProof.matches("160-12-00-12345678-001", "160-12-00-xxxxxx78-001"))
        assertTrue(OneProof.matches("160-12-00-12345678-001", "160-12-00-12345678-001"))
        assertTrue(OneProof.matches("160-12-00-ab345678-001", "160-12-00-AB345678-001"), "case does not matter")
        assertFalse(OneProof.matches("160-12-00-12345678-001", "160-12-00-XXXXXX79-001"))
        assertFalse(OneProof.matches("160-12-00-12345678-001", "160-12-00-XXXXXX78-002"))
        assertFalse(OneProof.matches("160-12-00-12345678-001", "160-12-00-XXXXXX78-0011"))
        assertFalse(OneProof.matches("", "X"))
    }

    @Test
    fun theTrailingSpacesBcelPutsAfterTheNumberDoNotMatter() {
        val body = REPLY.replace("160-12-00-XXXXXX78-001", "160-12-00-XXXXXX78-001   ")
        assertEquals(true, BcelChecker({ settings(listOf("160-12-00-12345678-001")) }, FakeTransport { HttpAnswer(200, body) }).check(BCEL)?.toShop)
    }

    // --- the payer ---

    @Test
    fun theCustomersNameAndAccountAreInAKnownAnswerButNeverInTheLog() {
        val log = mutableListOf<String>()
        val known = BcelChecker({ settings(emptyList()) }, FakeTransport { HttpAnswer(200, REPLY) }, log::add).check(BCEL)!!
        assertEquals(PAYER, known.from)
        assertEquals("130-12-XXXXXX08", known.fromAccount)
        assertEquals("160-12-00-XXXXXX78-001", known.toAccount)
        for (answer in listOf<() -> HttpAnswer>({ HttpAnswer(200, REPLY.replace("\"result\":0", "\"result\":3")) }, { HttpAnswer(500, REPLY) }, { HttpAnswer(200, REPLY.take(150)) })) {
            val check = BcelChecker({ settings(listOf("160-12-00-12345678-001")) }, FakeTransport(answer), log::add).check(BCEL)
            assertFalse(PAYER in check.toString() || "130-12" in check.toString(), check.toString())
        }
        assertFalse(log.any { PAYER in it || "130-12" in it }, log.toString())
    }

    // --- the settings ---

    @Test
    fun theSettingsDefaultToNoAccountsAndTenSeconds() {
        val warnings = mutableListOf<String>()
        val s = BcelSettings.read(MapConfig(emptyMap()), warnings::add)
        assertEquals(emptyList(), s.accounts)
        assertEquals(10, s.timeoutSeconds)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun theAccountsAreTrimmedAndTheTimeoutIsClampedWithAWarning() {
        val warnings = mutableListOf<String>()
        val s = BcelSettings.read(MapConfig(mapOf("accounts" to listOf(" 160-12-00-12345678-001 ", "", "160-12-00-12345678-001"), "timeout-seconds" to 99)), warnings::add)
        assertEquals(listOf("160-12-00-12345678-001"), s.accounts)
        assertEquals(30, s.timeoutSeconds)
        assertEquals(1, warnings.size)
        assertEquals(2, BcelSettings.read(MapConfig(mapOf("timeout-seconds" to 0)), warnings::add).timeoutSeconds)
        assertEquals(2, warnings.size)
        assertEquals(5, BcelSettings.read(MapConfig(mapOf("timeout-seconds" to 5)), warnings::add).timeoutSeconds)
        assertEquals(2, warnings.size)
    }

    @Test
    fun theShippedConfigReadsAsTheDefaults() {
        val text = BcelTest::class.java.getResourceAsStream("/config.yml")!!.bufferedReader(Charsets.UTF_8).readText()
        assertTrue("accounts: []" in text && "timeout-seconds: 10" in text)
        assertEquals(2..30, BcelSettings.TIMEOUT_RANGE)
        assertEquals(10, BcelSettings.DEFAULT_TIMEOUT_SECONDS)
    }
}
