package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.parseYamlMap
import xyz.felismp.shoparchive.server.users.quoted
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryChange
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.SessionClose
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionStatus
import xyz.felismp.shoparchive.shared.SlipDto
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.isIsoDate
import xyz.felismp.shoparchive.shared.isSlug
import xyz.felismp.shoparchive.shared.isUuidV7
import xyz.felismp.shoparchive.shared.parseAmount
import xyz.felismp.shoparchive.shared.wire
import java.security.MessageDigest
import java.time.LocalDate

/** The newest `file-version` of entry, history and session files this server writes and understands. */
internal const val RECORD_FILE_VERSION = 1

/** A record file that cannot be understood; the message says which file and what is wrong. The file is left as it is. */
internal class RecordFormatException(message: String) : Exception(message)

/** An amount in minor units with the decimals of its currency, so it can be written back exactly as it was read. */
internal data class Amount(val minor: Long, val exponent: Int) {
    val text: String get() = formatAmount(minor, exponent)
}

internal data class Tender(val currency: String, val method: TenderMethod, val amount: Amount) {
    fun toDto() = TenderDto(currency, method, amount.text)
}

internal data class Slip(val file: String, val sha256: String) {
    fun toDto() = SlipDto(file, sha256)

    /** The N of `slip-N.jpg`. */
    val number: Int get() = file.removePrefix("slip-").substringBefore('.').toInt()
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal val SLIP_FILE = Regex("slip-([1-9][0-9]{0,3})\\.(jpg|png)")

internal data class Entry(
    val id: String,
    val date: LocalDate,
    val type: EntryType,
    val branch: String,
    val category: String?,
    val item: String,
    val note: String,
    val createdBy: UserRef,
    val createdAt: String,
    val updatedAt: String,
    val deleted: Boolean,
    val tenders: List<Tender>,
    val slips: List<Slip>,
    val session: String?,
) {
    fun toDto() = EntryDto(
        id, date.toString(), type, branch, category, item, note, createdBy, createdAt, updatedAt, deleted,
        tenders.map { it.toDto() }, slips.map { it.toDto() }, session,
    )
}

/**
 * What the day's entries (those not deleted) added up to when the day was closed, kept in the session file so the summary of a closed day
 * (for the message about it) never changes when an entry is edited later. Maps are per currency, only for currencies that have money in them.
 */
internal data class Activity(
    val entries: Int,
    val slips: Int,
    val cashIn: Map<String, Amount>,
    val cashOut: Map<String, Amount>,
    val onlineIn: Map<String, Amount>,
    val onlineOut: Map<String, Amount>,
)

/** How a session ended; every map is per currency. [activity] is null for a day closed before it was kept. */
internal data class Closed(
    val counted: Map<String, Amount>,
    val expected: Map<String, Amount>,
    val variance: Map<String, Amount>,
    val handover: Map<String, Amount>,
    val belowFloor: List<String>,
    val note: String,
    val closedBy: UserRef,
    val closedAt: String,
    val activity: Activity? = null,
)

internal data class Session(
    val id: String,
    val branch: String,
    val businessDate: LocalDate,
    val openedBy: UserRef,
    val openedAt: String,
    val float: Map<String, Amount>,
    val closed: Closed?,
) {
    fun toDto() = SessionDto(
        id, branch, businessDate.toString(), openedBy, openedAt, float.mapValues { it.value.text },
        if (closed == null) SessionStatus.OPEN else SessionStatus.CLOSED,
        closed?.let {
            SessionClose(
                it.counted.mapValues { a -> a.value.text }, it.expected.mapValues { a -> a.value.text },
                it.variance.mapValues { a -> a.value.text }, it.handover.mapValues { a -> a.value.text },
                it.belowFloor, it.note, it.closedBy, it.closedAt,
            )
        },
    )
}

// --- writing: text values are always quoted, so a name like "null" or "1e3" is still text to every YAML reader ---

private fun ref(user: UserRef) = "{ id: ${quoted(user.id)}, name: ${quoted(user.name)} }"

private fun amounts(map: Map<String, Amount>) = map.entries.joinToString(", ", "{ ", " }") { "${it.key}: ${quoted(it.value.text)}" }.let { if (map.isEmpty()) "{}" else it }

internal fun renderEntry(e: Entry): String = buildString {
    append("file-version: $RECORD_FILE_VERSION\n")
    append("id: ${e.id}\n")
    append("session: ${e.session ?: "null"}\n")
    append("date: ${e.date}\n")
    append("type: ${e.type.wire()}\n")
    append("branch: ${quoted(e.branch)}\n")
    append("category: ${e.category?.let(::quoted) ?: "null"}\n")
    append("item: ${quoted(e.item)}\n")
    append("note: ${quoted(e.note)}\n")
    append("created-by: ${ref(e.createdBy)}\n")
    append("created-at: ${quoted(e.createdAt)}\n")
    append("updated-at: ${quoted(e.updatedAt)}\n")
    append("deleted: ${e.deleted}\n")
    append("tenders:\n")
    for (t in e.tenders) append("  - currency: ${t.currency}\n    method: ${t.method.wire()}\n    amount: ${quoted(t.amount.text)}\n")
    if (e.slips.isEmpty()) append("slips: []\n") else append("slips:\n")
    for (s in e.slips) append("  - file: ${quoted(s.file)}\n    sha256: ${quoted(s.sha256)}\n")
}

internal fun renderSession(s: Session): String = buildString {
    append("file-version: $RECORD_FILE_VERSION\n")
    append("id: ${s.id}\n")
    append("branch: ${quoted(s.branch)}\n")
    append("business-date: ${s.businessDate}\n")
    append("opened-by: ${ref(s.openedBy)}\n")
    append("opened-at: ${quoted(s.openedAt)}\n")
    append("float: ${amounts(s.float)}\n")
    append("status: ${if (s.closed == null) "open" else "closed"}\n")
    val c = s.closed ?: return@buildString
    append("counted: ${amounts(c.counted)}\n")
    append("expected: ${amounts(c.expected)}\n")
    append("variance: ${amounts(c.variance)}\n")
    append("handover: ${amounts(c.handover)}\n")
    append("below-float: ${c.belowFloor.joinToString(", ", "[", "]") { quoted(it) }}\n")
    append("note: ${quoted(c.note)}\n")
    append("closed-by: ${ref(c.closedBy)}\n")
    append("closed-at: ${quoted(c.closedAt)}\n")
    c.activity?.let {
        append("entries: ${it.entries}\n")
        append("slips: ${it.slips}\n")
        append("cash-in: ${amounts(it.cashIn)}\n")
        append("cash-out: ${amounts(it.cashOut)}\n")
        append("online-in: ${amounts(it.onlineIn)}\n")
        append("online-out: ${amounts(it.onlineOut)}\n")
    }
}

private const val HISTORY_HEAD = "file-version: $RECORD_FILE_VERSION\nhistory:\n"

internal fun renderHistoryHead(): String = HISTORY_HEAD

/** One item to add at the end of `history.yml`. */
internal fun renderHistoryItem(h: HistoryItem): String = buildString {
    append("  - at: ${quoted(h.at)}\n")
    append("    by: ${ref(h.by)}\n")
    append("    action: ${h.action}\n")
    if (h.changes.isEmpty()) append("    changes: []\n") else append("    changes:\n")
    for (c in h.changes) append("      - { field: ${quoted(c.field)}, from: ${quoted(c.from)}, to: ${quoted(c.to)} }\n")
}

// --- reading ---

private class Fields(private val map: Map<String, Any?>, private val where: String) {
    fun text(key: String): String =
        map[key] as? String ?: throw RecordFormatException("$where: '$key' is missing or is not text")

