package xyz.felismp.shoparchive.app.client

import xyz.felismp.shoparchive.shared.AdminRoleDto
import xyz.felismp.shoparchive.shared.AdminUserDto
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CreateBranchRequest
import xyz.felismp.shoparchive.shared.CreateCategoryRequest
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.CreateUserRequest
import xyz.felismp.shoparchive.shared.UpdateBranchRequest
import xyz.felismp.shoparchive.shared.UpdateCategoryRequest
import xyz.felismp.shoparchive.shared.CreateNoteRequest
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.NotesUnread
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.SessionPreview
import xyz.felismp.shoparchive.shared.SlipCheckDto
import xyz.felismp.shoparchive.shared.SlipUseDto
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import xyz.felismp.shoparchive.shared.wire
import io.ktor.http.encodeURLParameter
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import xyz.felismp.shoparchive.shared.SessionDto

/** The server's answer to recording an entry: [created] is false when the id was there already (nothing changed, [entry] is the stored one). */
data class CreatedEntry(val entry: EntryDto, val created: Boolean)

/** What `GET /api/v1/entries` is asked for: [from] and [to] (`yyyy-MM-dd`) are required here; the rest narrows the list. */
data class EntryFilter(
    val from: String,
    val to: String,
    val branch: String? = null,
    val type: EntryType? = null,
    val currency: String? = null,
    val category: String? = null,
    val includeDeleted: Boolean = false,
) {
    /** The query string, `from=..&to=..` first, then only the filters that are set. */
    fun query(): String = listOfNotNull(
        "from" to from, "to" to to, branch?.let { "branch" to it }, type?.let { "type" to it.wire() },
        currency?.let { "currency" to it }, category?.let { "category" to it }, if (includeDeleted) "includeDeleted" to "true" else null,
    ).joinToString("&") { (k, v) -> k + "=" + v.encodeURLParameter() }
}

/** A file the server made for the person to keep, as it named it. */
class ExportFile(val fileName: String, val bytes: ByteArray)

/** The record calls the screens make; [ApiClient] is the real one, tests use a fake. */
interface RecordsApi {
    /** The branch's open day. Throws [ClientError.Api] with `NO_OPEN_SESSION` when none is open. */
    suspend fun currentSession(branch: String): SessionDto

    /** The sessions (open or closed) of the branch whose business date is [date] (`yyyy-MM-dd`). */
    suspend fun sessionsOn(branch: String, date: String): List<SessionDto>

    /** The sessions of the branch whose business date is from [from] to [to] (`yyyy-MM-dd`; the server takes at most 366 days), oldest first. */
    suspend fun sessionsBetween(branch: String, from: String, to: String): List<SessionDto>

    suspend fun openSession(branch: String, request: OpenSessionRequest): OpenSessionResponse

    suspend fun dashboard(from: String, to: String, branch: String): DashboardDto

    /** Item texts used lately, newest first, for suggestions. */
    suspend fun recentItems(category: String?): List<String>

    /** Records [request] with [slips] (JPEG bytes). Sending the same id again returns the entry already stored. */
    suspend fun createEntry(request: CreateEntryRequest, slips: List<ByteArray>): CreatedEntry

    /** The entries matching [filter] that the signed-in user may see, oldest first as the server sends them. */
    suspend fun entries(filter: EntryFilter): List<EntryDto>

    suspend fun entry(date: String, id: String): EntryDto

    /** Changes an entry; [slips] (JPEG bytes) are added to the kept ones named in [request]. */
    suspend fun updateEntry(date: String, id: String, request: UpdateEntryRequest, slips: List<ByteArray>): EntryDto

    /** Soft delete; needs a recent PIN or password. */
    suspend fun deleteEntry(date: String, id: String): EntryDto

    /** Moves an entry to business date [to]. */
    suspend fun moveEntry(date: String, id: String, to: String): EntryDto

    /** Slip number [n] of the entry (an image). */
    suspend fun slip(date: String, id: String, n: Int): ByteArray

    suspend fun history(date: String, id: String): List<HistoryItem>

