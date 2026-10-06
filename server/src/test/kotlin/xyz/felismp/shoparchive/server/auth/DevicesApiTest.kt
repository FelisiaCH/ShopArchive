package xyz.felismp.shoparchive.server.auth

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.server.net.ApiException
import xyz.felismp.shoparchive.server.net.requireBranch
import xyz.felismp.shoparchive.server.net.requirePermission
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.SetModeRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The devices API, `GET /config`, and the helpers routes use to check a permission or a branch, over the real API module. */
class DevicesApiTest {
    @TempDir
    lateinit var root: Path

    private fun deviceFile(device: EnrollResponse) = Files.readString(root.resolve("data/devices/${device.deviceId}.yml"))

    /** A user who may take others off devices (and so has a password) and a shared device with mali and kham on it. */
    private class Scene(val env: AuthEnv) {
        val maliPhone: EnrollResponse
        val maliShared: EnrollResponse
        val khamShared: EnrollResponse
        val khamTablet: EnrollResponse
        val boss: EnrollResponse
        val maliId: String
        val khamId: String

        init {
            env.addUser("boss")
            env.users.setUserPermission("boss", DEVICES_REVOKE_NODE, true)
            boss = env.enroll("boss")
            maliPhone = env.enroll("mali")
            maliShared = env.enroll("mali", DeviceMode.SHARED)
            khamShared = env.enroll("kham", DeviceMode.SHARED, onto = maliShared)
            khamTablet = env.enroll("kham")
            maliId = env.users.find("mali")!!.id
            khamId = env.users.find("kham")!!.id
        }
    }

    // --- list ---

    @Test
    fun getDevicesListsTheCallersOwnDevicesWithTheirModeAndUserCount() = AuthEnv(root).run {
        val scene = Scene(this)
        api {
            val token = token(scene.maliShared, "mali")

            val response = getPath("/api/v1/devices", token)

            assertEquals(HttpStatusCode.OK, response.status)
            val list = response.parsed(ListSerializer(DeviceInfo.serializer())).associateBy { it.id }
            assertEquals(setOf(scene.maliPhone.deviceId, scene.maliShared.deviceId), list.keys)
            assertEquals(DeviceMode.PERSONAL, list.getValue(scene.maliPhone.deviceId).mode)
            assertEquals(1, list.getValue(scene.maliPhone.deviceId).userCount)
            assertFalse(list.getValue(scene.maliPhone.deviceId).current)
            val shared = list.getValue(scene.maliShared.deviceId)
            assertEquals(DeviceMode.SHARED, shared.mode)
            assertEquals(2, shared.userCount)
            assertTrue(shared.current)
            assertEquals("Phone of mali", shared.label)
            assertEquals("android", shared.platform)
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/devices").status)
        }
    }

    // --- revoke ---

    @Test
    fun aUserTakesTheirOwnEntryOffADeviceWithoutAPermissionAfterEnteringThePinAgain() = AuthEnv(root).run {
        val scene = Scene(this)
        api {
            val token = token(scene.maliPhone, "mali")
            val other = token(scene.maliShared, "mali")
            clock.advance(Duration.ofMinutes(6)) // past the 5-minute re-auth window
            val path = "/api/v1/devices/${scene.maliPhone.deviceId}"

            val needsPin = deletePath(path, token)
            assertEquals(HttpStatusCode.Unauthorized, needsPin.status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, needsPin.errorCode())
            assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(pin = TEST_PIN), token).status)

            assertEquals(HttpStatusCode.NoContent, deletePath(path, token).status)

