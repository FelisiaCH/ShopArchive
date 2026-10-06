package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.PairingService
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE_PIN = "482915"
private const val BOSS_PIN = "905173"

/** Who a session, a device block and a grant belong to: the account (its id), not the name it has today. */
class AuthIdentityTest {
    @TempDir
    lateinit var root: Path

    private class Login(val device: EnrollResponse, val token: String)

    private fun AuthEnv.authService() = services.get(AuthService::class.java)!!
    private fun AuthEnv.pairingService() = services.get(PairingService::class.java)!!

    private fun AuthEnv.redeemed(name: String) = pairingService().redeem(RedeemRequest(secret = pair(name).secret), "127.0.0.1")

    private fun AuthEnv.login(name: String, pin: String, mode: DeviceMode = DeviceMode.PERSONAL): Login {
        if (name !in users.userNames()) addUser(name)
        val device = authService().enroll(redeemed(name).enrollmentToken, EnrollRequest("Phone of $name", "android", mode, newPin = pin), "127.0.0.1")
        return Login(device, authService().unlock(UnlockRequest(device.deviceId, name, device.credential, pin = pin), "127.0.0.1").accessToken)
    }

    private fun AuthEnv.unlock(device: EnrollResponse, name: String, pin: String) =
        authService().unlock(UnlockRequest(device.deviceId, name, device.credential, pin = pin), "127.0.0.1")

    private fun deviceFile(device: EnrollResponse) = root.resolve("data/devices/${device.deviceId}.yml")

    @Test
    fun aNameTakenOverAfterARenameNeverInheritsTheOldAccountsTokenDeviceOrGrants() {
        val env = AuthEnv(root)
        val alice = env.login("alice", ALICE_PIN)
        val boss = env.login("boss", BOSS_PIN)
        val aliceId = env.users.find("alice")!!.id
        val bossId = env.users.find("boss")!!.id
        val grant = env.redeemed("alice") // Alice has a grant in hand ...
        val pairing = env.pair("alice") // ... and a pairing waiting to be redeemed

        env.console("user rename alice alicia")
        env.console("user rename boss alice")

        // Alice, now alicia, still works with the token and the device she had.
        assertEquals("alicia", env.authService().authenticate(alice.token)?.username)
        env.unlock(alice.device, "alicia", ALICE_PIN)
        assertTrue(Files.readString(deviceFile(alice.device)).let { "  alicia:" in it && "  alice:" !in it && aliceId in it }, "the block follows the rename")
        // The new "alice" is the boss, with the boss's own token and device.
        assertEquals("alice", env.authService().authenticate(boss.token)?.username)
        env.unlock(boss.device, "alice", BOSS_PIN)
        assertTrue(Files.readString(deviceFile(boss.device)).let { "  alice:" in it && bossId in it })

        // Alice's device credential does not open the account that now has her old name, with her PIN or the boss's.
        for (pin in listOf(ALICE_PIN, BOSS_PIN)) {
            assertEquals(401, assertFailsWith<ApiError> { env.unlock(alice.device, "alice", pin) }.status)
        }
        assertContains(env.audit(), "unlock.fail user=alice device=${alice.device.deviceId} ip=127.0.0.1 result=identity-mismatch")

        // The grant Alice held is still Alice's: the boss's PIN is wrong for it, hers works, and the device that results is hers.
        assertEquals(401, assertFailsWith<ApiError> {
            env.authService().enroll(grant.enrollmentToken, EnrollRequest("Phone", "android", DeviceMode.PERSONAL, pin = BOSS_PIN), "127.0.0.1")
        }.status)
        val second = env.authService().enroll(grant.enrollmentToken, EnrollRequest("Phone", "android", DeviceMode.PERSONAL, pin = ALICE_PIN), "127.0.0.1")
        assertContains(Files.readString(deviceFile(second)), aliceId)
        env.unlock(second, "alicia", ALICE_PIN)

        // The pairing made for "alice" before the rename redeems for the same account, under the name it has now.
        assertEquals("alicia", env.pairingService().redeem(RedeemRequest(secret = pairing.secret), "127.0.0.1").username)
    }

    @Test
    fun aPrincipalCapturedBeforeARenameDoesNotPairOthersWithTheBossesPermission() {
        val env = AuthEnv(root)
        val alice = env.login("alice", ALICE_PIN)
        env.login("boss", BOSS_PIN)
        env.addUser("mali")
        env.users.setUserPermission("boss", PAIR_NODE, true) // Alice has no right to pair others; the boss has
        // The request was authenticated as Alice, then waits for its body while the renames happen.
        val captured = env.authService().authenticate(alice.token)!!

        env.console("user rename alice alicia")
        env.console("user rename boss alice")

        val refused = assertFailsWith<ApiError> { env.auth.pairing.createPairing(captured, "mali", "127.0.0.1") }
        assertEquals(403, refused.status)
        assertEquals(0, env.auth.pairing.pendingCount())
        // She may still pair her own account, under the name it has now.
        env.auth.pairing.createPairing(captured, "alicia", "127.0.0.1")
        assertEquals(1, env.auth.pairing.pendingCount())
        assertContains(env.audit(), "pair.created user=alicia")
    }

