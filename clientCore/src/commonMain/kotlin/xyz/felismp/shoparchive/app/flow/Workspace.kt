package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.app.client.ServerApi
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.ConfigChangedMessage
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.WsMessage
import kotlinx.datetime.LocalDate

/** Where the shell is. Open day is under Today in the navigation, the console and Manage shop under More. */
enum class Destination { TODAY, OPEN_DAY, RECORD, HISTORY, REPORTS, NOTES, CONSOLE, ADMIN, MORE }

/**
 * Everything the signed-in person's screens keep: the shop's lists, the chosen branch, where they are in the app and the Record draft.
 * It lives in the session scope, so a lock ends it and the next user starts empty.
 */
class Workspace(
    private val scope: CoroutineScope,
    private val api: ServerApi,
    calls: Calls,
    val canWrite: StateFlow<Boolean>,
    refetch: Flow<Unit>,
    messages: Flow<WsMessage>,
    /** The branch chosen last time on this device. */
    savedBranch: String?,
    private val saveBranch: (String) -> Unit,
    compressor: SlipCompressor,
    reader: SlipReader,
    today: () -> LocalDate,
    now: () -> Long,
    /** Told every config the server answers, e.g. to keep the addresses it lists. */
    private val onConfig: (ConfigResponse) -> Unit = {},
    /** Makes what installs a newer app from the server's `downloads/`; null where the platform cannot (nothing about updates is shown then). */
    updatesFactory: UpdatesFactory? = null,
    /** The language the app shows now (see [Env.language]). */
    language: () -> String = { "en" },
) {
    private val _config = MutableStateFlow<ConfigState>(ConfigState.Loading)
    val config: StateFlow<ConfigState> = _config.asStateFlow()

    /** What the person may do; the screens leave out the rest. */
    val capabilities: StateFlow<Capabilities> = config.map { it.capabilities() }.stateIn(scope, SharingStarted.Eagerly, Capabilities.NONE)

    private val chosen = MutableStateFlow(savedBranch)
    private val _branch = MutableStateFlow<String?>(null)

    /** The branch being worked in: the chosen one if the server still lists it, else the first; null before the config is there or when there is none. */
    val branch: StateFlow<String?> = _branch.asStateFlow()

    private val _destination = MutableStateFlow(Destination.TODAY)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val env = Env(scope, api, calls, canWrite, config, branch, refetch, messages, today, now, language)
    val today = TodayState(env)
    val openDay = OpenDayState(env, this.today)
    val record = RecordState(env, this.today, compressor, reader)
    val history = HistoryState(env, compressor, reader)
    val closeDay = CloseDayState(env, this.today)
    val reports = ReportsState(env)
    val export = ExportState(env)
    val notifications = NotificationsState(env)
    val notes = NotesState(env)
    val console = ConsoleState(env)
    val admin = AdminState(env) { load() }

    /** Looks for a newer app once now (so at every unlock) and when asked; null where the platform cannot install updates. */
    val updates: AppUpdates? = updatesFactory?.invoke(scope, api, env.calls)

    private val _reportsTab = MutableStateFlow(ReportsTab.OVERVIEW)
    val reportsTab: StateFlow<ReportsTab> = _reportsTab.asStateFlow()

    /** The branches the person may use (the server lists only those). */
    val branches: List<BranchDto>
        get() = (config.value as? ConfigState.Ready)?.config?.branches.orEmpty().filter { !it.archived }

    init {
        scope.launch { load() }
        scope.launch { refetch.collect { load() } }
        // Branches or categories were changed (by an admin on another device): the lists on screen are the new ones.
        scope.launch { messages.collect { if (it is ConfigChangedMessage) load() } }
    }

    private suspend fun load() {
        try {
            val config = env.calls.run { api.config() }
            _config.value = ConfigState.Ready(config)
            // A change of rights can take away the screen the person is on.
            val can = ConfigState.Ready(config).capabilities()
            if (!can.allows(_destination.value)) _destination.value = Destination.TODAY
            if (_reportsTab.value !in can.reportsTabs) can.reportsTabs.firstOrNull()?.let { _reportsTab.value = it }
            onConfig(config)
            val usable = config.branches.filter { !it.archived }.map { it.key }
            _branch.value = chosen.value?.takeIf { it in usable } ?: usable.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_config.value !is ConfigState.Ready) _config.value = ConfigState.Failed(e.toFailure())
        }
    }

    /** Asks for the shop's lists again (after a failure). */
    fun retry() {
        scope.launch { load() }
    }

    fun go(to: Destination) {
        if (!env.can.allows(to)) return
        _destination.value = to
    }

    /** Opens Reports on [tab] (Close day is also reached from Today and from Open day). */
    fun openReports(tab: ReportsTab) {
        if (tab !in env.can.reportsTabs) return
        _reportsTab.value = tab
        _destination.value = Destination.REPORTS
    }

    /** Opens Notes on [day] (`yyyy-MM-dd`), e.g. the oldest day Today counts an unread note on; on the day it showed last when null. */
    fun openNotes(day: String? = null) {
        if (!env.can.allows(Destination.NOTES)) return
        day?.let(notes::show)
        _destination.value = Destination.NOTES
    }

    /**
     * The system Back: what the screen's own Back or Cancel does, one step toward Today (an edit, a delete or move card, then the entry, then History; Open day; a page
     * under More). [folded]: Reports and Notes sit under More. False on Today, where nothing is open and Back leaves the app.
     */
    fun back(folded: Boolean): Boolean {
        when (_destination.value) {
            Destination.TODAY -> return false
            Destination.HISTORY -> history.detail.value?.let { open ->
                val ui = open.ui.value
                when {
                    ui.edit != null -> open.cancelEdit()
                    ui.confirmDelete -> open.cancelDelete()
                    ui.moveText != null -> open.cancelMove()
                    else -> history.closeDetail()
                }
            } ?: run { _destination.value = Destination.TODAY }
            Destination.OPEN_DAY -> { openDay.reset(); _destination.value = Destination.TODAY }
            Destination.REPORTS, Destination.NOTES -> _destination.value = if (folded) Destination.MORE else Destination.TODAY
            Destination.CONSOLE, Destination.ADMIN -> _destination.value = Destination.MORE
            Destination.RECORD, Destination.MORE -> _destination.value = Destination.TODAY
        }
        return true
    }

    fun selectBranch(key: String) {
        if (key !in branches.map { it.key }) return
        chosen.value = key
        _branch.value = key
        saveBranch(key)
    }
}
