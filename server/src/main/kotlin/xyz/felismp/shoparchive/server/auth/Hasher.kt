package xyz.felismp.shoparchive.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore

/** Argon2id cost. The default is OWASP's smallest recommended setting (46 MiB, one pass, one lane). */
internal class Argon2Cost(val memoryKb: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        val DEFAULT = Argon2Cost(47_104, 1, 1)
    }
}

/**
 * Password and PIN hashing: Argon2id (BouncyCastle) in the PHC string format
 * `$argon2id$v=19$m=47104,t=1,p=1$<salt>$<hash>`. Verifying reads the cost from the stored string, so raising
 * [Argon2Cost.DEFAULT] later does not lock anyone out. Each hash takes tens of MiB, so at most `concurrency`
 * run at once; the rest wait.
 */
internal open class Hasher(concurrency: Int, private val cost: Argon2Cost = Argon2Cost.DEFAULT) {
    private val permits = Semaphore(concurrency)
    private val random = SecureRandom()

    open fun hash(secret: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = compute(secret, salt, cost, HASH_BYTES)
        return "\$argon2id\$v=19\$m=${cost.memoryKb},t=${cost.iterations},p=${cost.parallelism}\$${encode(salt)}\$${encode(hash)}"
    }

    /**
     * Whether [secret] is what [stored] was made from. A [stored] that is null or not a hash this class reads
     * still costs one hash of the same size, so the answer takes as long for a user that has no password as for one that has.
     */
    open fun verify(secret: String, stored: String?): Boolean {
        val parsed = stored?.let(::parse)
        if (parsed == null) {
            compute(secret, ByteArray(SALT_BYTES), cost, HASH_BYTES)
            return false
        }
        return MessageDigest.isEqual(compute(secret, parsed.salt, parsed.cost, parsed.hash.size), parsed.hash)
    }

    private fun compute(secret: String, salt: ByteArray, cost: Argon2Cost, length: Int): ByteArray {
        val input = secret.toByteArray(StandardCharsets.UTF_8)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(cost.iterations)
            .withMemoryAsKB(cost.memoryKb)
            .withParallelism(cost.parallelism)
            .withSalt(salt)
            .build()
        val out = ByteArray(length)
        permits.acquireUninterruptibly()
        try {
            Argon2BytesGenerator().apply { init(params) }.generateBytes(input, out)
        } finally {
            permits.release()
            input.fill(0)
        }
        return out
    }

    private class Parsed(val cost: Argon2Cost, val salt: ByteArray, val hash: ByteArray)

    /** Null for anything that is not a well-formed Argon2id v19 hash with a cost this server is willing to compute. */
    private fun parse(stored: String): Parsed? {
        val match = PHC.matchEntire(stored) ?: return null
        val (m, t, p) = match.groupValues.slice(1..3).map { it.toIntOrNull() ?: return null }
        // A hand-edited file must not be able to make a login cost gigabytes.
        if (m !in 8..MAX_MEMORY_KB || t !in 1..MAX_ITERATIONS || p !in 1..MAX_PARALLELISM || m < 8 * p) return null
        val salt = decode(match.groupValues[4]) ?: return null
        val hash = decode(match.groupValues[5]) ?: return null
        if (salt.size < 8 || hash.size !in 16..64) return null
        return Parsed(Argon2Cost(m, t, p), salt, hash)
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val MAX_MEMORY_KB = 262_144
        const val MAX_ITERATIONS = 10
        const val MAX_PARALLELISM = 8
        val PHC = Regex("\\\$argon2id\\\$v=19\\\$m=(\\d{1,9}),t=(\\d{1,9}),p=(\\d{1,9})\\\$([A-Za-z0-9+/]+)\\\$([A-Za-z0-9+/]+)")
    }
}
