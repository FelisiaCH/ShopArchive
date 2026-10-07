package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.NewDeviceEvent
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.records.stamp
import xyz.felismp.shoparchive.server.users.UserData
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.server.users.isUsableCredential
import xyz.felismp.shoparchive.server.users.isValidUserName
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.DevicePairedMessage
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.Refusal
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** The permission to work in every branch; registered with the other record nodes. */
internal const val BRANCH_ALL_NODE = "shoparchive.branch.all"

/**
 * Logging a user in on a device, unlocking it, and knowing who an access token belongs to. Every refusal that could tell a guesser
 * something (which of device, user or secret was wrong) is the same [ApiError]. The one exception is adding a user to a
 * shared device: there a device id or credential that is not accepted is told apart ([ErrorCode.DEVICE_NOT_RECOGNIZED]) from a
 * wrong PIN, so the app does not have to try one stored credential after another with the PIN. A device credential is 256 random
 * bits, so saying "not recognized" gives a guesser nothing; the PIN is not even looked at until the device is accepted.
 */
internal class DefaultAuthService(
    private val config: ConfigService,
    private val users: UserStore,
    private val policy: Policy,
    private val hasher: Hasher,
    private val sessions: Sessions,
    private val devices: DeviceStore,
    private val audit: AuditLog,
    private val services: ServiceRegistry,
    private val backoff: Backoff,
    private val clock: Clock = Clock.systemUTC(),
    private val barrier: DataBarrier = DataBarrier(),
) : AuthService {
    // One wrong-secret check per (device, user) at a time: the count of wrong tries must not be raced past by parallel guesses.
    private val locks = ConcurrentHashMap<String, Any>()

    override fun login(request: LoginRequest, ip: String): LoginResponse {
        val username = request.username.takeIf { it.length <= 32 && isValidUserName(it) }
        val found = username?.let(users::find)?.takeIf { it.enabled }
        // Unknown, disabled or not a name at all: the same work and the same answer as a wrong PIN.
        val unknown = {
            hasher.verify("-", null)
            audit.record("login.fail", username, request.deviceId, ip, "unknown")
            unauthorized("Login failed.")
        }
        if (username == null || found == null) throw unknown()
        // One login per account at a time: two first logins must not both find the account without a PIN and each set one.
        // The admin's reset and disable take the same lock, so they never run between this call's checks and its writes.
        return sessions.withAccount(found.id) {
            // Read again under the lock: what the account has is decided here. A rename meanwhile makes the name unknown.
            val user = users.findFreshById(found.id)?.takeIf { it.first == username && it.second.enabled }?.second ?: throw unknown()
            // The device first: a device that is not accepted costs the account no wrong try, and the PIN is not tested against it.
            val existing = existingDevice(request, username, ip)
            proveExisting(username, user, request, ip)
            // This tells whoever asks that the name exists and has no PIN; the app needs it to ask for a new PIN.
            if (!isUsableCredential(user.pin) && request.newPin == null) {
                throw ApiError(401, ErrorCode.UNAUTHORIZED, "Set a PIN.", reason = ErrorReasons.PIN_NOT_SET)
            }
            // Already on the device (logging in again there): nothing new for the owner to hear about.
            val wasOn = existing?.second?.users?.get(username)?.userId == user.id
            addToDevice(username, user, request, existing, newSecrets(username, user, request), ip).also {
                if (!wasOn) publishNewDevice(username, user, it.deviceId)
            }
        }
    }

    /** Tells the listeners of the shop events that [username] is on the device [deviceId] now, after it was saved. It cannot fail the login that already wrote it. */
    private fun publishNewDevice(username: String, user: UserData, deviceId: String) {
        try {
            val device = devices.get(deviceId) ?: return
            services.get(ShopEvents::class.java)?.publish(
                NewDeviceEvent(username, deviceId, device.label, device.platform, user.branches.firstOrNull() ?: "", stamp(clock, config)),
            )
        } catch (e: Exception) {
            Log.warn("could not publish device.new: ${e.message}")
        }
    }

    override fun unlock(request: UnlockRequest, ip: String): UnlockResponse {
        val deviceId = request.deviceId.takeIf(::isUuid)
        val username = request.username.takeIf { it.length <= 32 && isValidUserName(it) }
        val user = username?.let(users::find)?.takeIf { it.enabled }
        // Unknown device, user or credential: the same work and the same answer as a wrong PIN, so they cannot be told apart.
        if (deviceId == null || username == null || user == null || !devices.credentialMatches(deviceId, username, user.id, request.credential)) {
            hasher.verify("-", null)
            // The credential or the block under this name belongs to another account: the name has changed hands since. Only the admin can read this.
            val reused = deviceId != null && username != null && user != null &&
                (devices.blockOwner(deviceId, username) ?: devices.ownerOf(deviceId, request.credential))?.let { it != user.id } == true
            audit.record("unlock.fail", username, deviceId, ip, if (reused) "identity-mismatch" else "unknown")
            throw unauthorized("Unlock failed.")
        }
        if (idleExpired(deviceId, username, user)) {
            // Same work and same answer as a wrong PIN; the entry goes, so the user must log in on this device again.
            hasher.verify("-", null)
            devices.removeUser(deviceId, user.id)
            sessions.revokeAccess(user.id, deviceId)
            audit.record("device.expired", username, deviceId, ip, "idle")
            throw unauthorized("Unlock failed.")
        }
        val ttl = Duration.ofMinutes(config.auth.accessTokenMinutes.toLong())
        if (request.pin == null && request.password == null && unlocksWithoutPin(deviceId, username)) {
            // No secret was tried, so no wrong count and no lock: a lockout does not shut the owner out of their own phone.
            // The session is not recently verified: what asks for the PIN again still does.
            devices.recordUse(deviceId, username, user.id)
            val (token, _) = sessions.issueAccess(user.id, username, deviceId, ttl, verified = false)
            audit.record("unlock.ok", username, deviceId, ip, "ok,credential-only")
            return UnlockResponse(token, ttl.seconds.toInt())
        }
        checkSecret(deviceId, username, user, request.pin, request.password, ip, "unlock")
        val (token, _) = sessions.issueAccess(user.id, username, deviceId, ttl)
        audit.record("unlock.ok", username, deviceId, ip, "ok")
        return UnlockResponse(token, ttl.seconds.toInt())
    }

    /** Whether the device credential alone unlocks: it is on (`auth.device.unlock-without-pin`), the device holds one user, and that user needs no password. */
    private fun unlocksWithoutPin(deviceId: String, username: String): Boolean =
        config.auth.unlockWithoutPin && devices.get(deviceId)?.users?.size == 1 && !policy.passwordRequired(username)

    override fun authenticate(accessToken: String): Principal? {
        val (session, found) = sessions.access(accessToken) ?: return null
        return principalOf(session, found)
    }

    /** Compared by account id: a rename keeps the session valid for the same account, and never makes it valid for whoever takes the old name. */
    override fun isValid(principal: Principal): Boolean {
        val found = sessions.accessBySession(principal.session) ?: return false
        return principalOf(principal.session, found)?.userId == principal.userId
    }

    override fun hasPermission(principal: Principal, node: String): Boolean {
        val account = sessions.accessBySession(principal.session)?.let { users.findById(it.userId) } ?: return false
        return users.hasPermission(account.first, node)
    }

    override fun canAccessBranch(principal: Principal, branch: String): Boolean {
        val account = sessions.accessBySession(principal.session)?.let { users.findById(it.userId) } ?: return false
        return branch in account.second.branches || users.hasPermission(account.first, BRANCH_ALL_NODE)
    }

    override fun requireRecentAuth(principal: Principal) =
        sessions.requireVerified(principal.session, Duration.ofMinutes(config.auth.reauthWindowMinutes.toLong()))

    override fun reauth(principal: Principal, request: ReauthRequest, ip: String) {
        val (username, user) = sessions.accessBySession(principal.session)?.let { users.findById(it.userId) }?.takeIf { it.second.enabled && isValid(principal) }
            ?: throw unauthorized("The session has ended. Unlock again.")
        checkSecret(principal.deviceId, username, user, request.pin, request.password, ip, "reauth")
        sessions.markVerified(principal.session)
        audit.record("reauth.ok", username, principal.deviceId, ip, "ok")
    }

    /** The caller a live session stands for - unless its account was disabled or taken off the device since. It is named as the account is called now. */
    private fun principalOf(session: String, found: AccessSession): Principal? {
        val account = users.findById(found.userId)
        if (account == null || !account.second.enabled || !devices.hasUser(found.deviceId, account.first, found.userId)) {
            sessions.drop(session)
            return null
        }
        return Principal(found.userId, account.first, found.deviceId, session)
    }

    /**
     * The user's password if they need one, else their PIN, against the hash on file. Wrong ones are counted per user and
     * device (at `auth.pin.max-failures`, unless 0, the user is taken off the device and its tokens end) and per account (the delay
     * between tries, [Backoff]). A locked account is refused before any hash is computed.
     */
    private fun checkSecret(deviceId: String, username: String, user: UserData, pin: String?, password: String?, ip: String, event: String) {
        // The account first, then the device, everywhere: tries on one account are one after the other, so none slips past the lock.
        sessions.withAccount(user.id) {
            synchronized(locks.computeIfAbsent("$deviceId/${user.id}") { Any() }) {
                backoff.requireNotLocked(user, event, username, deviceId, ip)
                val required = policy.passwordRequired(username)
                // Not a wrong try: the PIN was not wrong, it is just not enough today. The app asks for the password and tries again.
                val due = event == "unlock" && !required && passwordDue(deviceId, username, user)
                if (due && password == null) {
                    audit.record("$event.reauth-required", username, deviceId, ip, "password-due")
                    throw ApiError(401, ErrorCode.REAUTH_REQUIRED, "Enter your password to unlock.", passwordRequired = true)
                }
                val needsPassword = required || due
                // The same for a user who always needs the password: a device that only knows to send the PIN is told, not counted wrong.
                if (needsPassword && password == null) {
                    audit.record("$event.reauth-required", username, deviceId, ip, "password-required")
                    throw ApiError(401, ErrorCode.REAUTH_REQUIRED, "Enter your password to unlock.", passwordRequired = true)
                }
                val given = if (needsPassword) password else pin
                val stored = if (needsPassword) user.password else user.pin
                val ok = given != null && policy.fitsToVerify(given, needsPassword) &&
                    hasher.verify(if (needsPassword) normalizePassword(given) else given, stored?.takeIf(::isUsableCredential))
                if (ok) {
                    // The PIN is the full check only for a user who has no password.
                    devices.recordSuccess(deviceId, username, user.id, fullVerification = needsPassword || !isUsableCredential(user.password))
                    backoff.succeeded(user)
                    return
                }
                if (given == null || !policy.fitsToVerify(given, needsPassword)) hasher.verify("-", null) // same cost as a real check
                audit.record("$event.fail", username, deviceId, ip, "wrong-secret")
                val removed = devices.recordFailure(deviceId, username, user.id, config.auth.pinMaxFailures)
                if (removed) {
                    sessions.revokeAccess(user.id, deviceId)
                    audit.record("revoke.pin-failures", username, deviceId, ip, "user-removed-from-device")
                }
                backoff.failed(user.id, username, deviceId, ip)
                throw unauthorized(if (event == "unlock") "Unlock failed." else "Wrong PIN or password.")
            }
        }
    }

    /** Whether the user has a password that must be entered at this unlock: it was last entered too long ago, or the user has not unlocked this device for long. A limit of 0 is off. */
    private fun passwordDue(deviceId: String, username: String, user: UserData): Boolean {
        if (!isUsableCredential(user.password)) return false
        val entry = devices.entry(deviceId, username, user.id) ?: return false
        val now = clock.instant()
        val settings = config.auth
        return (settings.reauthEveryDays > 0 && !entry.lastVerified.plus(Duration.ofDays(settings.reauthEveryDays.toLong())).isAfter(now)) ||
            (settings.reauthIdleDays > 0 && !entry.lastUsed.plus(Duration.ofDays(settings.reauthIdleDays.toLong())).isAfter(now))
    }

    private fun idleExpired(deviceId: String, username: String, user: UserData): Boolean {
        if (config.auth.deviceIdleExpiryDays == 0) return false
        val entry = devices.entry(deviceId, username, user.id) ?: return false
        return !entry.lastUsed.plus(Duration.ofDays(config.auth.deviceIdleExpiryDays.toLong())).isAfter(clock.instant())
    }

    /** The device [request] adds the user to, by its id and one of its credentials, or null for a new device; an id that is not accepted is refused. */
    private fun existingDevice(request: LoginRequest, username: String, ip: String): Pair<String, DeviceData>? =
        request.deviceId?.let { id ->
            val device = devices.get(id)
            val owner = request.deviceCredential?.let { devices.ownerOf(id, it) }
            if (device == null || owner == null || users.findById(owner)?.second?.enabled != true) {
                audit.record("login.fail", username, id, ip, "device-not-recognized")
                throw ApiError(401, ErrorCode.DEVICE_NOT_RECOGNIZED, "This device is not recognized for that user.")
            }
            id to device
        }

    /** The password and PIN the user already has, each against [request]; a wrong one counts against the account ([Backoff]) and is refused. */
    private fun proveExisting(username: String, user: UserData, request: LoginRequest, ip: String) {
        val hasPassword = isUsableCredential(user.password)
        val hasPin = isUsableCredential(user.pin)
        val proves = hasPassword || hasPin
        if (proves) backoff.requireNotLocked(user, "login", username, request.deviceId, ip)
        val passwordOk = !hasPassword || checkExisting(request.password, user.password, password = true)
        val pinOk = !hasPin || checkExisting(request.pin, user.pin, password = false)
        if (!passwordOk || !pinOk) {
            backoff.failed(user.id, username, request.deviceId, ip)
            audit.record("login.fail", username, request.deviceId, ip, "wrong-secret")
            throw unauthorized("Login failed.")
        }
        if (proves) backoff.succeeded(user)
    }

    /** The hashes of the password (if the user needs one) and the PIN the user does not have yet, from [request]; null for what they have. */
    private fun newSecrets(username: String, user: UserData, request: LoginRequest): Pair<String?, String?> {
        val newPassword = if (!isUsableCredential(user.password) && policy.passwordRequired(username)) {
            policy.checkPassword(request.newPassword)?.let { throw invalid(it) }
            hasher.hash(normalizePassword(request.newPassword!!))
        } else {
            null
        }
        val newPin = if (!isUsableCredential(user.pin)) {
            policy.checkPin(request.newPin)?.let { throw invalid(it) }
            hasher.hash(request.newPin!!)
        } else {
            null
        }
        return newPassword to newPin
    }

    /** Stores the [secrets] and puts the user on the [existing] device, or on a new one, with a new credential. */
    private fun addToDevice(
        username: String, user: UserData, request: LoginRequest, existing: Pair<String, DeviceData>?, secrets: Pair<String?, String?>, ip: String,
    ): LoginResponse {
        val (newPassword, newPin) = secrets
        existing?.let { (id, device) ->
            if (device.mode == DeviceMode.PERSONAL && device.users.values.any { it.userId != user.id }) {
                audit.record("login.fail", username, id, ip, "personal-device")
                throw ApiError(403, ErrorCode.FORBIDDEN, "This device is personal: it holds one user.", reason = ErrorReasons.DEVICE_PERSONAL)
            }
        }

        val credential = randomToken(32)
        // The user file and the device file are one change for a backup.
        val (deviceId, label) = barrier.mutate {
            // The user's own file first: if the device file then fails, the secrets chosen here are not lost.
            if ((newPassword != null || newPin != null) && !users.setCredentials(user.id, newPassword, newPin)) {
                throw credentialsChanged(username, request.deviceId, ip)
            }
            if (existing != null) {
                if (!devices.putUser(existing.first, username, user.id, credential)) {
                    audit.record("login.fail", username, existing.first, ip, "device-unavailable")
                    throw ApiError(401, ErrorCode.DEVICE_NOT_RECOGNIZED, "The device is not available. Log in again.")
                }
                existing.first to existing.second.label
            } else {
                val newLabel = clean(request.deviceLabel, 64, "Device")
                devices.create(newLabel, clean(request.platform, 32, "unknown"), request.mode, username, user.id, credential) to newLabel
            }
        }
        audit.record("login.ok", username, deviceId, ip, "ok,mode=${(existing?.second?.mode ?: request.mode).name.lowercase()}")
        events()?.notifyUser(user.id, DevicePairedMessage(deviceId, label), exceptDeviceId = deviceId)
        return LoginResponse(deviceId, credential)
    }

    private fun checkExisting(given: String?, stored: String?, password: Boolean): Boolean {
        if (given == null || !policy.fitsToVerify(given, password)) {
            hasher.verify("-", null)
            return false
        }
        return hasher.verify(if (password) normalizePassword(given) else given, stored)
    }

    private fun credentialsChanged(username: String, deviceId: String?, ip: String): ApiError {
        audit.record("login.fail", username, deviceId, ip, "credentials-changed")
        return ApiError(409, ErrorCode.CREDENTIALS_CHANGED, "This account has a PIN or password now. Enter the existing one and try again.")
    }

    private fun events(): EventService? = services.get(EventService::class.java)

    private fun unauthorized(message: String) = ApiError(401, ErrorCode.UNAUTHORIZED, message)

    private fun invalid(refusal: Refusal) = ApiError(400, ErrorCode.INVALID_REQUEST, refusal.message, reason = refusal.reason)

    /** [text] on one line, trimmed, at most [max] characters; [fallback] if nothing is left. */
    private fun clean(text: String, max: Int, fallback: String): String =
        text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(max).trim().ifEmpty { fallback }
}
