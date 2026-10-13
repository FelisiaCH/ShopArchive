package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.app.client.CreatedEntry
import xyz.felismp.shoparchive.app.client.EntryFilter
import xyz.felismp.shoparchive.app.client.ExportFile
import xyz.felismp.shoparchive.app.client.RecordsApi
import xyz.felismp.shoparchive.shared.AuthPolicy
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.CreateNoteRequest
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.SessionPreview
import xyz.felismp.shoparchive.shared.SlipDto
import xyz.felismp.shoparchive.shared.SlipUseDto
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.NoteRead
import xyz.felismp.shoparchive.shared.NotesUnread
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import xyz.felismp.shoparchive.shared.RecordsPolicy
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionStatus
import xyz.felismp.shoparchive.shared.UserRef
import xyz.felismp.shoparchive.shared.WsMessage
import java.time.LocalDate

/** A scripted server for the screen state holders: set the fields, read what was asked. */
class FakeRecordsApi : RecordsApi {
    val alice = UserRef("u1", "alice")
    var current: SessionDto? = null
    var todaysSessions: List<SessionDto> = emptyList()
    var dashboard: DashboardDto = DashboardDto("2026-10-04", "2026-10-04", emptyList(), emptyList())
    var dashboardError: Exception? = null
    var currentError: Exception? = null
    var openAnswer: () -> OpenSessionResponse = { OpenSessionResponse(session("s-new")) }
    var openError: Exception? = null
    var items = listOf("Rice", "Rice paper", "Noodles")
    /** Throws for the n-th (1-based) createEntry call, if set. */
    var createFails: (Int) -> Exception? = { null }
    var createGate: CompletableDeferred<Unit>? = null
    /** The ids the fake "server" holds; a resend of one of them answers `created = false`. */
    val stored = linkedMapOf<String, EntryDto>()
    val dashboardCalls = mutableListOf<Triple<String, String, String>>()
    val opened = mutableListOf<Pair<String, OpenSessionRequest>>()
    val created = mutableListOf<Pair<CreateEntryRequest, List<ByteArray>>>()
    val itemCalls = mutableListOf<String?>()
    /** A request that reached the server but whose answer is lost: the entry is stored, then the call throws. */
    var storeThenFail = false

    /** The branch keys `GET /config` lists. */
    var configBranches: List<String> = emptyList()

    fun session(id: String, date: String = "2026-10-04", status: SessionStatus = SessionStatus.OPEN) =
        SessionDto(id, "main", date, alice, "${date}T08:12:00+07:00", mapOf("LAK" to "100000"), status)

    override suspend fun currentSession(branch: String): SessionDto {
        currentError?.let { throw it }
        return current ?: throw ClientError.Api(404, ErrorCode.NO_OPEN_SESSION, "No day is open.")
    }

    override suspend fun sessionsOn(branch: String, date: String) = todaysSessions

    override suspend fun openSession(branch: String, request: OpenSessionRequest): OpenSessionResponse {
        opened += branch to request
        openError?.let { throw it }
        return openAnswer()
    }

    override suspend fun dashboard(from: String, to: String, branch: String): DashboardDto {
        dashboardCalls += Triple(from, to, branch)
        dashboardError?.let { throw it }
        return dashboard
    }

    override suspend fun recentItems(category: String?): List<String> {
        itemCalls += category
        return items
    }

    override suspend fun createEntry(request: CreateEntryRequest, slips: List<ByteArray>): CreatedEntry {
        created += request to slips
        createGate?.await()
        createFails(created.size)?.let { throw it }
        val existing = stored[request.id]
        val entry = existing ?: EntryDto(
            request.id, "2026-10-04", request.type, request.branch, request.category, request.item, request.note, alice,
            "2026-10-04T09:00:00+07:00", "2026-10-04T09:00:00+07:00", false, request.tenders, emptyList(), "s1",
        ).also { stored[request.id] = it }
        if (storeThenFail) {
            storeThenFail = false
            throw ClientError.Unreachable()
        }
        return CreatedEntry(entry, created = existing == null)
    }

    // ---- History, Close day, Export ----