    fun has(key: String): Boolean = map[key] != null

    fun optionalText(key: String): String? =
        if (map[key] == null) null else map[key] as? String ?: throw RecordFormatException("$where: '$key' is not text")

    fun bool(key: String): Boolean = when (text(key)) {
        "true" -> true
        "false" -> false
        else -> throw RecordFormatException("$where: '$key' is not true or false")
    }

    fun fields(key: String): Fields {
        @Suppress("UNCHECKED_CAST")
        val inner = map[key] as? Map<String, Any?> ?: throw RecordFormatException("$where: '$key' is missing or is not a mapping")
        return Fields(inner, "$where.$key")
    }

    fun items(key: String): List<Fields> {
        val list = map[key] as? List<*> ?: throw RecordFormatException("$where: '$key' is missing or is not a list")
        return list.mapIndexed { index, item ->
            @Suppress("UNCHECKED_CAST")
            Fields(item as? Map<String, Any?> ?: throw RecordFormatException("$where: '$key' item ${index + 1} is not a mapping"), "$where.$key[${index + 1}]")
        }
    }

    fun words(key: String): List<String> {
        val list = map[key] as? List<*> ?: throw RecordFormatException("$where: '$key' is missing or is not a list")
        return list.map { it as? String ?: throw RecordFormatException("$where: '$key' has an item that is not text") }
    }

