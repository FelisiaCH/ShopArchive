package xyz.felismp.shoparchive.server.auth

import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.CertificateService
import xyz.felismp.shoparchive.api.PairingService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.users.UserStore
import xyz.felismp.shoparchive.server.users.isUsableCredential
import xyz.felismp.shoparchive.server.users.isValidUserName
import xyz.felismp.shoparchive.shared.CreatePairingRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.PairPayload
import xyz.felismp.shoparchive.shared.PairingResponse
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.RedeemResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** RFC 8628's user-code alphabet: no vowels (so no words) and none of the letters that look like digits. 20 letters x 10 places is 43 bits. */
internal const val CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ"
internal const val CODE_LENGTH = 10

/** What the manual code looks like when shown: `XXXXX-XXXXX`. */
internal fun displayCode(code: String) = code.substring(0, CODE_LENGTH / 2) + "-" + code.substring(CODE_LENGTH / 2)

/** [typed] as the 10 letters it stands for, or null if it cannot be a code. Case, spaces and the dash do not matter. */
internal fun normalizeCode(typed: String?): String? {
    val code = typed?.filter { it != '-' && !it.isWhitespace() }?.uppercase()
    return code?.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
}

private const val SECRET_BYTES = 16
private const val LINK_PREFIX = "shoparchive://pair?d="

/** The link a QR code or a tap opens. The pin ([PairPayload.fp]) is always in it: a device never trusts a server it has not been told the key of. */
internal fun pairingLink(payload: PairPayload): String {
    val json = Json { encodeDefaults = true }.encodeToString(PairPayload.serializer(), payload)
    return LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
}

/**
 * A pairing waiting to be redeemed, for the account [userId] ([username] is what it was called when the pairing was made).
 * Only hashes of the secret and the code are kept; the people holding them are the only ones who know them.
 */
private class Pending(
    val id: String,
    val userId: String,
    val username: String,
    val secretHash: ByteArray,
    val codeHash: ByteArray,
    val expiresAt: Instant,
) {
    var wrongCodes = 0
    var png: Path? = null
}

/** A new pairing as the caller shows it. [id] names it without being a secret. */
internal class CreatedPairing(val id: String, val response: PairingResponse)

/**
 * Pairings: made from the console or the API, redeemed for an enrollment token. They live in memory only, so a restart
 * ends them all; a new pairing for a user replaces that user's old one. A pairing belongs to the account, not the name:
 * after a rename it is still that account's, and the new owner of the old name gets nothing from it.
 */