    /** What `entries` answers; every filter asked is kept in [entryFilters]. */
    var listed: List<EntryDto> = emptyList()
    var listError: Exception? = null
    val entryFilters = mutableListOf<EntryFilter>()
    var slipBytes: Map<Int, ByteArray> = emptyMap()
    var slipError: Exception? = null
    var historyItems: List<HistoryItem> = emptyList()
    var historyError: Exception? = null
    /** Thrown by update, delete and move when set. */
    var changeError: Exception? = null
    val updates = mutableListOf<Triple<String, UpdateEntryRequest, List<ByteArray>>>()
    val deletes = mutableListOf<String>()
    val moves = mutableListOf<Pair<String, String>>()
    var closeError: Exception? = null
    var closeAnswer: ((CloseSessionRequest) -> SessionDto)? = null
    val closes = mutableListOf<Triple<String, String, CloseSessionRequest>>()
    var previewAnswer = SessionPreview(emptyMap(), emptyMap(), emptyMap(), emptyMap())
    var previewError: Exception? = null
    val previewCalls = mutableListOf<Pair<String, String>>()
    var exportError: Exception? = null
    var exportFile = ExportFile("shoparchive-2026-10-01_2026-10-04.csv", byteArrayOf(1, 2, 3))
    val exports = mutableListOf<List<String>>()
    val entryReads = mutableListOf<Pair<String, String>>()

    override suspend fun entries(filter: EntryFilter): List<EntryDto> {
        entryFilters += filter
        listError?.let { throw it }
        return listed
    }

    override suspend fun entry(date: String, id: String): EntryDto {
        entryReads += date to id
        listError?.let { throw it }
        return stored[id] ?: listed.first { it.id == id }
    }

    private fun held(id: String) = stored[id] ?: listed.first { it.id == id }

    override suspend fun updateEntry(date: String, id: String, request: UpdateEntryRequest, slips: List<ByteArray>): EntryDto {
        updates += Triple(id, request, slips)
        changeError?.let { throw it }
        val old = held(id)
        val kept = old.slips.filter { request.keepSlips == null || it.file in request.keepSlips!! }
        val added = slips.indices.map { SlipDto("slip-${old.slips.size + it + 1}.jpg", "x") }
        return old.copy(
            type = request.type, category = request.category, item = request.item, note = request.note, tenders = request.tenders, slips = kept + added,
        ).also { stored[id] = it }
    }

    override suspend fun deleteEntry(date: String, id: String): EntryDto {
        deletes += id
        changeError?.let { throw it }
        return held(id).copy(deleted = true).also { stored[id] = it }
    }

    override suspend fun moveEntry(date: String, id: String, to: String): EntryDto {
        moves += id to to
        changeError?.let { throw it }
        return held(id).copy(date = to).also { stored[id] = it }
    }

    override suspend fun slip(date: String, id: String, n: Int): ByteArray {
        slipError?.let { throw it }
        return slipBytes[n] ?: byteArrayOf(n.toByte())
    }

    /** What `slipUses` answers for each code; every code asked is kept in [slipUseCalls]. */
    var slipUsed: Map<String, List<SlipUseDto>> = emptyMap()
    var slipUsesError: Exception? = null
    val slipUseCalls = mutableListOf<String>()

    override suspend fun slipUses(code: String): List<SlipUseDto> {
        slipUseCalls += code
        slipUsesError?.let { throw it }
        return slipUsed[code].orEmpty()
    }

    override suspend fun history(date: String, id: String): List<HistoryItem> {
        historyError?.let { throw it }
        return historyItems
    }

    override suspend fun closeSession(branch: String, id: String, request: CloseSessionRequest): SessionDto {
        closes += Triple(branch, id, request)
        closeError?.let { throw it }
        return closeAnswer!!(request)
    }

    override suspend fun sessionPreview(branch: String, id: String): SessionPreview {
        previewCalls += branch to id
        previewError?.let { throw it }
        return previewAnswer
    }