    /** Every entry of the mapping under [key] as (name, text). */
    fun texts(key: String): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val inner = map[key] as? Map<String, Any?> ?: throw RecordFormatException("$where: '$key' is missing or is not a mapping")
        return inner.mapValues { it.value as? String ?: throw RecordFormatException("$where: '$key.${it.key}' is not text") }
    }
}

private fun readYaml(what: String, text: String): Fields {
    val map = try {
        parseYamlMap(what, text)
    } catch (e: ConfigFileException) {
        throw RecordFormatException(e.message ?: "$what cannot be parsed")
    }
    val version = (map["file-version"] as? String)?.trim()?.toIntOrNull()
        ?: throw RecordFormatException("$what: file-version is missing or is not a number")
    if (version < 1 || version > RECORD_FILE_VERSION) {
        throw RecordFormatException("$what has file-version $version, but this server only knows up to $RECORD_FILE_VERSION; the file is left untouched")
    }
    return Fields(map, what)
}

private fun Fields.userRef(key: String): UserRef = fields(key).let { UserRef(it.text("id"), it.text("name")) }

private fun amount(currency: String, text: String, exponents: Map<String, Int>, where: String, allowZero: Boolean = false): Amount {
    val exponent = exponents[currency] ?: throw RecordFormatException("$where: the currency $currency is not in config/currencies.yml")
    val minor = parseAmount(text.removePrefix("-"), exponent, allowZero = true)
        ?: throw RecordFormatException("$where: '$text' is not an amount in $currency")
    val signed = if (text.startsWith("-")) -minor else minor
    if (!allowZero && signed <= 0) throw RecordFormatException("$where: the amount of $currency must be above 0")
    return Amount(signed, exponent)
}

private fun Fields.amounts(key: String, exponents: Map<String, Int>, where: String): Map<String, Amount> =
    texts(key).mapValues { (currency, text) -> amount(currency, text, exponents, "$where.$key.$currency", allowZero = true) }

/** A time with its offset, like 2026-09-27T08:14:03+07:00: what sorting and the dashboard's hours rely on. */
private fun Fields.stamp(key: String): String = text(key).also {
    val valid = STAMP_TEXT.matches(it) && try {
        java.time.OffsetDateTime.parse(it)
        true
    } catch (e: java.time.format.DateTimeParseException) {
        false
    }
    if (!valid) throw RecordFormatException("entry.yml: $key '$it' is not a time with its offset like 2026-09-27T08:14:03+07:00")
}

// Four-digit year and seconds always there: the export takes the time of day as characters 11-19.
private val STAMP_TEXT = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})")

