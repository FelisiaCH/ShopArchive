package xyz.felismp.shoparchive.api

import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import xyz.felismp.shoparchive.shared.WsMessage

/**
 * How a service refuses a request: the HTTP [status] and the [code] of the shared error envelope. [message] is shown to the caller, so it must not hold a secret.
 * [retryAfterSeconds] becomes the `Retry-After` header; [passwordRequired] tells the app that a PIN will not do.
 * [reason] is a key from [xyz.felismp.shoparchive.shared.ErrorReasons] that the app words in the user's language; leave it null when the [code] says enough.
 * [pinLength] goes to [xyz.felismp.shoparchive.shared.ErrorResponse.pinLength].
 */
class ApiError(
    val status: Int,
    val code: ErrorCode,
    message: String,
    val retryAfterSeconds: Int? = null,
    val passwordRequired: Boolean = false,
    val reason: String? = null,
    val pinLength: Int? = null,
) : Exception(message)

/**
 * The signed-in caller of an API request: the user and the device they unlocked on. [session] names the access token
 * without being it, so it is safe to keep and to log. [userId] is the account and never changes; [username] is what it
 * was called when the request was authenticated, for display and audit only - a rename can give that name to another
 * account, so a decision about who may do what must use [userId].
 */
class Principal(val userId: String, val username: String, val deviceId: String, val session: String) {
    override fun toString() = "$username@$deviceId"
}

/** Logging users in on devices, unlocking them and telling who an access token belongs to. */
interface AuthService {
    /**
     * Signs a user in on a device by name and PIN (or password); a user who has none yet sets it here.
     * @throws ApiError the user or secret is wrong (the same answer for both), the user has no PIN and sent no new one
     * ([xyz.felismp.shoparchive.shared.ErrorReasons.PIN_NOT_SET]), the device is not known ([ErrorCode.DEVICE_NOT_RECOGNIZED]), or a new secret is not acceptable
     */
    fun login(request: LoginRequest, ip: String): LoginResponse

    /** @throws ApiError the device, user or secret is wrong; the answer is the same for all of them */
    fun unlock(request: UnlockRequest, ip: String): UnlockResponse

    /** The caller an access token belongs to, or null if it is unknown, expired, or its user or device was disabled or revoked. */
    fun authenticate(accessToken: String): Principal?

    /** Whether [principal] may still be served: its token has not expired and its user and device are still allowed. */
    fun isValid(principal: Principal): Boolean

    fun hasPermission(principal: Principal, node: String): Boolean

    /** Whether [principal] works in [branch]: it is on the account's branch list, or the account holds `shoparchive.branch.all` (an op holds every node). */
    fun canAccessBranch(principal: Principal, branch: String): Boolean

    /** @throws ApiError [ErrorCode.REAUTH_REQUIRED] unless the caller entered the PIN or password within the re-auth window; for actions that are hard to take back */
    fun requireRecentAuth(principal: Principal)

    /** Checks the PIN or password again and remembers the time. @throws ApiError the secret is wrong */
    fun reauth(principal: Principal, request: ReauthRequest, ip: String)
}

/** The devices of the signed-in caller. */
interface DeviceService {
    fun list(principal: Principal): List<DeviceInfo>

    /**
     * Takes the account [userId] off device [deviceId] and ends its tokens there. The caller has already been checked for
     * the permission to do this to another user; the caller must have entered the PIN or password within the re-auth window.
     * @throws ApiError the device or the user on it is not found, or [ErrorCode.REAUTH_REQUIRED]
     */
    fun revoke(principal: Principal, deviceId: String, userId: String, ip: String)

    /** @throws ApiError the caller is not on the device, or it holds other users and cannot become personal */
    fun setMode(principal: Principal, deviceId: String, mode: DeviceMode, ip: String)
}

/** What `GET /api/v1/config` answers for the signed-in caller. */
interface ClientConfigService {
    fun clientConfig(principal: Principal): ConfigResponse
}

/** What the server pushes to open WebSockets. */
interface EventService {
    /** Registers an open socket; [send] takes one JSON text and says whether it was accepted. Close the result when the socket ends. */
    fun register(principal: Principal, send: (String) -> Boolean): AutoCloseable

    /** Sends [message] to every open socket and returns how many. */
    fun broadcast(message: WsMessage): Int

    /** Sends [message] to the open sockets whose principal [wanted] accepts, and returns how many. */
    fun broadcastTo(message: WsMessage, wanted: (Principal) -> Boolean): Int

    /** Sends [message] to the open sockets of the account [userId], except those of [exceptDeviceId]. */
    fun notifyUser(userId: String, message: WsMessage, exceptDeviceId: String? = null)

    fun openConnections(): Int
}
