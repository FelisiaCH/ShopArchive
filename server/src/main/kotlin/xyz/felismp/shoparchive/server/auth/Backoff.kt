package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.users.DISABLED_BY_BACKOFF
import xyz.felismp.shoparchive.server.users.UserData
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.shared.ErrorCode
import java.time.Clock
import java.time.Duration

/**
 * The delay between wrong PINs or passwords on one account, whichever device they come from. The count and the lock are
 * in the user's file, so a restart does not clear them. While an account is locked a try is refused before any hash is
 * computed, so guessing costs the guesser time and the server nothing.
 */
internal class Backoff(
    private val config: ConfigService,
    private val users: UserStore,
    private val sessions: Sessions,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** @throws ApiError 429 with `Retry-After` while the account is locked */
    fun requireNotLocked(user: UserData, event: String, username: String, deviceId: String?, ip: String) {
        val until = (users.findById(user.id)?.second ?: user).lockedUntil ?: return
        val wait = Duration.between(clock.instant(), until)
        if (wait.isNegative || wait.isZero) return
        val seconds = ((wait.toMillis() + 999) / 1000).toInt()
        audit.record("$event.locked", username, deviceId, ip, "retry-after=$seconds")
        throw ApiError(429, ErrorCode.RATE_LIMITED, "Too many wrong tries. Try again in $seconds seconds.", retryAfterSeconds = seconds)
    }

    /** A wrong PIN or password. The next try is delayed twice as long as the last; at `auth.backoff.disable-at` (unless 0) the account is disabled and its tokens end. */
    fun failed(userId: String, username: String, deviceId: String?, ip: String) {
        val settings = config.auth
        val now = clock.instant()
        val after = users.updateLogin(userId) { user ->
            val count = user.failedLogins + 1
            if (settings.backoffDisableAt > 0 && count >= settings.backoffDisableAt) {
                user.copy(failedLogins = count, lockedUntil = null, enabled = false, disabledReason = DISABLED_BY_BACKOFF)
            } else {
                user.copy(failedLogins = count, lockedUntil = delay(count, settings.backoffStartSeconds, settings.backoffMaxMinutes)?.let(now::plus))
            }
        } ?: return
        if (!after.enabled) {
            sessions.revokeAccess(userId, null)
            audit.record("account.disabled", username, deviceId, ip, "too-many-wrong-tries,failed=${after.failedLogins}")
        }
    }

    /** A right PIN or password: the count starts again. */
    fun succeeded(user: UserData) {
        val current = users.findById(user.id)?.second ?: return
        if (current.failedLogins == 0 && current.lockedUntil == null) return
        users.updateLogin(user.id) { it.copy(failedLogins = 0, lockedUntil = null) }
    }

    private fun delay(count: Int, startSeconds: Int, maxMinutes: Int): Duration? {
        if (startSeconds == 0) return null
        val cap = maxMinutes * 60L
        // 60 s doubled 20 times is already far above the 24 h cap; going on would only overflow.
        val seconds = if (count > 20) cap else minOf(startSeconds.toLong() shl (count - 1), cap)
        return Duration.ofSeconds(seconds)
    }
}
