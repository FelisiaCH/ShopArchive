package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import xyz.felismp.shoparchive.server.config.Warn
import xyz.felismp.shoparchive.server.config.YamlConfigFile
import xyz.felismp.shoparchive.server.config.loadConfigFile
import xyz.felismp.shoparchive.server.config.peekConfigFile
import xyz.felismp.shoparchive.server.users.quoted
import xyz.felismp.shoparchive.server.users.scalar
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.writeAtomically
import xyz.felismp.shoparchive.shared.DeviceMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What a device knows about one user on it: the SHA-256 of the user's device credential, the id of the account it was
 * issued to (the name in the file is only a label for the admin; a name can later belong to another account), and the
 * wrong PINs since the last good one. [lastVerified] is when the password was last entered here (the PIN, for a user with no password).
 */
internal data class DeviceUser(
    val credentialSha256: String,
    val userId: String,
    val created: Instant,
    val lastUsed: Instant,
    val pinFailures: Int,
    val lastVerified: Instant = created,
)

internal data class DeviceData(val label: String, val platform: String, val mode: DeviceMode, val created: Instant, val users: Map<String, DeviceUser>)

/** `data/devices/<device-id>.yml`. */
internal class DeviceFile(id: String, private val clock: Clock) : YamlConfigFile<DeviceData>("file-version", 1, emptyList()) {
    override val path = "data/devices/$id.yml"
    override val title = "ShopArchive device $id. Written by the server; the credentials in it are hashes. Delete a user's block to take the user off this device."

    private val known = setOf("label", "platform", "mode", "created", "users")

    override fun resolve(content: Map<String, Any?>, warn: Warn): DeviceData {
        for (key in content.keys) if (key !in known) warn("unknown key '$key' (ignored, and not kept when the file is rewritten)")
        val mode = when ((content["mode"] as? String)?.trim()?.lowercase()) {
            "shared" -> DeviceMode.SHARED
            "personal" -> DeviceMode.PERSONAL
            // The stricter of the two: a device with a damaged mode must not be able to take more users.
            else -> DeviceMode.PERSONAL.also { warn("mode: invalid value '${content["mode"] ?: "(empty)"}'; using personal") }
        }
        val users = LinkedHashMap<String, DeviceUser>()
        val raw = content["users"] as? Map<*, *>
        if (content["users"] != null && raw == null) warn("users: expected one block per user; ignored")
        for ((name, entry) in raw.orEmpty()) {
            val fields = entry as? Map<*, *>
            val hash = (fields?.get("credential-sha256") as? String)?.trim()?.lowercase()
            if (name !is String || fields == null || hash == null || !SHA256_HEX.matches(hash)) {
                warn("users.$name: no valid credential-sha256; the entry is ignored, so that user is not on this device any more")
                continue
            }
            // Without the id nobody can say whose block it is, and guessing from the name is what the id is there to stop.
            val userId = (fields["user-id"] as? String)?.trim()?.lowercase()?.takeIf(::isUuid)
            if (userId == null) {
                warn("users.$name: no valid user-id; the entry is ignored, so that user is not on this device any more and has to log in on it again")
                continue
            }
            val created = instant(fields["created"], "users.$name.created", warn)
            users[name] = DeviceUser(
                hash, userId, created, instant(fields["last-used"], "users.$name.last-used", warn, created),
                (fields["pin-failures"] as? String)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                // An entry from before this key was written was verified when it was made.
                if ("last-verified" in fields) instant(fields["last-verified"], "users.$name.last-verified", warn, created) else created,
            )
        }
        return DeviceData(
            label = (content["label"] as? String)?.takeIf { it.isNotBlank() } ?: "Device",
            platform = (content["platform"] as? String)?.takeIf { it.isNotBlank() } ?: "unknown",
            mode = mode, created = instant(content["created"], "created", warn), users = users,
        )
    }

    private fun instant(raw: Any?, where: String, warn: Warn, fallback: Instant = clock.instant()): Instant =
        try {
            Instant.parse((raw as? String)?.trim().orEmpty())
        } catch (_: DateTimeParseException) {
            fallback.also { warn("$where: not a time like 2026-10-03T08:00:00Z; using $it") }
        }

