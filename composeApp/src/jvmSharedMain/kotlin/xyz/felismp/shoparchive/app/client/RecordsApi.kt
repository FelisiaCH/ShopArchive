package xyz.felismp.shoparchive.app.client

import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.SessionPreview
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

    /** Moves an entry to business date [to]; needs a recent PIN or password. */
    suspend fun moveEntry(date: String, id: String, to: String): EntryDto

    /** Slip number [n] of the entry (an image). */
    suspend fun slip(date: String, id: String, n: Int): ByteArray

    suspend fun history(date: String, id: String): List<HistoryItem>

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

    /** The entries of the range as a CSV or XLSX file; needs a recent PIN or password. [format] is `csv` or `xlsx`. */
    suspend fun export(from: String, to: String, branch: String, format: String): ExportFile

    /** Runs [line] like the server console does and answers what it said, one entry per line. Needs the node of the command and a recent PIN or password. */
    suspend fun runCommand(line: String): List<String>

    /** The words that could end [line], from the commands the person may run; same needs as [runCommand]. */
    suspend fun completeCommand(line: String): List<String>
}
