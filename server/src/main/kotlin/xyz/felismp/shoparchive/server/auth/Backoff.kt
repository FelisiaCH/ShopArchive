package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.users.DISABLED_BY_BACKOFF
import xyz.felismp.shoparchive.server.users.UserData
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.shared.ErrorCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The delay between wrong PINs or passwords on one account, whichever device they come from. The count and the lock are
 * in the user's file, so a restart does not clear them. While an account is locked a try is refused before any hash is
 * computed, so guessing costs the guesser time and the server nothing.
 *
 * Only tries that come with a device of the account (`known`) count against the account itself. A try from a caller
 * that has proven no device counts in memory (a restart clears it), against the account but separately: it delays only
 * the next anonymous tries, whatever their address, so nobody on the network can keep the owner locked out by guessing as the owner.
 */
internal class Backoff(
    private val config: ConfigService,
    private val users: UserStore,
    private val sessions: Sessions,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** The wrong tries and the lock of the anonymous tries on one account, under its id: every address shares them, so many addresses guess no faster than one. */
    private class Tries(val count: Int, val lockedUntil: Instant)
    private val anonymous = ConcurrentHashMap<String, Tries>()

    /** @throws ApiError 429 with `Retry-After` while the account is locked, or, for a try that is not `known`, while its anonymous tries are locked */
    fun requireNotLocked(user: UserData, event: String, username: String, deviceId: String?, ip: String, known: Boolean = true) {
        val until = listOfNotNull(
            (users.findById(user.id)?.second ?: user).lockedUntil,
            if (known) null else anonymous[user.id]?.lockedUntil,
        ).maxOrNull() ?: return
        val wait = Duration.between(clock.instant(), until)
        if (wait.isNegative || wait.isZero) return
        val seconds = ((wait.toMillis() + 999) / 1000).toInt()
        audit.record("$event.locked", username, deviceId, ip, "retry-after=$seconds")
        throw ApiError(429, ErrorCode.RATE_LIMITED, "Too many wrong tries. Try again in $seconds seconds.", retryAfterSeconds = seconds)
    }

    /** A wrong PIN or password. The next try is delayed twice as long as the last; at `auth.backoff.disable-at` (unless 0) the account is disabled and its tokens end. */
    fun failed(userId: String, username: String, deviceId: String?, ip: String, known: Boolean = true) {
        val settings = config.auth
        val now = clock.instant()
        if (!known) {
            // Never disables the account: anyone who can guess could otherwise switch the owner off.
            if (anonymous.size > 1000) anonymous.values.removeIf { it.lockedUntil.plus(Duration.ofMinutes(settings.backoffMaxMinutes.toLong())).isBefore(now) }
            anonymous.compute(userId) { _, old ->
                val count = (old?.count ?: 0) + 1
                delay(count, settings.backoffStartSeconds, settings.backoffMaxMinutes)?.let { Tries(count, now.plus(it)) }
            }
            return
        }
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

    /** A right PIN or password: the count starts again. The anonymous count ends only with a right try that was anonymous too: the owner's own unlock must not wipe a guesser's delay. */
    fun succeeded(user: UserData, known: Boolean = true) {
        if (!known) anonymous.remove(user.id)
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