            assertFalse("  mali:" in deviceFile(scene.maliPhone))
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/devices", token).status, "the token of that device ends")
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/devices", other).status, "mali's other device is untouched")
            assertEquals(HttpStatusCode.Unauthorized, postJson("/api/v1/unlock", xyz.felismp.shoparchive.shared.UnlockRequest.serializer(),
                xyz.felismp.shoparchive.shared.UnlockRequest(scene.maliPhone.deviceId, "mali", scene.maliPhone.credential, pin = TEST_PIN)).status)
            assertTrue(audit().contains("device.revoked user=mali device=${scene.maliPhone.deviceId}"), audit())
            assertEquals(HttpStatusCode.NotFound, deletePath(path, token(scene.maliShared, "mali")).status, "already gone")
        }
    }

    @Test
    fun anotherUsersEntryNeedsTheRevokePermissionAndTheOtherUsersTokenEndsAtOnce() = AuthEnv(root).run {
        val scene = Scene(this)
        api {
            val maliToken = token(scene.maliShared, "mali")
            val khamToken = token(scene.khamShared, "kham")
            val khamElsewhere = token(scene.khamTablet, "kham")
            val path = "/api/v1/devices/${scene.maliShared.deviceId}?user=${scene.khamId}"

            val refused = deletePath(path, maliToken)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals(ErrorCode.FORBIDDEN, refused.errorCode())
            assertTrue("  kham:" in deviceFile(scene.maliShared))

            val bossToken = token(scene.boss, "boss", secretIsPassword = true)
            assertEquals(HttpStatusCode.NoContent, deletePath(path, bossToken).status)

            assertFalse("  kham:" in deviceFile(scene.maliShared))
            assertTrue("  mali:" in deviceFile(scene.maliShared))
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/devices", khamToken).status)
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/devices", khamElsewhere).status, "kham's other device is untouched")
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/devices", maliToken).status)
            // Not there any more, not a device, or not on it: the same 404.
            assertEquals(HttpStatusCode.NotFound, deletePath(path, bossToken).status)
            assertEquals(HttpStatusCode.NotFound, deletePath("/api/v1/devices/11111111-2222-3333-4444-555555555555?user=${scene.khamId}", bossToken).status)
            assertEquals(HttpStatusCode.NotFound, deletePath("/api/v1/devices/${scene.maliPhone.deviceId}?user=${scene.khamId}", bossToken).status)
        }
    }

    @Test
    fun revokingAnotherUsersEntryAlsoNeedsTheCallersPasswordWithinTheWindow() = AuthEnv(root).run {
        val scene = Scene(this)
        api {
            val bossToken = token(scene.boss, "boss", secretIsPassword = true)
            clock.advance(Duration.ofMinutes(6))
            val path = "/api/v1/devices/${scene.maliShared.deviceId}?user=${scene.khamId}"

            assertEquals(ErrorCode.REAUTH_REQUIRED, deletePath(path, bossToken).errorCode())
            assertTrue("  kham:" in deviceFile(scene.maliShared))
            assertEquals(HttpStatusCode.NoContent, postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(password = TEST_PASSWORD), bossToken).status)
            assertEquals(HttpStatusCode.NoContent, deletePath(path, bossToken).status)
        }
    }

    // --- mode ---

    @Test
    fun aPersonalDeviceCanBecomeSharedButASharedOneBecomesPersonalOnlyWhenItHoldsJustTheCaller() = AuthEnv(root).run {
        val scene = Scene(this)
        api {
            val phoneToken = token(scene.maliPhone, "mali")
            val sharedToken = token(scene.maliShared, "mali")
            suspend fun mode(device: EnrollResponse, token: String, mode: DeviceMode) =
                putJson("/api/v1/devices/${device.deviceId}/mode", SetModeRequest.serializer(), SetModeRequest(mode), token)

            assertEquals(HttpStatusCode.NoContent, mode(scene.maliPhone, phoneToken, DeviceMode.SHARED).status)
            assertTrue("mode: shared" in deviceFile(scene.maliPhone))
            assertEquals(HttpStatusCode.NoContent, mode(scene.maliPhone, phoneToken, DeviceMode.SHARED).status, "no change is fine")

            val refused = mode(scene.maliShared, sharedToken, DeviceMode.PERSONAL)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertTrue("mode: shared" in deviceFile(scene.maliShared))

            assertEquals(HttpStatusCode.NoContent, mode(scene.maliPhone, phoneToken, DeviceMode.PERSONAL).status, "it holds only mali")
            assertTrue("mode: personal" in deviceFile(scene.maliPhone))

            // A device the caller is not on is not theirs to change.
            assertEquals(HttpStatusCode.NotFound, mode(scene.khamTablet, phoneToken, DeviceMode.SHARED).status)
            assertTrue("mode: personal" in deviceFile(scene.khamTablet))

            // Once kham is off the shared device, mali may make it personal.
            val bossToken = token(scene.boss, "boss", secretIsPassword = true)
            assertEquals(HttpStatusCode.NoContent, deletePath("/api/v1/devices/${scene.maliShared.deviceId}?user=${scene.khamId}", bossToken).status)
            assertEquals(HttpStatusCode.NoContent, mode(scene.maliShared, sharedToken, DeviceMode.PERSONAL).status)
            assertTrue(audit().contains("device.mode user=mali device=${scene.maliPhone.deviceId} ip=localhost result=ok,mode=shared"), audit())

            assertEquals(HttpStatusCode.BadRequest, putJson("/api/v1/devices/${scene.maliPhone.deviceId}/mode", String.serializer(), "x", phoneToken).status)
            assertEquals(HttpStatusCode.Unauthorized, mode(scene.maliPhone, "nope", DeviceMode.SHARED).status)
        }
    }

    // --- /config ---

    @Test
    fun configGivesTheCallerThePolicyTheEndpointsAndTheCurrencies() = AuthEnv(root).run {
        val scene = Scene(this)
        api {
            val mali = getPath("/api/v1/config", token(scene.maliPhone, "mali"))
            val boss = getPath("/api/v1/config", token(scene.boss, "boss", secretIsPassword = true))

            assertEquals(HttpStatusCode.OK, mali.status)
            val config = mali.parsed(ConfigResponse.serializer())
            assertEquals(6, config.auth.pinLength)
            assertFalse(config.auth.passwordRequired)
            assertEquals(3, config.auth.autoLockSharedMinutes)
            assertEquals(15, config.auth.autoLockPersonalMinutes)
            assertTrue(config.auth.biometricsPersonal)
            assertEquals(5, config.auth.reauthWindowMinutes)
            assertEquals(listOf("shop.example.com:25655", "192.168.1.20:25655"), config.endpoints)
            assertEquals(listOf(CurrencyInfo("LAK", 0), CurrencyInfo("THB", 2), CurrencyInfo("USD", 2)), config.currencies)
            val bossConfig = boss.parsed(ConfigResponse.serializer())
            assertTrue(bossConfig.auth.passwordRequired, "per caller")
            assertTrue(config.userId.isNotBlank() && config.userId != bossConfig.userId, "the caller's own user id")
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/config").status)
        }
    }

    @Test
    fun configFollowsTheServerConfigAfterAReload() {
        val env = AuthEnv(root)
        val mali = env.enroll("mali")
        env.api {
            val token = env.token(mali, "mali")
            Files.writeString(
                root.resolve("config/shoparchive.yml"),
                "config-version: 1\nauth:\n  device:\n    auto-lock-shared-minutes: 7\n    biometrics-personal: false\n  session:\n    reauth-window-minutes: 9\n  backoff:\n    start-seconds: 0\n",
            )
            env.console("reload")

            val config = getPath("/api/v1/config", token).parsed(ConfigResponse.serializer())

            assertEquals(7, config.auth.autoLockSharedMinutes)
            assertFalse(config.auth.biometricsPersonal)
            assertEquals(9, config.auth.reauthWindowMinutes)
        }
    }

    // --- the checks routes use ---

    @Test
    fun requirePermissionRefusesWhoLacksTheNodeAndAnOpHasEvery() = AuthEnv(root).run {
        val mali = enroll("mali")
        addUser("boss", op = true)
        val boss = enroll("boss")
        val maliPrincipal = authService().authenticate(token(mali, "mali"))!!
        val bossPrincipal = authService().authenticate(token(boss, "boss", secretIsPassword = true))!!

        val refused = assertFailsWith<ApiException> { services.requirePermission(maliPrincipal, USERS_MANAGE_NODE) }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals(ErrorCode.FORBIDDEN, refused.code)
        services.requirePermission(maliPrincipal, "test.view") // a node that is on by default
        services.requirePermission(bossPrincipal, USERS_MANAGE_NODE)
        users.setUserPermission("mali", USERS_MANAGE_NODE, true)
        services.requirePermission(maliPrincipal, USERS_MANAGE_NODE)
    }

    @Test
    fun requireBranchPassesTheBranchesOnTheAccountAndWhoHoldsBranchAllOrIsOp() = AuthEnv(root).run {
        nodes.register(PermissionNode(BRANCH_ALL_NODE, "Every branch", default = false)) // P06 registers it; here it stands in
        addUser("mali")
        users.addBranch("mali", "market")
        val mali = authService().authenticate(token(enroll("mali"), "mali"))!!
        addUser("kham")
        users.setUserPermission("kham", BRANCH_ALL_NODE, true)
        val kham = authService().authenticate(token(enroll("kham"), "kham"))!!
        addUser("boss", op = true)
        val boss = authService().authenticate(token(enroll("boss"), "boss", secretIsPassword = true))!!

        services.requireBranch(mali, "market")
        assertEquals(HttpStatusCode.Forbidden, assertFailsWith<ApiException> { services.requireBranch(mali, "airport") }.status)
        services.requireBranch(kham, "airport")
        services.requireBranch(boss, "anything")
        assertNull(users.find("kham")!!.branches.firstOrNull(), "branch.all is not a branch on the account")
        // A branch taken away is taken at the next check, not at the next unlock.
        users.removeBranch("mali", "market")
        assertEquals(HttpStatusCode.Forbidden, assertFailsWith<ApiException> { services.requireBranch(mali, "market") }.status)
    }
}