    /** The entries, not deleted, that carry a slip with [code] (the text of its QR), oldest first. */
    suspend fun slipUses(code: String): List<SlipUseDto>

    /** What the bank that printed the slip with [code] says about it; null when no bank plugin on the server knows that kind of slip. */
    suspend fun slipCheck(code: String): SlipCheckDto?

    /** Closes the day session [id] of [branch] with the money counted. Answers the closed session. */
    suspend fun closeSession(branch: String, id: String, request: CloseSessionRequest): SessionDto

    /** What closing session [id] would expect in the drawer now, as the server works it out (the same way as the close). */
    suspend fun sessionPreview(branch: String, id: String): SessionPreview

    /** The newest messages of the outbox first, at most [limit]. Needs `shoparchive.notifications.view`. */
    suspend fun notifications(limit: Int): List<NotificationDto>

    /** Puts a sent or failed message into the outbox again. The server refuses (reason `notification.pending`) while it is still queued or unknown. */
    suspend fun resendNotification(id: String): NotificationDto

    /** Puts the summary of a closed day into the outbox again. */
    suspend fun notifyDay(branch: String, sessionId: String): NotificationDto

    /** The entries of the range as a CSV or XLSX file. [format] is `csv` or `xlsx`; [language] (`en`, `lo`, `th`) is the one the file is written in. */
    suspend fun export(from: String, to: String, branch: String, format: String, language: String): ExportFile

    /** The day notes of [branch] on [date] (`yyyy-MM-dd`) the signed-in user may see, oldest first. */
    suspend fun notes(branch: String, date: String): List<NoteDto>

    /** Leaves the note; sending the same id again returns the note already stored. */
    suspend fun createNote(request: CreateNoteRequest): NoteDto

    /** Marks one note read by the signed-in user. Needs `shoparchive.note.read`. */
    suspend fun markNoteRead(id: String): NoteDto

    /** Marks the notes of others on [date] in [branch] read; answers the day's notes. Needs `shoparchive.note.read`. */
    suspend fun markNotesRead(branch: String, date: String): List<NoteDto>

    /** How many notes of others in [branch], on any day, the signed-in user has not read, and the date of the oldest. Needs `shoparchive.note.read`. */
    suspend fun unreadNotes(branch: String): NotesUnread

    /** Runs [line] like the server console does and answers what it said, one entry per line. Needs the node of the command and a recent PIN or password. */
    suspend fun runCommand(line: String): List<String>

    /** The words that could end [line], from the commands the person may run; same needs as [runCommand]. */
    suspend fun completeCommand(line: String): List<String>

    // ---- Admin: what the console's `user`, `branch` and `category` commands do. Each needs a recent PIN or password; the users need an op. ----

    suspend fun adminUsers(): List<AdminUserDto>

    suspend fun adminRoles(): List<AdminRoleDto>

    /** Every user call answers the account as it is afterwards. */
    suspend fun addUser(request: CreateUserRequest): AdminUserDto

    suspend fun renameUser(name: String, to: String): AdminUserDto

    /** [role] is a role name or `none`. */
    suspend fun setUserRole(name: String, role: String): AdminUserDto

    suspend fun addUserBranch(name: String, branch: String): AdminUserDto

    suspend fun removeUserBranch(name: String, branch: String): AdminUserDto

    suspend fun setUserEnabled(name: String, enabled: Boolean): AdminUserDto

    /** Forgets the wrong tries and the lock; an account the server disabled for them is enabled again. */
    suspend fun unlockUser(name: String): AdminUserDto

    /** Takes the user off every device and clears the PIN and password, so the user sets a new PIN at the next login. */
    suspend fun resetUser(name: String): AdminUserDto

    /** Needs `shoparchive.branches.manage`. */
    suspend fun addBranch(request: CreateBranchRequest): BranchDto

    suspend fun updateBranch(key: String, request: UpdateBranchRequest): BranchDto

    /** Needs `shoparchive.categories.manage`. */
    suspend fun addCategory(request: CreateCategoryRequest): CategoryDto

    suspend fun updateCategory(key: String, request: UpdateCategoryRequest): CategoryDto
}