    override fun renderBody(value: DeviceData): String = buildString {
        append("label: ").append(quoted(value.label)).append('\n')
        append("platform: ").append(quoted(value.platform)).append('\n')
        append("mode: ").append(if (value.mode == DeviceMode.SHARED) "shared" else "personal").append('\n')
        append("created: ").append(value.created).append('\n')
        if (value.users.isEmpty()) {
            append("users: {}\n")
            return@buildString
        }
        append("users:\n")
        for ((name, user) in value.users) {
            append("  ").append(scalar(name)).append(":\n")
            append("    credential-sha256: ").append(user.credentialSha256).append('\n')
            append("    user-id: ").append(user.userId).append('\n')
            append("    created: ").append(user.created).append('\n')
            append("    last-used: ").append(user.lastUsed).append('\n')
            append("    pin-failures: ").append(user.pinFailures).append('\n')
            append("    last-verified: ").append(user.lastVerified).append('\n')
        }
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/**
 * The devices under `data/devices/`, in memory, and the only code that writes those files. A device is a phone or a
 * PC; each user on it has their own credential, so one user can be taken off a device and the others stay.
 * Every change reaches the disk (atomic write) before the call returns, so a count of wrong PINs survives a crash.
 *
 * The file is what the admin edits (the header tells them to delete a user's block to take the user off), so before
 * every check and every write the file's modified time is compared with the one seen when it was read or written: if it
 * moved, the file is read again first, and a write goes on top of what is there now - a block the admin removed never
 * comes back. A file that cannot be read now counts as a device without users and is never overwritten.
 * [onRevoked] is told when a block is found gone or changed that way, so the account's access tokens on that device can end.
 *
 * Loading is a change too (it creates, migrates and rewrites files), so [load] runs inside the barrier like the rest; a check that finds the
 * file changed reads it without writing unless it runs inside a change. Every change takes the [DataBarrier] first and the store's own lock second (a public `x` calls `xInLock`), so a backup that holds
 * the barrier makes a change wait without making a reader wait for the lock that change would be holding.
 */
internal class DeviceStore(
    private val root: Path,
    private val log: ConfigLog = ConsoleConfigLog,
    private val clock: Clock = Clock.systemUTC(),
    private val barrier: DataBarrier = DataBarrier(),
    private val onRevoked: (deviceId: String, userId: String) -> Unit = { _, _ -> },
) {
    private val devices = HashMap<String, DeviceData>()
    private val seen = HashMap<String, FileTime>()
    private val unreadable = HashSet<String>()

    // One "unchanged" line per file on every start would bury everything else once there are many devices.
    private val fileLog = object : ConfigLog by log {
        override fun info(msg: String) {
            if (!msg.endsWith(": unchanged")) log.info(msg)
        }
    }

    /**
     * Reads every device file again (at start, and on `reload devices`). One that cannot be understood counts as a
     * device without users (an error in the log) and is left as it is. Blocks that are gone or changed since the last
     * read are reported to [onRevoked].
     */
    fun load() = barrier.mutate { loadInLock() }

    @Synchronized
    private fun loadInLock() {
        Files.createDirectories(root.resolve("data/devices"))
        val ids = Files.list(root.resolve("data/devices")).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".yml") }.map { it.removeSuffix(".yml") }.sorted().toList()
        }
        for (id in (devices.keys + unreadable).filter { it !in ids }) forget(id)
        for (id in ids) {
            if (!isUuid(id)) {
                log.warn("data/devices/$id.yml: not a device id; the file is ignored")
                continue
            }
            read(id)
        }
        log.info("data/devices/: ${devices.size} devices loaded")
    }

    @Synchronized fun get(id: String): DeviceData? = fresh(id)

    /** Whether the block under [username] on device [id] was issued to the account [userId]. */
    @Synchronized
    fun hasUser(id: String, username: String, userId: String): Boolean = fresh(id)?.users?.get(username)?.userId == userId

    /** The account the block under [username] on device [id] belongs to, if there is one. */
    @Synchronized
    fun blockOwner(id: String, username: String): String? = fresh(id)?.users?.get(username)?.userId

    @Synchronized fun count(): Int = devices.size

    /** Whether [credential] is the one issued to the account [userId], under the name [username], on device [id]. */
    @Synchronized
    fun credentialMatches(id: String, username: String, userId: String, credential: String): Boolean {
        val user = fresh(id)?.users?.get(username)?.takeIf { it.userId == userId } ?: return false
        return sameHash(user.credentialSha256, credential)
    }

    /** What device [id] holds for the account [userId] under [username], if anything. */
    @Synchronized
    fun entry(id: String, username: String, userId: String): DeviceUser? = fresh(id)?.users?.get(username)?.takeIf { it.userId == userId }

    /** Every device the account [userId] is on, with its id. */
    @Synchronized
    fun devicesOf(userId: String): List<Pair<String, DeviceData>> =
        devices.keys.sorted().mapNotNull { id -> fresh(id)?.takeIf { d -> d.users.values.any { it.userId == userId } }?.let { id to it } }

    /** The id of the account on device [id] whose credential [credential] is, if any. */
    @Synchronized
    fun ownerOf(id: String, credential: String): String? =
        fresh(id)?.users?.values?.firstOrNull { sameHash(it.credentialSha256, credential) }?.userId

    /** Makes a device with one user and returns its id. */
    fun create(label: String, platform: String, mode: DeviceMode, username: String, userId: String, credential: String): String = barrier.mutate { createInLock(label, platform, mode, username, userId, credential) }

    @Synchronized
    private fun createInLock(label: String, platform: String, mode: DeviceMode, username: String, userId: String, credential: String): String {
        val id = UUID.randomUUID().toString()
        val now = now()
        write(id, DeviceData(label, platform, mode, now, mapOf(username to DeviceUser(sha256Hex(credential), userId, now, now, 0, now))))
        return id
    }

    /**
     * Puts the account [userId], called [username], on device [id] (a user already on it gets the new credential and a
     * clean count). False if the device is gone or its file cannot be read now; nothing is written then.
     */
    fun putUser(id: String, username: String, userId: String, credential: String): Boolean = barrier.mutate { putUserInLock(id, username, userId, credential) }

    @Synchronized
    private fun putUserInLock(id: String, username: String, userId: String, credential: String): Boolean {
        val device = fresh(id) ?: return false
        val now = now()
        write(id, device.copy(users = device.users + (username to DeviceUser(sha256Hex(credential), userId, now, now, 0, now))))
        return true
    }

    /** The account [userId] is called [new] now: its block on every device gets that name. A block that cannot move (the name is taken in that file) stays, and so the user is off that device. */
    fun renameUser(userId: String, old: String, new: String) = barrier.mutate { renameUserInLock(userId, old, new) }

    @Synchronized
    private fun renameUserInLock(userId: String, old: String, new: String) {
        for (id in devices.keys.toList()) {
            val device = fresh(id) ?: continue
            if (device.users[old]?.userId != userId) continue
            if (new in device.users) {
                log.warn("data/devices/$id.yml: cannot rename '$old' to '$new', the name is already in the file; '$old' has to log in on this device again")
                continue
            }
            write(id, device.copy(users = device.users.entries.associate { (if (it.key == old) new else it.key) to it.value }))
        }
    }

    /** A good PIN or password: the count of wrong ones starts again. [fullVerification]: it was the password (or the PIN of a user with none). */
    fun recordSuccess(id: String, username: String, userId: String, fullVerification: Boolean = true) = barrier.mutate { recordSuccessInLock(id, username, userId, fullVerification) }

    @Synchronized
    private fun recordSuccessInLock(id: String, username: String, userId: String, fullVerification: Boolean = true) {
        val device = fresh(id) ?: return
        val user = device.users[username]?.takeIf { it.userId == userId } ?: return
        val now = now()
        write(id, device.copy(users = device.users + (username to user.copy(lastUsed = now, pinFailures = 0, lastVerified = if (fullVerification) now else user.lastVerified))))
    }

    /** The device was used with its credential alone: no PIN or password was entered, so the count of wrong ones stays. */
    fun recordUse(id: String, username: String, userId: String) = barrier.mutate { recordUseInLock(id, username, userId) }

    @Synchronized
    private fun recordUseInLock(id: String, username: String, userId: String) {
        val device = fresh(id) ?: return
        val user = device.users[username]?.takeIf { it.userId == userId } ?: return
        write(id, device.copy(users = device.users + (username to user.copy(lastUsed = now()))))
    }

    /** Takes the account [userId] off device [id] (the file is read again first). False if it was not on it. */
    fun removeUser(id: String, userId: String): Boolean = barrier.mutate { removeUserInLock(id, userId) }

    @Synchronized
    private fun removeUserInLock(id: String, userId: String): Boolean {
        val device = fresh(id) ?: return false
        val name = device.users.entries.firstOrNull { it.value.userId == userId }?.key ?: return false
        write(id, device.copy(users = device.users - name))
        return true
    }

    /** Takes the account [userId] off every device; returns how many it was on. */
    fun removeAccount(userId: String): Int = barrier.mutate { removeAccountInLock(userId) }

    @Synchronized
    private fun removeAccountInLock(userId: String): Int = devicesOf(userId).count { (id, _) -> removeUser(id, userId) }

    /** False if the device is gone or its file cannot be read now; nothing is written then. */
    fun setMode(id: String, mode: DeviceMode): Boolean = barrier.mutate { setModeInLock(id, mode) }

    @Synchronized
    private fun setModeInLock(id: String, mode: DeviceMode): Boolean {
        val device = fresh(id) ?: return false
        if (device.mode != mode) write(id, device.copy(mode = mode))
        return true
    }

    /** A wrong PIN or password. At [maxFailures] (unless 0) the user is taken off the device; returns true then. */
    fun recordFailure(id: String, username: String, userId: String, maxFailures: Int): Boolean = barrier.mutate { recordFailureInLock(id, username, userId, maxFailures) }

    @Synchronized
    private fun recordFailureInLock(id: String, username: String, userId: String, maxFailures: Int): Boolean {
        val device = fresh(id) ?: return false
        val user = device.users[username]?.takeIf { it.userId == userId } ?: return false
        val failures = user.pinFailures + 1
        if (maxFailures > 0 && failures >= maxFailures) {
            write(id, device.copy(users = device.users - username))
            return true
        }
        write(id, device.copy(users = device.users + (username to user.copy(pinFailures = failures))))
        return false
    }

    /** Whole seconds are enough for a file a person may read. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun sameHash(storedHex: String, credential: String): Boolean =
        credential.length <= MAX_CREDENTIAL_CHARS && MessageDigest.isEqual(storedHex.toByteArray(), sha256Hex(credential).toByteArray())

    private fun write(id: String, data: DeviceData) {
        val path = devicePath(id)
        barrier.mutate { writeAtomically(path, DeviceFile(id, clock).render(data).toByteArray(Charsets.UTF_8)) }
        secure(path)
        devices[id] = data
        unreadable -= id
        mtime(path)?.let { seen[id] = it }
    }

    private fun devicePath(id: String) = root.resolve("data/devices/$id.yml")

    private fun mtime(path: Path): FileTime? =
        try {
            Files.getLastModifiedTime(path)
        } catch (_: IOException) {
            null
        }

    /** The device as its file says now: if the file changed (or went) since it was read or written here, it is read again first. */
    private fun fresh(id: String): DeviceData? {
        // The id comes from a request: only a real one may name a file.
        if (!isUuid(id)) return null
        val modified = mtime(devicePath(id))
        if (modified != seen[id]) {
            if (modified == null) forget(id) else read(id, write = barrier.inMutate)
        }
        return devices[id]
    }

    /**
     * Reads the file of device [id]; what it says replaces what was held, and blocks it no longer has are revoked. With [write] the file is
     * normalized as [loadConfigFile] does (only inside the barrier: [load], or a change). Without it nothing is written, and a file that is
     * not in template form keeps looking changed, so the next change under the barrier normalizes it.
     */
    private fun read(id: String, write: Boolean = true) {
        val path = devicePath(id)
        val before = devices[id]
        var clean = true
        try {
            if (write) {
                devices[id] = loadConfigFile(root, DeviceFile(id, clock), fileLog, clock).value
                secure(path)
            } else {
                val peeked = peekConfigFile(root, DeviceFile(id, clock), fileLog)
                devices[id] = peeked.value
                clean = peeked.clean
            }
            unreadable -= id
        } catch (e: ConfigFileException) {
            log.error("${e.message}; this device has no users until the file is fixed, and it is left as it is")
            devices.remove(id)
            unreadable += id
        }
        // After the load, which may have rewritten the file.
        if (clean) mtime(path)?.let { seen[id] = it } ?: seen.remove(id)
        revokeChanged(id, before, devices[id])
    }

    /** The file of device [id] is gone: so is the device. */
    private fun forget(id: String) {
        val before = devices.remove(id)
        seen.remove(id)
        unreadable -= id
        revokeChanged(id, before, null)
    }

    private fun revokeChanged(id: String, before: DeviceData?, after: DeviceData?) {
        for ((name, user) in before?.users.orEmpty()) {
            val now = after?.users?.get(name)
            if (now != null && now.userId == user.userId && now.credentialSha256 == user.credentialSha256) continue
            log.info("data/devices/$id.yml: the block of '$name' is gone or was changed; its access tokens on this device end")
            onRevoked(id, user.userId)
        }
    }

    /** Owner-only access on a filesystem that has POSIX permissions (not Windows); nothing happens elsewhere. */
    private fun secure(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // no POSIX permissions here
        }
    }

    private companion object {
        /** A credential is 43 characters; anything much longer is not one and is not worth hashing. */
        const val MAX_CREDENTIAL_CHARS = 128
    }
}
