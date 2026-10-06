package xyz.felismp.shoparchive.server.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.server.config.ConfigLog
import xyz.felismp.shoparchive.server.config.ConsoleConfigLog
import xyz.felismp.shoparchive.server.config.backUp
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.writeAtomically
import java.io.IOException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** One line of `banned-ips.json`, the same fields Paper's file has (minus `expires`: a ban lasts until `pardon-ip`). */
@Serializable
internal data class BannedIp(val ip: String, val created: String, val source: String, val reason: String)

private val FORMAT = Json { prettyPrint = true }
private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
private val IPV6 = Regex("[0-9a-fA-F:.]+")

/** [text] as the one spelling used for comparing addresses (so `::ffff:1.2.3.4` equals `1.2.3.4`), or null if it is not an IP address. Never asks DNS. */
internal fun normalizeIp(text: String): String? {
    val trimmed = text.trim().substringBefore('%') // a zone id (fe80::1%eth0) names a local interface, not the peer
    if (!IPV4.matches(trimmed) && !(trimmed.contains(':') && IPV6.matches(trimmed))) return null
    return try {
        InetAddress.getByName(trimmed).hostAddress
    } catch (e: java.net.UnknownHostException) {
        null
    }
}

/**
 * The list of banned client addresses, kept in `banned-ips.json` in the root. A banned address gets 403 on every
 * route. A file that cannot be read is treated as empty - the server still starts - but it is not overwritten
 * until a ban or pardon actually changes the list; the unreadable copy is saved under `data/migration/` first.
 */
internal class IpBans(
    private val root: Path,
    private val log: ConfigLog = ConsoleConfigLog,
    private val clock: Clock = Clock.systemUTC(),
    private val barrier: DataBarrier = DataBarrier(),
) : IpBanService {
    private val file = root.resolve("banned-ips.json")

    @Volatile private var byIp: Map<String, BannedIp> = emptyMap()

    /** The unreadable file's bytes, held until the next change saves them to `data/migration/`. */
    private var unreadable: ByteArray? = null

    @Synchronized
    fun load() {
        unreadable = null
        byIp = emptyMap()
        if (!Files.exists(file)) return
        try {
            val bytes = Files.readAllBytes(file)
            val entries = try {
                FORMAT.decodeFromString(ListSerializer(BannedIp.serializer()), String(bytes, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                unreadable = bytes
                log.warn("banned-ips.json cannot be read (${e.message?.lineSequence()?.first()}); no address is banned until it is fixed. The file is left as it is until a ban-ip or pardon-ip changes the list.")
                return
            }
            byIp = entries.mapNotNull { entry -> normalizeIp(entry.ip)?.let { it to entry.copy(ip = it) } }.toMap()
            if (byIp.size != entries.size) log.warn("banned-ips.json: ${entries.size - byIp.size} entry(ies) without a valid or distinct ip were ignored")
        } catch (e: IOException) {
            log.warn("banned-ips.json cannot be read (${e.message}); no address is banned until it is fixed.")
        }
    }

    override fun isBanned(ip: String): Boolean = normalizeIp(ip)?.let { it in byIp } ?: false

    fun list(): List<BannedIp> = byIp.values.sortedBy { it.ip }

    /** Bans [ip] (already normalised). Returns false if it was banned before; the earlier entry is kept. */
    fun ban(ip: String, reason: String, source: String): Boolean = barrier.mutate { banInLock(ip, reason, source) }

    @Synchronized
    private fun banInLock(ip: String, reason: String, source: String): Boolean {
        if (ip in byIp) return false
        save(byIp + (ip to BannedIp(ip, clock.instant().toString(), source, reason)))
        return true
    }

    /** Lifts the ban on [ip] (already normalised). Returns false if it was not banned, and then nothing is written. */
    fun pardon(ip: String): Boolean = barrier.mutate { pardonInLock(ip) }

    @Synchronized
    private fun pardonInLock(ip: String): Boolean {
        if (ip !in byIp) return false
        save(byIp - ip)
        return true
    }

    private fun save(updated: Map<String, BannedIp>) {
        unreadable?.let {
            log.warn("banned-ips.json was unreadable; the old copy is saved as ${backUp(root, "banned-ips.json", it, clock)}")
            unreadable = null
        }
        val text = FORMAT.encodeToString(ListSerializer(BannedIp.serializer()), updated.values.sortedBy { it.created })
        writeAtomically(file, text.toByteArray(StandardCharsets.UTF_8))
        byIp = updated // after the write: what is enforced is what is on disk
    }
}