    var notificationList: List<xyz.felismp.shoparchive.shared.NotificationDto> = emptyList()
    var notificationsError: Exception? = null
    var resendError: Exception? = null
    val notificationCalls = mutableListOf<Int>()
    val resends = mutableListOf<String>()
    val dayNotifies = mutableListOf<Pair<String, String>>()

    private fun queuedCopy(of: String, code: String) = xyz.felismp.shoparchive.shared.NotificationDto(
        "n-new", code, "day.closed", xyz.felismp.shoparchive.shared.NotificationState.QUEUED, "main", "alice", "2026-10-04T20:00:00+07:00", 0, 0, resendOf = of, text = "x",
    )

    override suspend fun notifications(limit: Int): List<xyz.felismp.shoparchive.shared.NotificationDto> {
        notificationCalls += limit
        notificationsError?.let { throw it }
        return notificationList
    }

    override suspend fun resendNotification(id: String): xyz.felismp.shoparchive.shared.NotificationDto {
        resends += id
        resendError?.let { throw it }
        return queuedCopy(id, "20261004-AAAAA")
    }

    override suspend fun notifyDay(branch: String, sessionId: String): xyz.felismp.shoparchive.shared.NotificationDto {
        dayNotifies += branch to sessionId
        resendError?.let { throw it }
        return queuedCopy("", "20261004-AAAAA")
    }

    override suspend fun export(from: String, to: String, branch: String, format: String): ExportFile {
        exports += listOf(from, to, branch, format)
        exportError?.let { throw it }
        return exportFile
    }

    // ---- Notes ----

    /** The notes the fake "server" holds for every branch and day; `notes` answers the ones of the branch and date asked. The person is `u1`. */
    var noteList: List<NoteDto> = emptyList()
    var notesError: Exception? = null
    var createNoteError: Exception? = null
    var createNoteGate: CompletableDeferred<Unit>? = null
    var markNoteError: Exception? = null
    /** The business date the fake "server" puts a new note on (the open day's, which can be the day before). */
    var createdNoteDate = "2026-10-04"
    var unreadAnswer = NotesUnread(0)
    var markGate: CompletableDeferred<Unit>? = null
    /** Holds the next `notes` answer for that date until completed; the answer is what the list held when it was asked. */
    val notesGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var unreadError: Exception? = null
    val noteCalls = mutableListOf<Pair<String, String>>()
    val createdNotes = mutableListOf<CreateNoteRequest>()
    val noteReads = mutableListOf<String>()
    val noteReadAlls = mutableListOf<Pair<String, String>>()
    val unreadCalls = mutableListOf<String>()

    private fun readByMe(note: NoteDto) = if (note.reads.any { it.by.id == "u1" }) note else note.copy(reads = note.reads + NoteRead(alice, "2026-10-04T10:00:00+07:00"))

    override suspend fun notes(branch: String, date: String): List<NoteDto> {
        noteCalls += branch to date
        val answer = noteList.filter { it.branch == branch && it.date == date }
        notesGates.remove(date)?.await()
        notesError?.let { throw it }
        return answer
    }

    override suspend fun createNote(request: CreateNoteRequest): NoteDto {
        createdNotes += request
        createNoteGate?.await()
        createNoteError?.let { throw it }
        return noteList.firstOrNull { it.id == request.id }
            ?: NoteDto(request.id, request.branch, createdNoteDate, request.text.trim(), alice, "2026-10-04T09:00:00+07:00").also { noteList = noteList + it }
    }

    override suspend fun markNoteRead(id: String): NoteDto {
        noteReads += id
        markNoteError?.let { throw it }
        val done = readByMe(noteList.first { it.id == id })
        noteList = noteList.map { if (it.id == id) done else it }
        return done
    }

    override suspend fun markNotesRead(branch: String, date: String): List<NoteDto> {
        noteReadAlls += branch to date
        markGate?.await()
        markNoteError?.let { throw it }
        noteList = noteList.map { if (it.branch == branch && it.date == date && it.createdBy.id != "u1") readByMe(it) else it }
        return noteList.filter { it.branch == branch && it.date == date }
    }

    override suspend fun unreadNotes(branch: String): NotesUnread {
        unreadCalls += branch
        unreadError?.let { throw it }
        return unreadAnswer
    }