internal class DefaultPairingService(
    private val root: Path,
    private val config: ConfigService,
    private val users: UserStore,
    private val policy: Policy,
    private val sessions: Sessions,
    private val audit: AuditLog,
    private val services: ServiceRegistry,
    private val serverId: UUID,
    private val endpoints: () -> List<String>,
    private val clock: Clock = Clock.systemUTC(),
) : PairingService {
    private val pending = HashMap<String, Pending>() // by user id
    private val random = SecureRandom()

    // Only used to delete a QR image when its pairing runs out, even if nobody calls the server again.
    private val timer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "pairing-expiry").apply { isDaemon = true } }
    }

    @Synchronized
    fun pendingCount(): Int {
        purge()
        return pending.size
    }

    @Synchronized private fun sweep() = purge()

    override fun create(requester: Principal?, request: CreatePairingRequest, ip: String): PairingResponse =
        createPairing(requester, request.username, ip).response

    fun createPairing(requester: Principal?, username: String, ip: String): CreatedPairing {
        // The caller is the account the session was issued to, not whoever holds its name now: a rename may have
        // happened while the request body was still arriving.
        val caller = requester?.let { principal ->
            users.findById(principal.userId)?.takeIf { it.second.enabled }
                ?: throw ApiError(401, ErrorCode.UNAUTHORIZED, "The session has ended. Unlock again.")
        }
        val source = when {
            requester == null -> "console"
            users.find(username)?.id == requester.userId -> "self"
            else -> "admin"
        }
        if (source !in config.auth.pairingSources) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "Pairing from the $source is turned off (auth.pairing.sources).")
        }
        if (source == "admin" && !users.hasPermissionById(requester!!.userId, PAIR_NODE)) {
            throw ApiError(403, ErrorCode.FORBIDDEN, "Creating a pairing for another user needs $PAIR_NODE.")
        }
        if (requester != null) {
            // Creating a way into an account is the thing a stolen, still-unlocked device must not be able to do on its own.
            sessions.requireVerified(requester.session, Duration.ofMinutes(config.auth.reauthWindowMinutes.toLong()))
        }
        val user = users.find(username) ?: throw ApiError(404, ErrorCode.NOT_FOUND, "No such user.")
        if (!user.enabled) throw ApiError(400, ErrorCode.INVALID_REQUEST, "That user is disabled.")
        val fingerprint = services.get(CertificateService::class.java)?.fingerprint
            ?: throw ApiError(500, ErrorCode.INTERNAL, "The server has no certificate yet.")

        val secret = randomToken(SECRET_BYTES)
        val code = (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        val expiresAt = clock.instant().plus(Duration.ofMinutes(config.auth.pairingTtlMinutes.toLong()))
        val id = randomToken(8)
        val link = pairingLink(PairPayload(1, serverId.toString(), fingerprint.replace(" ", ""), secret, username, endpoints().take(2)))
        synchronized(this) {
            purge()
            pending.remove(user.id)?.let(::forget)
            pending[user.id] = Pending(id, user.id, username, sha256(secret), sha256(code), expiresAt)
        }
        timer.schedule({ sweep() }, Duration.between(clock.instant(), expiresAt).toMillis() + 50, TimeUnit.MILLISECONDS)
        audit.record("pair.created", username, requester?.deviceId, ip, "ok,source=$source" + (caller?.let { ",by=${it.first}" } ?: ""))
        return CreatedPairing(
            id, PairingResponse(link, if (config.auth.manualCode) displayCode(code) else null, fingerprint, expiresAt.toString()),
        )
    }

    /** Drops the pairing waiting for the account [userId], if any (`user reset` and `user disable`: only the pairing made afterwards counts). */
    @Synchronized
    fun cancel(userId: String) {
        pending.remove(userId)?.let(::forget)
    }

    /** Remembers an image file made for pairing [id]; it is deleted when the pairing is redeemed, replaced or expires. */
    @Synchronized
    fun attachImage(id: String, file: Path) {
        val owner = pending.values.firstOrNull { it.id == id }
        if (owner == null) Files.deleteIfExists(file) else owner.png = file
    }

    override fun redeem(request: RedeemRequest, ip: String): RedeemResponse {
        val found = find(request)
        // Under the account lock, so `user reset` and `user disable` (which hold it while they revoke) cannot finish between taking the pairing and issuing the grant.
        val redeemed = found?.let { candidate ->
            sessions.withAccount(candidate.userId) {
                val resolved = if (consume(candidate)) users.findById(candidate.userId)?.takeIf { it.second.enabled } else null
                resolved?.let { (username, user) ->
                    val ttl = Duration.ofMinutes(config.auth.pairingTtlMinutes.toLong())
                    RedeemResponse(
                        enrollmentToken = sessions.issueEnrollment(user.id, username, ttl),
                        username = username,
                        passwordRequired = policy.passwordRequired(username),
                        hasPassword = isUsableCredential(user.password),
                        hasPin = isUsableCredential(user.pin),
                        pinLength = config.auth.pinLength,
                        suggestedPasswordMin = config.auth.passwordMin,
                        serverName = config.serverName,
                        expiresInSeconds = ttl.seconds,
                    )
                }
            }
        }
        if (redeemed == null) {
            val named = request.username?.takeIf { request.secret == null }
            audit.record("redeem.fail", named, null, ip, "invalid")
            throw ApiError(401, ErrorCode.PAIRING_INVALID, "This pairing is not valid. Ask for a new one.")
        }
        audit.record("redeem.ok", redeemed.username, null, ip, "ok")
        return redeemed
    }

    /** Finds the pairing a request names without using it up, or counts a wrong code. */
    @Synchronized
    private fun find(request: RedeemRequest): Pending? {
        purge()
        val secret = request.secret
        return if (secret != null) {
            val hash = sha256(secret.take(MAX_INPUT_CHARS))
            pending.values.firstOrNull { MessageDigest.isEqual(hash, it.secretHash) }
        } else {
            manualMatch(request)
        }
    }

    /** Uses up [candidate] if it is still the one waiting; a reset, a disable or a newer pairing may have taken its place. */
    @Synchronized
    private fun consume(candidate: Pending): Boolean {
        purge()
        if (pending[candidate.userId] !== candidate) return false
        pending.remove(candidate.userId)
        forget(candidate)
        return true
    }

    private fun manualMatch(request: RedeemRequest): Pending? {
        if (!config.auth.manualCode) return null
        val name = request.username?.takeIf { it.length <= MAX_INPUT_CHARS && isValidUserName(it) }
        val candidate = name?.let(users::find)?.let { pending[it.id] } ?: return null
        if (candidate.wrongCodes >= config.auth.manualCodeAttempts) return null
        val code = normalizeCode(request.code)
        if (code != null && MessageDigest.isEqual(sha256(code), candidate.codeHash)) return candidate
        candidate.wrongCodes++
        return null
    }

    /** Drops what has run out. Call with the lock held. */
    private fun purge() {
        val now = clock.instant()
        val gone = pending.values.filter { it.expiresAt <= now }
        gone.forEach { pending.remove(it.userId); forget(it) }
    }

    private fun forget(p: Pending) {
        p.png?.let { runCatching { Files.deleteIfExists(it) } }
    }

    private companion object {
        /** Real secrets are 22 characters and user names at most 32. */
        const val MAX_INPUT_CHARS = 128
    }
}

/** The permission to pair someone else's account. */
internal const val PAIR_NODE = "shoparchive.devices.pair"
