package xyz.felismp.shoparchive.api

import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.CreateBranchRequest
import xyz.felismp.shoparchive.shared.CreateCategoryRequest
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.CreateNoteRequest
import xyz.felismp.shoparchive.shared.MarkNotesReadRequest
import xyz.felismp.shoparchive.shared.MoveEntryRequest
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.NotesUnread
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionPreview
import xyz.felismp.shoparchive.shared.SlipCheckDto
import xyz.felismp.shoparchive.shared.SlipUseDto
import xyz.felismp.shoparchive.shared.UpdateBranchRequest
import xyz.felismp.shoparchive.shared.UpdateCategoryRequest
import xyz.felismp.shoparchive.shared.UpdateEntryRequest

/**
 * The branches. Every method checks what the caller may do and throws [ApiError] if not.
 * Routes call it through the [ServiceRegistry], so a plugin can replace it.
 */
interface BranchService {
    /** The branches the caller works in: all of them with `shoparchive.branch.all`. Archived ones are marked. */
    fun list(principal: Principal): List<BranchDto>

    /** @throws ApiError 403 without `shoparchive.branches.manage`; 400 for a key that is not a slug; 409 if the key is taken; 401 [xyz.felismp.shoparchive.shared.ErrorCode.REAUTH_REQUIRED] unless the PIN or password was entered recently */
    fun create(principal: Principal, request: CreateBranchRequest): BranchDto

    /** Like [create], told the caller's address for the audit log; a service that keeps no log may leave this out. */
    fun create(principal: Principal, request: CreateBranchRequest, ip: String): BranchDto = create(principal, request)

    /** The key never changes. @throws ApiError 403 without `shoparchive.branches.manage`; 404 for an unknown key; 401 [xyz.felismp.shoparchive.shared.ErrorCode.REAUTH_REQUIRED] unless the PIN or password was entered recently */
    fun update(principal: Principal, key: String, request: UpdateBranchRequest): BranchDto

    /** Like [update], told the caller's address for the audit log. */
    fun update(principal: Principal, key: String, request: UpdateBranchRequest, ip: String): BranchDto = update(principal, key, request)
}

interface CategoryService {
    /** Every category, archived ones marked; any signed-in user may read them. */
    fun list(principal: Principal): List<CategoryDto>

    /** @throws ApiError 403 without `shoparchive.categories.manage`; 400 for a key that is not a slug; 409 if the key is taken; 401 [xyz.felismp.shoparchive.shared.ErrorCode.REAUTH_REQUIRED] unless the PIN or password was entered recently */
    fun create(principal: Principal, request: CreateCategoryRequest): CategoryDto

    /** Like [create], told the caller's address for the audit log; a service that keeps no log may leave this out. */
    fun create(principal: Principal, request: CreateCategoryRequest, ip: String): CategoryDto = create(principal, request)

    /** The key never changes. @throws ApiError 403 without `shoparchive.categories.manage`; 404 for an unknown key; 401 [xyz.felismp.shoparchive.shared.ErrorCode.REAUTH_REQUIRED] unless the PIN or password was entered recently */
    fun update(principal: Principal, key: String, request: UpdateCategoryRequest): CategoryDto

    /** Like [update], told the caller's address for the audit log. */
    fun update(principal: Principal, key: String, request: UpdateCategoryRequest, ip: String): CategoryDto = update(principal, key, request)
}

/** What `GET /api/v1/entries` filters by; [from] and [to] are `yyyy-MM-dd` and default to the current month. */
class EntryQuery(
    val from: String?,
    val to: String?,
    val branch: String?,
    val type: EntryType?,
    val currency: String?,
    val category: String?,
    val includeDeleted: Boolean,
)

/** [created] is false when the entry id existed already and [entry] is the stored one. */
class CreatedEntry(val entry: EntryDto, val created: Boolean)

class SlipFile(val bytes: ByteArray, val contentType: String)

/**
 * Entries and their slips. [slips] are the bytes of the images sent with the request, in order.
 * Every method checks what the caller may do and throws [ApiError] if not.
 */
interface EntryService {
    /**
     * Records an entry in the branch's open day. An id that exists already returns the stored entry, unchanged.
     * @throws ApiError 400 for a bad entry; 403; 409 with [xyz.felismp.shoparchive.shared.ErrorCode.NO_OPEN_SESSION]
     */
    fun create(principal: Principal, request: CreateEntryRequest, slips: List<ByteArray>): CreatedEntry

    fun list(principal: Principal, query: EntryQuery): List<EntryDto>

    /** @throws ApiError 404; 409 [xyz.felismp.shoparchive.shared.ErrorCode.ENTRY_BROKEN] for an entry whose files are damaged */
    fun get(principal: Principal, date: String, id: String): EntryDto

    /** Changes an entry and adds [newSlips]. @throws ApiError 403 for someone else's entry or one past the edit window without the `.all` permission */
    fun update(principal: Principal, date: String, id: String, request: UpdateEntryRequest, newSlips: List<ByteArray>): EntryDto

    /** Marks the entry deleted; nothing is removed from disk. Needs the PIN or password entered recently. */
    fun delete(principal: Principal, date: String, id: String): EntryDto

    /** Puts the entry on another business date. */
    fun move(principal: Principal, date: String, id: String, request: MoveEntryRequest): EntryDto

    /** Slip number [n] (the `N` of `slip-N.jpg`). */
    fun slip(principal: Principal, date: String, id: String, n: Int): SlipFile