    @Test
    fun aDeviceBlockWithoutAUserIdIsTreatedAsRevoked() {
        val env = AuthEnv(root)
        val mali = env.login("mali", ALICE_PIN)
        val file = deviceFile(mali.device)
        Files.writeString(file, Files.readString(file).lines().filterNot { it.trim().startsWith("user-id:") }.joinToString("\n"))

        env.auth.devices.load()

        assertEquals(401, assertFailsWith<ApiError> { env.unlock(mali.device, "mali", ALICE_PIN) }.status)
        assertNull(env.authService().authenticate(mali.token))
    }

    @Test
    fun reloadDevicesAndAPlainReloadEndTheTokensOfABlockTheAdminRemoved() {
        val env = AuthEnv(root)
        for ((command, name) in listOf("reload devices" to "mali", "reload" to "noy")) {
            val login = env.login(name, ALICE_PIN)
            val file = deviceFile(login.device)
            // The file looks untouched to the modified-time check (a restored backup does); only a reload can find the edit.
            Files.setLastModifiedTime(file, removeUserBlock(file, name))

            env.console(command)

            assertNull(env.authService().authenticate(login.token), command)
            assertEquals(401, assertFailsWith<ApiError> { env.unlock(login.device, name, ALICE_PIN) }.status, command)
        }
    }

    @Test
    fun twoEnrollmentGrantsForTheSameNewUserSetOnePinAndTheOtherMustProveIt() {
        // Both requests have read "no PIN yet" when they reach the hash; only then may either go on.
        val bothRead = CountDownLatch(2)
        val hasher = object : Hasher(4, TEST_COST) {
            override fun hash(secret: String): String {
                bothRead.countDown()
                bothRead.await(2, TimeUnit.SECONDS) // with the fix the second request waits for the first one's lock, so this times out
                return super.hash(secret)
            }
        }
        val env = AuthEnv(root, hasher = hasher)
        env.addUser("mali")
        val first = env.redeemed("mali").enrollmentToken
        val second = env.redeemed("mali").enrollmentToken
        val pins = mapOf(first to ALICE_PIN, second to BOSS_PIN)

        val pool = Executors.newFixedThreadPool(2)
        val results = try {
            pins.map { (token, pin) ->
                pool.submit<Result<EnrollResponse>> {
                    runCatching { env.authService().enroll(token, EnrollRequest("Phone", "android", DeviceMode.PERSONAL, newPin = pin), "127.0.0.1") }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val winner = results.indexOfFirst { it.isSuccess }
        assertEquals(1, results.count { it.isSuccess }, "exactly one grant sets the PIN: $results")
        val loserError = assertNotNull(results[1 - winner].exceptionOrNull() as? ApiError)
        assertEquals(409, loserError.status)
        assertEquals(ErrorCode.CREDENTIALS_CHANGED, loserError.code)
        val winnerPin = pins.values.toList()[winner]
        val loserToken = pins.keys.toList()[1 - winner]
        val winnerDevice = results[winner].getOrThrow()

        // The loser asks the person for the existing PIN and goes on with the same token; that was not a wrong secret.
        val retried = env.authService().enroll(loserToken, EnrollRequest("Phone", "android", DeviceMode.PERSONAL, pin = winnerPin), "127.0.0.1")

        env.unlock(winnerDevice, "mali", winnerPin)
        env.unlock(retried, "mali", winnerPin)
        assertEquals(401, assertFailsWith<ApiError> { env.unlock(winnerDevice, "mali", pins.values.toList()[1 - winner]) }.status)
        assertFalse(env.audit().contains("enroll.fail user=mali device=- ip=127.0.0.1 result=wrong-secret"), env.audit())
    }

    @Test
    fun setCredentialsOnlyFillsWhatTheAccountDoesNotHaveYet() {
        val env = AuthEnv(root)
        env.addUser("mali")
        val id = env.users.find("mali")!!.id
        val hash = Hasher(1, TEST_COST)::hash

        assertTrue(env.users.setCredentials(id, null, hash("482915")))
        val pin = env.users.find("mali")!!.pin

        assertFalse(env.users.setCredentials(id, null, hash("905173")), "a PIN is there: not replaced")
        assertFalse(env.users.setCredentials(id, hash("a password of mine"), hash("905173")), "nothing is written when one of them is refused")
        assertEquals(pin, env.users.find("mali")!!.pin)
        assertNull(env.users.find("mali")!!.password)
        assertTrue(env.users.setCredentials(id, hash("a password of mine"), null), "what is still empty can be set")
        assertEquals(pin, env.users.find("mali")!!.pin)
    }
}
