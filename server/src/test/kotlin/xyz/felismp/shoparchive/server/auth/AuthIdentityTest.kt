package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ALICE_PIN = "482915"
private const val BOSS_PIN = "905173"

/** Who a session and a device block belong to: the account (its id), not the name it has today. */
class AuthIdentityTest {
    @TempDir
    lateinit var root: Path

    private class Login(val device: LoginResponse, val token: String)

    private fun AuthEnv.authService() = services.get(AuthService::class.java)!!

    private fun AuthEnv.login(name: String, pin: String, mode: DeviceMode = DeviceMode.PERSONAL): Login {
        if (name !in users.userNames()) addUser(name)
        val device = authService().login(LoginRequest(name, "Phone of $name", "android", mode, newPin = pin), "127.0.0.1")
        return Login(device, authService().unlock(UnlockRequest(device.deviceId, name, device.credential, pin = pin), "127.0.0.1").accessToken)
    }

    private fun AuthEnv.unlock(device: LoginResponse, name: String, pin: String) =
        authService().unlock(UnlockRequest(device.deviceId, name, device.credential, pin = pin), "127.0.0.1")

    private fun deviceFile(device: LoginResponse) = root.resolve("data/devices/${device.deviceId}.yml")

    @Test
    fun aNameTakenOverAfterARenameNeverInheritsTheOldAccountsTokenOrDevice() {
        val env = AuthEnv(root)
        val alice = env.login("alice", ALICE_PIN)
        val boss = env.login("boss", BOSS_PIN)
        val aliceId = env.users.find("alice")!!.id
        val bossId = env.users.find("boss")!!.id

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