    fun history(principal: Principal, date: String, id: String): List<HistoryItem>

    /**
     * The entries, not deleted, that carry a slip with this [code] (the text of its QR), oldest first. Needs `shoparchive.entry.create`.
     * @throws ApiError 400 for a text that cannot be a slip code; 403
     */
    fun slipUses(principal: Principal, code: String): List<SlipUseDto>

    /**
     * What the registered [SlipChecker] (a bank plugin) says about the slip with this [code], or null when there is none or it gave no answer. Needs `shoparchive.entry.create`.
     * @throws ApiError 400 for a text that cannot be a slip code; 403
     */
    fun slipCheck(principal: Principal, code: String): SlipCheckDto?
}

/** The day sessions: open the day with the change counted, close it with the drawer counted. */
interface SessionService {
    /**
     * Opens the branch's day. If it is open already that session is returned with [OpenSessionResponse.alreadyOpenBy] set.
     * @throws ApiError 403 without `shoparchive.day.open` or the branch; 400 for a bad float or an archived branch
     */
    fun open(principal: Principal, branch: String, request: OpenSessionRequest): OpenSessionResponse

    /** @throws ApiError 404 [xyz.felismp.shoparchive.shared.ErrorCode.NO_OPEN_SESSION] if no day is open */
    fun current(principal: Principal, branch: String): SessionDto

    /**
     * Closes the session once. @throws ApiError 400 for a missing count or a missing note when something differs;
     * 409 [xyz.felismp.shoparchive.shared.ErrorCode.SESSION_CLOSED] if it was closed already
     */
    fun close(principal: Principal, branch: String, id: String, request: CloseSessionRequest): SessionDto

    /**
     * What closing [id] would expect in the drawer right now, worked out like the close does. Needs the close permission.
     * @throws ApiError 404 for an unknown session; 409 [xyz.felismp.shoparchive.shared.ErrorCode.SESSION_CLOSED] if it is closed
     */
    fun preview(principal: Principal, branch: String, id: String): SessionPreview

    /** Sessions opened on business dates [from]..[to] (`yyyy-MM-dd`; default the current month), oldest first. */
    fun list(principal: Principal, branch: String, from: String?, to: String?): List<SessionDto>
}

/** A finished export: the file, its media type and the name to save it under. */
class ExportFile(val bytes: ByteArray, val contentType: String, val fileName: String)

/** The entries of a date range as a spreadsheet file. */
interface ExportService {
    /**
     * The entries [principal] could list with [query] (`from` and `to` are required), as `csv` or `xlsx`.
     * @throws ApiError 400 for a bad range or format; 403
     */
    fun export(principal: Principal, query: EntryQuery, format: String): ExportFile

    /**
     * Like the above, with the headers, sheet names and enum words in [language] (`lo`, `th` or `en`) and categories and branches by display name.
     * A null [language], or an implementation that does not know the parameter, gives the English of the first versions.
     */
    fun export(principal: Principal, query: EntryQuery, format: String, language: String?): ExportFile = export(principal, query, format)
}

/** The numbers of the dashboard, worked out from the entries when asked. */
interface DashboardService {
    /**
     * The totals, breakdowns and candles of [from]..[to] (`yyyy-MM-dd`, both needed) for the branches the caller works in, or only [branch].
     * Branch-level: it counts every user's entries. @throws ApiError 400 for a bad range; 403 without `shoparchive.dashboard.view` or for a branch out of scope
     */
    fun dashboard(principal: Principal, from: String?, to: String?, branch: String?, currency: String?): DashboardDto

    /** Up to 20 item texts, most recently used first, of the entries the caller may list (only those of [category] if given), for suggestions. */
    fun recentItems(principal: Principal, category: String?): List<String>
}

/** [created] is false when the note id existed already and [note] is the stored one. */
class CreatedNote(val note: NoteDto, val created: Boolean)

/** Day notes: what staff leave for the owner, written once, with who read them. Every method checks what the caller may do and throws [ApiError] if not. */
interface NoteService {
    /**
     * Leaves a note on the branch's current business day (the open day's date, else today). An id that exists already (from the same user) returns the stored note, unchanged.
     * @throws ApiError 400 for empty text or an archived branch; 403 without `shoparchive.note.create` or the branch; 409 for an id that is someone else's
     */
    fun create(principal: Principal, request: CreateNoteRequest): CreatedNote

    /**
     * The notes of [branch] on [date] (`yyyy-MM-dd`) the caller may see, oldest first: all of them with `shoparchive.note.read`, else the caller's own.
     * @throws ApiError 400 for a bad date; 403 without either note node or the branch
     */
    fun list(principal: Principal, branch: String, date: String): List<NoteDto>

    /** Marks one note as read by the caller (a note read before keeps its time). @throws ApiError 403 without `shoparchive.note.read` or the branch; 404 */
    fun markRead(principal: Principal, id: String): NoteDto

    /** Marks every note of others that the caller has not read, on one day of a branch; answers the day's notes as [list] does. @throws ApiError 400; 403 without `shoparchive.note.read` or the branch */
    fun markAllRead(principal: Principal, request: MarkNotesReadRequest): List<NoteDto>

    /** How many notes of others, on any day of [branch], the caller has not read, and the date of the oldest. @throws ApiError 403 without `shoparchive.note.read` or the branch */
    fun unread(principal: Principal, branch: String): NotesUnread
}
