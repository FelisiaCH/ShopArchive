package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.ClientConfigService
import xyz.felismp.shoparchive.api.DeviceService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.shared.AuthPolicy
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.RecordsPolicy
import java.time.Duration

/** The caller's devices: listing them, taking a user off one, and changing its mode. Every answer is about the account (its id), never a name. */
internal class DefaultDeviceService(
    private val config: ConfigService,
    private val users: UserStore,
    private val devices: DeviceStore,
    private val sessions: Sessions,
    private val audit: AuditLog,
) : DeviceService {
    override fun list(principal: Principal): List<DeviceInfo> {
        val (_, user) = caller(principal)
        return devices.devicesOf(user.id).map { (id, device) ->
            val own = device.users.values.first { it.userId == user.id }
            DeviceInfo(id, device.label, device.platform, device.mode, own.lastUsed.toString(), device.users.size, current = id == principal.deviceId)
        }
    }

    override fun revoke(principal: Principal, deviceId: String, userId: String, ip: String) {
        val (name, _) = caller(principal)
        // Taking someone off a device is a way to lock them out, the thing a stolen, unlocked phone must not do unasked.
        sessions.requireVerified(principal.session, Duration.ofMinutes(config.auth.reauthWindowMinutes.toLong()))
        val onDevice = devices.get(deviceId)?.users?.entries?.firstOrNull { it.value.userId == userId }
        if (onDevice == null || !devices.removeUser(deviceId, userId)) throw notFound()
        sessions.revokeAccess(userId, deviceId)
        audit.record("device.revoked", name, deviceId, ip, "ok,target=${onDevice.key}")
    }

    override fun setMode(principal: Principal, deviceId: String, mode: DeviceMode, ip: String) {
        val (name, user) = caller(principal)
        val device = devices.get(deviceId)?.takeIf { d -> d.users.values.any { it.userId == user.id } } ?: throw notFound()
        if (device.mode == mode) return
        if (mode == DeviceMode.PERSONAL && device.users.values.any { it.userId != user.id }) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "Other users are on this device: take them off first, or keep it shared.", reason = ErrorReasons.DEVICE_HAS_OTHER_USERS)
        }
        if (!devices.setMode(deviceId, mode)) throw notFound()
        audit.record("device.mode", name, deviceId, ip, "ok,mode=${mode.name.lowercase()}")
    }

    /** The account the session was issued to, as it is called now; 401 if it has gone or been disabled since the request was authenticated. */
    private fun caller(principal: Principal) =
        users.findById(principal.userId)?.takeIf { it.second.enabled } ?: throw ApiError(401, ErrorCode.UNAUTHORIZED, "The session has ended. Unlock again.")

    private fun notFound() = ApiError(404, ErrorCode.NOT_FOUND, "No such device.")
}

/** What `GET /config` says about records, branches and categories; the records code answers it. */
internal interface ClientRecordsConfig {
    fun branches(principal: Principal): List<BranchDto>
    fun categories(): List<CategoryDto>
    fun policy(): RecordsPolicy
}

/** For a server (or a test) with no records code: the app sees no branches and the default rules. */
internal object NoRecords : ClientRecordsConfig {
    override fun branches(principal: Principal) = emptyList<BranchDto>()
    override fun categories() = emptyList<CategoryDto>()
    override fun policy() = RecordsPolicy()
}

/** `GET /config`: the policy the app follows, from the live config, so `reload` reaches the apps at their next ask. */
internal class DefaultClientConfigService(
    private val config: ConfigService,
    private val users: UserStore,
    private val policy: Policy,
    private val endpoints: () -> List<String>,
    private val records: ClientRecordsConfig = NoRecords,
) : ClientConfigService {
    override fun clientConfig(principal: Principal): ConfigResponse {
        val auth = config.auth
        val name = users.findById(principal.userId)?.first
        return ConfigResponse(
            auth = AuthPolicy(
                pinLength = auth.pinLength,
                passwordRequired = name != null && policy.passwordRequired(name),
                autoLockSharedMinutes = auth.autoLockSharedMinutes,
                autoLockPersonalMinutes = auth.autoLockPersonalMinutes,
                biometricsPersonal = auth.biometricsPersonal,
                reauthWindowMinutes = auth.reauthWindowMinutes,
                unlockWithoutPin = auth.unlockWithoutPin,
            ),
            endpoints = endpoints().take(2),
            currencies = config.currencies.map { CurrencyInfo(it.code, it.exponent) },
            branches = records.branches(principal),
            categories = records.categories(),
            records = records.policy(),
            // Every registered node the caller holds: an op holds them all, anyone else by role or own setting.
            permissions = users.nodeNames().filter { users.hasPermissionById(principal.userId, it) },
            userId = principal.userId,
        )
    }
}
