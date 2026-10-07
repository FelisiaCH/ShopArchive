package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.shared.ErrorCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val random = SecureRandom()

/** [bytes] random bytes from the system CSPRNG as base64url without padding. */
internal fun randomToken(bytes: Int): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

internal fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

internal fun sha256Hex(text: String): String = sha256(text).joinToString("") { "%02x".format(it) }

/**
 * What an access token stands for: the account [userId], never just a name (a name can be given to another account
 * after a rename). [username] is what the account was called when the token was issued, for display. [verifiedAt] is
 * when the PIN or password was last entered on its behalf ([Instant.EPOCH] if never).
 */
internal class AccessSession(val userId: String, val username: String, val deviceId: String, val expiresAt: Instant, @Volatile var verifiedAt: Instant)

/**
 * Access tokens. They are 256 random bits, live in memory only (a restart signs everyone out, and the devices unlock
 * again), and are kept under their SHA-256, so what sits in memory cannot be sent as a token.
 */
internal class Sessions(private val clock: Clock = Clock.systemUTC()) {
    private val access = ConcurrentHashMap<String, AccessSession>()

    /**
     * Returns the new token and the handle ([Principal.session][xyz.felismp.shoparchive.api.Principal.session]) that names it.
     * [verified]: the PIN or password was entered for it now; if not, it is not recently verified until a re-auth.
     */
    fun issueAccess(userId: String, username: String, deviceId: String, ttl: Duration, verified: Boolean = true): Pair<String, String> {
        val now = clock.instant()
        access.values.removeIf { it.expiresAt <= now }
        val token = randomToken(32)
        val session = sha256Hex(token)
        access[session] = AccessSession(userId, username, deviceId, now.plus(ttl), verifiedAt = if (verified) now else Instant.EPOCH)
        return token to session
    }

    fun access(token: String): Pair<String, AccessSession>? {
        if (token.length > MAX_TOKEN_CHARS) return null
        val session = sha256Hex(token)
        return accessBySession(session)?.let { session to it }
    }

    fun accessBySession(session: String): AccessSession? {
        val found = access[session] ?: return null
        if (found.expiresAt <= clock.instant()) {
            access.remove(session)
            return null
        }
        return found
    }

    /** Whether the PIN or password was entered for [session] less than [window] ago. */
    fun verifiedWithin(session: String, window: Duration): Boolean {
        val verifiedAt = accessBySession(session)?.verifiedAt ?: return false
        return verifiedAt.plus(window) > clock.instant()
    }

    /** @throws ApiError [ErrorCode.REAUTH_REQUIRED] unless the PIN or password was entered for [session] within [window] */
    fun requireVerified(session: String, window: Duration) {
        if (!verifiedWithin(session, window)) throw ApiError(401, ErrorCode.REAUTH_REQUIRED, "Enter the PIN or password again to do this.")
    }

    fun markVerified(session: String) {
        accessBySession(session)?.verifiedAt = clock.instant()
    }

    fun drop(session: String) {
        access.remove(session)
    }

    /** Ends every access token of the account [userId] on [deviceId] - or on every device if [deviceId] is null. */
    fun revokeAccess(userId: String, deviceId: String?) {
        access.values.removeIf { it.userId == userId && (deviceId == null || it.deviceId == deviceId) }
    }

    // One lock per account for everything that changes who the account is on a device: login, and the admin's reset and disable.
    private val accountLocks = ConcurrentHashMap<String, Any>()

    /**
     * Runs [block] holding the lock of the account [userId]. A login in flight finishes before the block starts (and the block
     * then undoes it); one that starts later sees what the block did. Lock order is this one, then the user store's.
     */
    inline fun <T> withAccount(userId: String, block: () -> T): T = synchronized(accountLock(userId), block)

    fun accountLock(userId: String): Any = accountLocks.computeIfAbsent(userId) { Any() }

    private companion object {
        /** Real tokens are 43 characters; anything longer is not one and is not worth hashing. */
        const val MAX_TOKEN_CHARS = 256
    }
}