    var commandError: Exception? = null
    var commandAnswer: (String) -> List<String> = { listOf("ran $it") }
    var completions: List<String> = emptyList()
    val commandLines = mutableListOf<String>()
    val completeLines = mutableListOf<String>()

    var commandGate: CompletableDeferred<Unit>? = null

    override suspend fun runCommand(line: String): List<String> {
        commandLines += line
        commandGate?.await()
        commandError?.let { throw it }
        return commandAnswer(line)
    }

    override suspend fun completeCommand(line: String): List<String> {
        completeLines += line
        commandError?.let { throw it }
        return completions
    }
}

val LAK = CurrencyInfo("LAK", 0)
val THB = CurrencyInfo("THB", 2)

fun testConfig(
    policy: RecordsPolicy = RecordsPolicy(),
    branches: List<BranchDto> = listOf(BranchDto("main", "Main", false)),
    currencies: List<CurrencyInfo> = listOf(LAK, THB),
    permissions: List<String> = ALL_NODES,
    userId: String = "u1",
) = ConfigResponse(
    AuthPolicy(6, false, 3, 15, false, 5), emptyList(), currencies, branches,
    listOf(
        CategoryDto("sales", LocalizedName("ຂາຍ", "ขาย", "Sales"), AppliesTo.INCOME, false),
        CategoryDto("stock", LocalizedName("ສິນຄ້າ", "สินค้า", "Stock"), AppliesTo.EXPENSE, false),
        CategoryDto("misc", LocalizedName("ອື່ນໆ", "อื่นๆ", "Misc"), AppliesTo.BOTH, false),
        CategoryDto("old", LocalizedName("old", "old", "Old"), AppliesTo.BOTH, true),
    ),
    policy, permissions, userId,
)

/** Every node the app looks at, held: the person the screen tests play is allowed everything unless a test says otherwise. */
val ALL_NODES = with(xyz.felismp.shoparchive.shared.PermissionNodes) {
    listOf(ENTRY_CREATE, ENTRY_VIEW_OWN, ENTRY_VIEW_ALL, ENTRY_EDIT_OWN, ENTRY_EDIT_ALL, ENTRY_DELETE_OWN, ENTRY_DELETE_ALL, DAY_OPEN, DAY_CLOSE, EXPORT, DASHBOARD_VIEW, NOTE_CREATE, NOTE_READ, NOTIFICATIONS_VIEW, NOTIFICATIONS_SEND, COMMAND_PREFIX + "status")
}

/** Everything a screen state holder needs, wired to fakes. */
class Harness(scope: CoroutineScope, config: ConfigState = ConfigState.Ready(testConfig()), branch: String? = "main") {
    val api = FakeRecordsApi()
    val canWrite = MutableStateFlow(true)
    val config = MutableStateFlow(config)
    val branch = MutableStateFlow(branch)
    val refetch = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val messages = MutableSharedFlow<WsMessage>(extraBufferCapacity = 8)
    var date: LocalDate = LocalDate.parse("2026-10-04")
    var millis = 1_790_000_000_000L
    val calls = object : Calls {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }
    val env = Env(scope, api, calls, canWrite, this.config, this.branch, refetch, messages, { date }, { millis })
}

/** An entry as the server lists it. */
fun testEntry(
    id: String = "e1",
    date: String = "2026-10-04",
    type: xyz.felismp.shoparchive.shared.EntryType = xyz.felismp.shoparchive.shared.EntryType.EXPENSE,
    createdAt: String = "2026-10-04T09:00:00+07:00",
    tenders: List<xyz.felismp.shoparchive.shared.TenderDto> = listOf(xyz.felismp.shoparchive.shared.TenderDto("LAK", xyz.felismp.shoparchive.shared.TenderMethod.CASH, "150000")),
    slips: List<SlipDto> = emptyList(),
    deleted: Boolean = false,
    category: String? = "stock",
) = EntryDto(
    id, date, type, "main", category, "Ice", "", UserRef("u1", "alice"), createdAt, createdAt, deleted, tenders, slips, "s1",
)
