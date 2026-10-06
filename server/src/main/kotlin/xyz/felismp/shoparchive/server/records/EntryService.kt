package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.CreatedEntry
import xyz.felismp.shoparchive.api.EntryQuery
import xyz.felismp.shoparchive.api.EntryCreatedEvent
import xyz.felismp.shoparchive.api.EntryService
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.api.SlipFile
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.shared.CategoryRule
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryMovedMessage
import xyz.felismp.shoparchive.shared.EntryRules
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.HistoryChange
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.MoveEntryRequest
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import xyz.felismp.shoparchive.shared.UserRef
import xyz.felismp.shoparchive.shared.WsMessage
import xyz.felismp.shoparchive.shared.checkEntry
import xyz.felismp.shoparchive.shared.detectSlipExtension
import xyz.felismp.shoparchive.shared.isIsoDate
import xyz.felismp.shoparchive.shared.isUuidV7
import xyz.felismp.shoparchive.shared.parseAmount
import xyz.felismp.shoparchive.shared.wire
import java.nio.file.NoSuchFileException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The longest span `GET /api/v1/entries` answers for, in days. */
private const val MAX_RANGE_DAYS = 366L

internal val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx", java.util.Locale.ROOT)

/** The time the way files and JSON write it: in the server's time zone, with its offset, to the second. */
internal fun stamp(clock: Clock, config: ConfigService): String = OffsetDateTime.now(clock.withZone(config.timezone)).truncatedTo(ChronoUnit.SECONDS).format(STAMP)

/**
 * The updated-at for a change of an entry last stamped [previous]: now, or one second after [previous] when now is not later,
 * so every change gives the entry a new version and an edit made from an older one is always told apart (seconds are the precision).
 */
internal fun nextStamp(previous: String, clock: Clock, config: ConfigService): String {
    val now = OffsetDateTime.now(clock.withZone(config.timezone)).truncatedTo(ChronoUnit.SECONDS)
    val before = runCatching { OffsetDateTime.parse(previous) }.getOrNull() ?: return now.format(STAMP)
    return (if (now.isAfter(before)) now else before.plusSeconds(1).withOffsetSameInstant(now.offset)).format(STAMP)
}

/** The date the shop is on now, in the configured time zone. */
internal fun businessToday(clock: Clock, config: ConfigService): LocalDate = LocalDate.now(clock.withZone(config.timezone))

private fun date(text: String): LocalDate {
    if (!isIsoDate(text)) throw badRequest("'$text' is not a date like 2026-09-27.", ErrorReasons.DATE_INVALID)
    return LocalDate.parse(text)
}

/** The dates of a list or an export: both `yyyy-MM-dd`, [from] not after [to], and at most [MAX_RANGE_DAYS] apart. */
internal fun dateRange(from: String, to: String): Pair<LocalDate, LocalDate> {
    val first = date(from)
    val last = date(to)
    if (last.isBefore(first)) throw badRequest("'from' is after 'to'.", ErrorReasons.RANGE_ORDER)
    if (ChronoUnit.DAYS.between(first, last) >= MAX_RANGE_DAYS) throw badRequest("Ask for at most $MAX_RANGE_DAYS days at a time.", ErrorReasons.RANGE_TOO_LONG)
    return first to last
}

/** What differs between two versions of an entry, as history changes. */
internal fun differences(old: Entry, new: Entry): List<HistoryChange> = buildList {
    if (old.type != new.type) add(HistoryChange("type", old.type.wire(), new.type.wire()))
    if (old.category != new.category) add(HistoryChange("category", old.category ?: "", new.category ?: ""))
    if (old.item != new.item) add(HistoryChange("item", old.item, new.item))
    if (old.note != new.note) add(HistoryChange("note", old.note, new.note))
    if (old.tenders != new.tenders) add(HistoryChange("tenders", describe(old.tenders), describe(new.tenders)))
    if (old.slips != new.slips) add(HistoryChange("slips", old.slips.joinToString("; ") { it.file }, new.slips.joinToString("; ") { it.file }))
}

private fun describe(tenders: List<Tender>) = tenders.joinToString("; ") { "${it.currency} ${it.method.wire()} ${it.amount.text}" }

internal fun noOpenSession(branch: String) =
    ApiError(409, ErrorCode.NO_OPEN_SESSION, "The branch $branch has no open day. Open the day first.", reason = ErrorReasons.SESSION_NO_OPEN_DAY)

internal fun brokenError() =
    ApiError(409, ErrorCode.ENTRY_BROKEN, "The files of this entry are damaged. The admin can see why on the server console (records).")

