package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.write
import xyz.felismp.shoparchive.shared.ErrorReasons
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HasherTest {
    private val hasher = Hasher(2, TEST_COST)

    @Test
    fun aHashIsAPhcStringAndTheRightValueVerifies() {
        val stored = hasher.hash("correct horse")

        assertTrue(Regex("\\\$argon2id\\\$v=19\\\$m=1024,t=1,p=1\\\$[A-Za-z0-9+/]{22}\\\$[A-Za-z0-9+/]{43}").matches(stored), stored)
        assertTrue(hasher.verify("correct horse", stored))
        assertFalse(hasher.verify("correct horse ", stored))
        assertFalse(hasher.verify("Correct horse", stored))
        assertFalse(hasher.verify("", stored))
    }

    @Test
    fun theSameValueGetsADifferentSaltEveryTime() {
        assertNotEquals(hasher.hash("same"), hasher.hash("same"))
    }

    @Test
    fun theCostComesFromTheStoredHashNotFromTheHasher() {
        val stored = Hasher(1, Argon2Cost(2048, 2, 1)).hash("moved")

        assertContains(stored, "m=2048,t=2,p=1")
        // A hasher with the production cost still checks a hash made with another one.
        assertTrue(Hasher(1).verify("moved", stored))
        assertFalse(Hasher(1).verify("other", stored))
    }

    @Test
    fun theProductionCostIsTheOwaspMinimum() {
        assertEquals(47_104, Argon2Cost.DEFAULT.memoryKb)
        assertEquals(1, Argon2Cost.DEFAULT.iterations)
        assertEquals(1, Argon2Cost.DEFAULT.parallelism)
    }

    @Test
    fun anythingThatIsNotAReadableArgon2idHashNeverVerifies() {
        val good = hasher.hash("x")
        val salt = good.split("$")[4]
        val hash = good.split("$")[5]
        val bad = listOf(
            null, "", "x", "plain text password", "\$argon2i\$v=19\$m=1024,t=1,p=1\$$salt\$$hash", "\$argon2id\$v=16\$m=1024,t=1,p=1\$$salt\$$hash",
            "\$argon2id\$v=19\$m=1024,t=1,p=1\$$salt", "\$argon2id\$v=19\$m=1024,t=1,p=1\$!!!\$$hash",
            // Costs a hand-edited file must not be able to impose.
            "\$argon2id\$v=19\$m=4000000,t=1,p=1\$$salt\$$hash", "\$argon2id\$v=19\$m=1024,t=500,p=1\$$salt\$$hash",
            "\$argon2id\$v=19\$m=1024,t=1,p=0\$$salt\$$hash", "\$argon2id\$v=19\$m=0,t=1,p=1\$$salt\$$hash",
        )
        for (stored in bad) assertFalse(hasher.verify("x", stored), stored)
    }

    @Test
    fun manyChecksAtOnceAllFinishWhenOnlyOneMayRun() {
        val one = Hasher(1, TEST_COST)
        val stored = one.hash("v")
        val pool = Executors.newFixedThreadPool(6)
        val results = (1..12).map { n -> pool.submit<Boolean> { one.verify(if (n % 2 == 0) "v" else "w", stored) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals((1..12).map { it % 2 == 0 }, results.map { it.get() })
    }
}

class PolicyTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String? = null) = AuthEnv(root, config)

    @Test
    fun aPasswordIsRefusedOnlyWhenMissingOrTooLong() = env().run {
        val policy = Policy(settings, users)

        assertNull(policy.checkPassword("x".repeat(128)))
        assertContains(policy.checkPassword("x".repeat(129))!!.message, "128")
        assertContains(policy.checkPassword("x".repeat(100_000))!!.message, "128")
        assertContains(policy.checkPassword(null)!!.message, "needed")
        assertContains(policy.checkPassword("")!!.message, "needed")
        // Length is counted in characters, after NFC: "é" typed as e + accent is one.
        assertNull(policy.checkPassword("e\u0301".repeat(128)))
        assertContains(policy.checkPassword("e\u0301".repeat(129))!!.message, "128")
    }

    @Test
    fun aWeakPasswordIsAccepted() = env().run {
        addUser("mali")
        addUser("noy", op = true)
        val policy = Policy(settings, users)

        // Short, common, with the user name, with the server name: the app warns, the server accepts.
        for (weak in listOf("a", "short-1", "fourteen-chars", "password", "Password123", "12345678", "my-MALI-secret-1", "the-shoparchive-pass")) {
            assertNull(policy.checkPassword(weak), weak)
        }
    }

    @Test
    fun aPasswordIsCheckedAfterNfcNormalisation() {
        assertEquals("é", normalizePassword("e\u0301"))
        val hasher = Hasher(1, TEST_COST)
        val stored = hasher.hash(normalizePassword("café-pass-1"))

        assertTrue(hasher.verify(normalizePassword("café-pass-1"), stored))
    }

    @Test
    fun aPinIsExactlyTheConfiguredDigits() = env().run {
        val policy = Policy(settings, users)

        assertNull(policy.checkPin("482915"))
        for (bad in listOf("12345", "1234567", "48291a", "48 915", "")) {
            assertContains(policy.checkPin(bad)!!.message, "PIN", message = bad)
        }
        assertContains(policy.checkPin(null)!!.message, "needed")
    }

    @Test
    fun aTrivialPinIsAccepted() = env().run {
        val policy = Policy(settings, users)

        for (weak in listOf("111111", "000000", "123456", "654321", "234567", "987654")) assertNull(policy.checkPin(weak), weak)
    }

    @Test
    fun everyRefusalCarriesAReasonTheAppCanWord() = env().run {
        val policy = Policy(settings, users)

        assertEquals(ErrorReasons.PIN_LENGTH, policy.checkPin("12")!!.reason)
        assertEquals(ErrorReasons.PIN_REQUIRED, policy.checkPin(null)!!.reason)
        assertEquals(ErrorReasons.PASSWORD_REQUIRED, policy.checkPassword(null)!!.reason)
        assertEquals(ErrorReasons.PASSWORD_REQUIRED, policy.checkPassword("")!!.reason)
        assertEquals(ErrorReasons.PASSWORD_LONG, policy.checkPassword("x".repeat(129))!!.reason)
    }

    @Test
    fun thePinLengthComesFromTheConfig() = env("config-version: 1\nauth:\n  pin:\n    length: 4\n").run {
        val policy = Policy(settings, users)

        assertNull(policy.checkPin("4829"))
        assertNull(policy.checkPin("1234"))
        assertContains(policy.checkPin("482915")!!.message, "exactly 4")
    }

    @Test
    fun anOpOrAUserWithAListedNodeNeedsAPassword() = env().run {
        addUser("mali")
        addUser("noy", op = true)
        addUser("boss")
        users.setUserPermission("boss", "shoparchive.users.manage", true)
        val policy = Policy(settings, users)

        assertFalse(policy.passwordRequired("mali"))
        assertTrue(policy.passwordRequired("noy"))
        assertTrue(policy.passwordRequired("boss"))
        assertFalse(policy.passwordRequired("nobody"))
    }

    @Test
    fun theRequiredForListIsTheConfiguredOne() = env("config-version: 1\nauth:\n  password:\n    required-for: [test.view]\n").run {
        addUser("mali")
        addUser("noy", op = true)
        val policy = Policy(settings, users)

        // test.view is on for everyone by default; "op" is no longer listed, but an op has every node.
        assertTrue(policy.passwordRequired("mali"))
        assertTrue(policy.passwordRequired("noy"))
    }
}