/** @throws RecordFormatException the file is not a valid `entry.yml`; [exponents] are the decimals of each known currency */
internal fun parseEntry(text: String, exponents: Map<String, Int>): Entry {
    val f = readYaml("entry.yml", text)
    val id = f.text("id").also { if (!isUuidV7(it)) throw RecordFormatException("entry.yml: id '$it' is not a lower case UUIDv7") }
    val date = f.text("date").also { if (!isIsoDate(it)) throw RecordFormatException("entry.yml: date '$it' is not a date like 2026-09-27") }
    val type = EntryType.entries.firstOrNull { it.wire() == f.text("type") } ?: throw RecordFormatException("entry.yml: type is not income or expense")
    val branch = f.text("branch").also { if (!isSlug(it)) throw RecordFormatException("entry.yml: branch '$it' is not a branch key") }
    val tenders = f.items("tenders").map { t ->
        val currency = t.text("currency")
        val method = TenderMethod.entries.firstOrNull { it.wire() == t.text("method") } ?: throw RecordFormatException("entry.yml: a tender's method is not cash or online")
        Tender(currency, method, amount(currency, t.text("amount"), exponents, "entry.yml.tenders"))
    }
    if (tenders.isEmpty()) throw RecordFormatException("entry.yml: an entry needs at least one tender")
    val slips = f.items("slips").map { s ->
        val file = s.text("file").also { if (!SLIP_FILE.matches(it)) throw RecordFormatException("entry.yml: '$it' is not a slip file name like slip-1.jpg") }
        val sha = s.text("sha256").also { if (!Regex("[0-9a-f]{64}").matches(it)) throw RecordFormatException("entry.yml: the sha256 of $file is not 64 hex digits") }
        Slip(file, sha)
    }
    return Entry(
        id = id, date = LocalDate.parse(date), type = type, branch = branch,
        category = f.optionalText("category"), item = f.text("item"), note = f.text("note"),
        createdBy = f.userRef("created-by"), createdAt = f.stamp("created-at"), updatedAt = f.stamp("updated-at"),
        deleted = f.bool("deleted"), tenders = tenders, slips = slips, session = f.optionalText("session"),
    )
}

internal fun parseSession(text: String, exponents: Map<String, Int>): Session {
    val f = readYaml("session", text)
    val id = f.text("id").also { if (!isUuidV7(it)) throw RecordFormatException("session: id '$it' is not a lower case UUIDv7") }
    val date = f.text("business-date").also { if (!isIsoDate(it)) throw RecordFormatException("session: business-date '$it' is not a date like 2026-09-27") }
    val branch = f.text("branch").also { if (!isSlug(it)) throw RecordFormatException("session: branch '$it' is not a branch key") }
    val closed = when (f.text("status")) {
        "open" -> null
        "closed" -> Closed(
            counted = f.amounts("counted", exponents, "session"), expected = f.amounts("expected", exponents, "session"),
            variance = f.amounts("variance", exponents, "session"), handover = f.amounts("handover", exponents, "session"),
            belowFloor = f.words("below-float"), note = f.text("note"), closedBy = f.userRef("closed-by"), closedAt = f.text("closed-at"),
            activity = if (!f.has("entries")) null else Activity(
                f.text("entries").toIntOrNull() ?: throw RecordFormatException("session: entries is not a number"),
                f.text("slips").toIntOrNull() ?: throw RecordFormatException("session: slips is not a number"),
                f.amounts("cash-in", exponents, "session"), f.amounts("cash-out", exponents, "session"),
                f.amounts("online-in", exponents, "session"), f.amounts("online-out", exponents, "session"),
            ),
        )
        else -> throw RecordFormatException("session: status is not open or closed")
    }
    return Session(id, branch, LocalDate.parse(date), f.userRef("opened-by"), f.text("opened-at"), f.amounts("float", exponents, "session"), closed)
}

/** The items of a `history.yml`. */
internal fun parseHistory(text: String): List<HistoryItem> =
    readYaml("history.yml", text).items("history").map { h ->
        HistoryItem(
            h.text("at"), h.userRef("by"), h.text("action"),
            h.items("changes").map { c -> HistoryChange(c.text("field"), c.text("from"), c.text("to")) },
        )
    }