internal class DefaultEntryService(
    private val config: ConfigService,
    private val branches: BranchStore,
    private val categories: CategoryStore,
    private val store: RecordStore,
    private val sessions: SessionStore,
    private val access: Access,
    private val services: ServiceRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : EntryService {
    private fun today() = businessToday(clock, config)

    // --- create ---

    override fun create(principal: Principal, request: CreateEntryRequest, slips: List<ByteArray>): CreatedEntry {
        access.require(principal, ENTRY_CREATE_NODE)
        if (!isUuidV7(request.id)) throw badRequest("The id must be a UUID version 7 in lower case.", ErrorReasons.ENTRY_ID_INVALID)
        access.requireBranch(principal, request.branch)
        val branch = branches.find(request.branch) ?: throw badRequest("There is no branch '${request.branch}'.", ErrorReasons.BRANCH_UNKNOWN)
        checkEntry(request.type, request.category, request.tenders, slips.size, rules())?.let { throw badRequest(it) }
        val slipFiles = slipFiles(slips, firstNumber = 1)
        val tenders = tenders(request.tenders)

        val (stored, isNew) = store.writer.run {
            store.find(request.id)?.let { existing ->
                // A resend gets what was stored the first time; an id that is someone else's is not handed out.
                if (!canView(principal, existing)) throw conflict("That entry id is already used.", ErrorReasons.ENTRY_ID_TAKEN)
                return@run existing to false
            }
            if (store.brokenEntry(request.id) != null) throw brokenError()
            if (branch.archived) throw badRequest("The branch '${branch.key}' is archived.", ErrorReasons.BRANCH_ARCHIVED)
            // The day it is recorded on is the day the open session was opened, even after midnight.
            val open = sessions.openFor(branch.key)
            if (open == null && config.records.requireOpenDay) throw noOpenSession(branch.key)
            val now = stamp(clock, config)
            val by = UserRef(principal.userId, principal.username)
            val entry = Entry(
                id = request.id, date = open?.businessDate ?: today(), type = request.type, branch = branch.key, category = request.category,
                item = request.item.trim(), note = request.note.trim(), createdBy = by, createdAt = now, updatedAt = now, deleted = false,
                tenders = tenders, slips = slipFiles.map { Slip(it.file, sha256Hex(it.bytes)) }, session = open?.id,
            )
            store.create(NewEntry(entry, slipFiles.associate { it.file to it.bytes }, HistoryItem(now, by, "create", emptyList())))
            entry to true
        }
        if (isNew) {
            publish(EntryCreatedMessage(stored.id, stored.date.toString(), stored.branch), stored)
            publishEvent { createdEvent(stored) }
        }
        return CreatedEntry(stored.toDto(), isNew)
    }

    // --- read ---

    override fun list(principal: Principal, query: EntryQuery): List<EntryDto> {
        val today = today()
        val (from, to) = dateRange(query.from ?: today.withDayOfMonth(1).toString(), query.to ?: today.withDayOfMonth(today.lengthOfMonth()).toString())
        query.branch?.let { access.requireBranch(principal, it) }
        val viewAll = access.has(principal, ENTRY_VIEW_ALL_NODE)
        if (!viewAll && !access.has(principal, ENTRY_VIEW_OWN_NODE)) throw access.forbidden("This needs the permission $ENTRY_VIEW_OWN_NODE.", ErrorReasons.PERMISSION_MISSING)
        val inScope = HashMap<String, Boolean>()
        return store.all().asSequence()
            .filter { it.date in from..to }
            .filter { query.branch == null || it.branch == query.branch }
            .filter { query.type == null || it.type == query.type }
            .filter { query.category == null || it.category == query.category }
            .filter { query.currency == null || it.tenders.any { t -> t.currency == query.currency } }
            .filter { query.includeDeleted || !it.deleted }
            .filter { viewAll || it.createdBy.id == principal.userId }
            .filter { inScope.getOrPut(it.branch) { access.canBranch(principal, it.branch) } }
            .sortedWith(compareBy({ it.date }, { it.createdAt }, { it.id }))
            .map { it.toDto() }
            .toList()
    }

    override fun get(principal: Principal, date: String, id: String): EntryDto = lookup(principal, date, id).toDto()

    override fun slip(principal: Principal, date: String, id: String, n: Int): SlipFile {
        val entry = lookup(principal, date, id)
        val slip = entry.slips.firstOrNull { it.number == n } ?: throw notFound("This entry has no slip $n.")
        val bytes = try {
            store.slipBytes(entry, slip.file)
        } catch (e: NoSuchFileException) {
            throw ApiError(409, ErrorCode.ENTRY_BROKEN, "The file ${slip.file} of this entry is missing. The admin can see more on the server console.")
        }
        return SlipFile(bytes, if (slip.file.endsWith(".png")) "image/png" else "image/jpeg")
    }

    override fun history(principal: Principal, date: String, id: String): List<HistoryItem> {
        val entry = lookup(principal, date, id)
        return try {
            store.history(entry)
        } catch (e: RecordFormatException) {
            throw ApiError(409, ErrorCode.ENTRY_BROKEN, "The history of this entry cannot be read: ${e.message}")
        }
    }

    // --- change ---

    override fun update(principal: Principal, date: String, id: String, request: UpdateEntryRequest, newSlips: List<ByteArray>): EntryDto {
        val result = store.writer.run {
            val entry = lookup(principal, date, id)
            requireChange(principal, entry, ENTRY_EDIT_OWN_NODE, ENTRY_EDIT_ALL_NODE)
            request.expectedUpdatedAt?.let { if (it != entry.updatedAt) throw conflict("This entry was changed on another device. Load it again, then make your change.", ErrorReasons.ENTRY_CHANGED_ELSEWHERE) }
            if (entry.deleted) throw conflict("A deleted entry cannot be changed.", ErrorReasons.ENTRY_DELETED)
            val kept = request.keepSlips?.let { keep ->
                keep.map { file -> entry.slips.firstOrNull { it.file == file } ?: throw badRequest("This entry has no slip '$file' to keep.", ErrorReasons.ENTRY_SLIP_UNKNOWN) }.distinct()
            } ?: entry.slips
            // An unchanged category stays acceptable even if it was archived or retyped since.
            val unchangedCategory = request.category != null && request.category == entry.category
            checkEntry(
                request.type, request.category.takeUnless { unchangedCategory }, request.tenders, kept.size + newSlips.size,
                rules().let { if (unchangedCategory) EntryRules(it.exponents, it.categories, false, it.slipMaxCount) else it },
            )?.let { throw badRequest(it) }
            val added = slipFiles(newSlips, firstNumber = store.nextSlipNumber(entry))
            val now = nextStamp(entry.updatedAt, clock, config)
            val changed = entry.copy(
                type = request.type, category = request.category, item = request.item.trim(), note = request.note.trim(),
                tenders = tenders(request.tenders), slips = kept + added.map { Slip(it.file, sha256Hex(it.bytes)) }, updatedAt = now,
            )
            val changes = differences(entry, changed)
            if (changes.isEmpty()) return@run entry to null
            val by = UserRef(principal.userId, principal.username)
            store.replace(changed, added.associate { it.file to it.bytes }, HistoryItem(now, by, "edit", changes)) to EntryUpdatedMessage(id, changed.date.toString(), changed.branch)
        }
        result.second?.let { publish(it, result.first) }
        return result.first.toDto()
    }

    override fun delete(principal: Principal, date: String, id: String): EntryDto {
        val result = store.writer.run {
            val entry = lookup(principal, date, id)
            requireChange(principal, entry, ENTRY_DELETE_OWN_NODE, ENTRY_DELETE_ALL_NODE)
            access.requireRecentAuth(principal)
            if (entry.deleted) return@run entry to null
            val now = nextStamp(entry.updatedAt, clock, config)
            val by = UserRef(principal.userId, principal.username)
            store.replace(entry.copy(deleted = true, updatedAt = now), emptyMap(), HistoryItem(now, by, "delete", emptyList())) to
                EntryDeletedMessage(id, entry.date.toString(), entry.branch)
        }
        result.second?.let { publish(it, result.first) }
        return result.first.toDto()
    }

    override fun move(principal: Principal, date: String, id: String, request: MoveEntryRequest): EntryDto {
        val result = store.writer.run {
            val entry = lookup(principal, date, id)
            requireChange(principal, entry, ENTRY_EDIT_OWN_NODE, ENTRY_EDIT_ALL_NODE)
            access.requireRecentAuth(principal)
            val target = date(request.date)
            if (target == entry.date) return@run entry to null
            if (target.isAfter(today())) throw badRequest("An entry cannot be moved to a day that has not come yet.", ErrorReasons.ENTRY_MOVE_FUTURE)
            if (!access.has(principal, ENTRY_EDIT_ALL_NODE) && !withinWindow(target)) {
                throw access.forbidden("That day is past the edit window; moving an entry there needs $ENTRY_EDIT_ALL_NODE.", ErrorReasons.ENTRY_PAST_WINDOW)
            }
            val now = nextStamp(entry.updatedAt, clock, config)
            val by = UserRef(principal.userId, principal.username)
            val moved = entry.copy(date = target, updatedAt = now)
            store.move(entry, moved, HistoryItem(now, by, "move", listOf(HistoryChange("date", entry.date.toString(), target.toString())))) to
                EntryMovedMessage(id, target.toString(), entry.date.toString(), entry.branch)
        }
        result.second?.let { publish(it, result.first) }
        return result.first.toDto()
    }

    // --- rules ---

    private fun rules() = EntryRules(
        exponents = config.currencies.associate { it.code to it.exponent },
        categories = categories.all().associate { it.key to CategoryRule(it.appliesTo, it.archived) },
        requireCategory = config.records.requireCategory,
        slipMaxCount = config.records.slipMaxCount,
    )

    /** [checkEntry] has passed, so every amount parses. */
    private fun tenders(dtos: List<TenderDto>): List<Tender> {
        val exponents = config.currencies.associate { it.code to it.exponent }
        return dtos.map { t -> exponents.getValue(t.currency).let { Tender(t.currency, t.method, Amount(parseAmount(t.amount, it)!!, it)) } }
    }

    private class NewSlip(val file: String, val bytes: ByteArray)

    /** The slips as files `slip-N.ext`, numbered from [firstNumber]; each must be a JPEG or a PNG within the size limit. */
    private fun slipFiles(slips: List<ByteArray>, firstNumber: Int): List<NewSlip> {
        val maxBytes = config.records.slipMaxSizeKb * 1024L
        return slips.mapIndexed { index, bytes ->
            if (bytes.size > maxBytes) throw ApiError(413, ErrorCode.PAYLOAD_TOO_LARGE, "A slip is larger than ${config.records.slipMaxSizeKb} KB.")
            val extension = detectSlipExtension(bytes) ?: throw badRequest("A slip must be a JPEG or a PNG image.", ErrorReasons.ENTRY_SLIP_TYPE)
            NewSlip("slip-${firstNumber + index}.$extension", bytes)
        }
    }

    private fun withinWindow(date: LocalDate) = !date.isBefore(today().minusDays(config.records.editWindowDays.toLong()))

    // --- who may do what ---

    private fun canView(principal: Principal, entry: Entry) =
        access.canBranch(principal, entry.branch) &&
            (access.has(principal, ENTRY_VIEW_ALL_NODE) || (access.has(principal, ENTRY_VIEW_OWN_NODE) && entry.createdBy.id == principal.userId))

    /** The entry on [date] with [id], if the caller may see it. */
    private fun lookup(principal: Principal, date: String, id: String): Entry {
        if (!isIsoDate(date)) throw badRequest("'$date' is not a date like 2026-09-27.", ErrorReasons.DATE_INVALID)
        if (!isUuidV7(id)) throw notFound("No such entry.")
        if (store.brokenEntry(id) != null) throw brokenError()
        val entry = store.find(id)?.takeIf { it.date.toString() == date } ?: throw notFound("No such entry.")
        access.requireBranch(principal, entry.branch)
        if (!canView(principal, entry)) throw access.forbidden("This needs the permission $ENTRY_VIEW_ALL_NODE.", ErrorReasons.PERMISSION_MISSING)
        return entry
    }

    /** Changing or deleting: anyone with the `.all` node; else the creator, with the `.own` node, while the entry is within the edit window. */
    private fun requireChange(principal: Principal, entry: Entry, ownNode: String, allNode: String) {
        if (access.has(principal, allNode)) return
        if (!access.has(principal, ownNode) || entry.createdBy.id != principal.userId) throw access.forbidden("This needs the permission $allNode.", ErrorReasons.PERMISSION_MISSING)
        if (!withinWindow(entry.date)) throw access.forbidden("This entry is past the edit window; changing it needs $allNode.", ErrorReasons.ENTRY_PAST_WINDOW)
    }

    /** The event of [entry] being created, with the names of its branch and category as they are now. */
    fun createdEvent(entry: Entry) = EntryCreatedEvent(entry.toDto(), branches.find(entry.branch)?.displayName ?: entry.branch, entry.category?.let { categories.find(it)?.name })

    /** Tells the listeners of the shop events, after the entry is saved. They cannot fail the request that already wrote it. */
    private fun publishEvent(event: () -> EntryCreatedEvent) {
        try {
            services.get(ShopEvents::class.java)?.publish(event())
        } catch (e: Exception) {
            Log.warn("could not publish entry.created: ${e.message}")
        }
    }

    /** Tells the users who may see [entry]. A push that fails is logged and never fails the request that caused it. */
    private fun publish(message: WsMessage, entry: Entry) {
        try {
            services.get(EventService::class.java)?.broadcastTo(message) { canView(it, entry) }
        } catch (e: Exception) {
            Log.warn("could not push ${message::class.simpleName}: ${e.message}")
        }
    }
}
