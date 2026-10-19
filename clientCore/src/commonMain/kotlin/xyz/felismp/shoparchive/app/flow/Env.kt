package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import xyz.felismp.shoparchive.app.client.RecordsApi
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryMovedMessage
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.NoteChangedMessage
import xyz.felismp.shoparchive.shared.SessionClosedMessage
import xyz.felismp.shoparchive.shared.SessionOpenedMessage
import xyz.felismp.shoparchive.shared.WsMessage
import kotlinx.datetime.LocalDate

/** The shop's rules and lists (branches, categories, currencies) as the server last told them. */
sealed interface ConfigState {
    data object Loading : ConfigState
    data class Ready(val config: ConfigResponse) : ConfigState
    data class Failed(val failure: Failure) : ConfigState
}

/** What the screen state holders of one unlocked session share. Everything here ends with the session. */
class Env(
    val scope: CoroutineScope,
    val api: RecordsApi,
    val calls: Calls,
    /** True while the live connection is up: recording and opening the day need it. */
    val canWrite: StateFlow<Boolean>,
    val config: StateFlow<ConfigState>,
    /** The branch the person works in; null until the config is there or when the user has none. */
    val branch: StateFlow<String?>,
    /** Fires after the live connection came back: reload what was missed. */
    val refetch: Flow<Unit>,
    val messages: Flow<WsMessage>,
    /** The calendar date where the shop is. */
    val today: () -> LocalDate,
    val now: () -> Long,
    /** The language the app shows now (`en`, `lo` or `th`; the system's when none is chosen): what a file the server writes for the person is written in. */
    val language: () -> String = { "en" },
) {
    /** What the person may do, from the config as it stands now. */
    val can: Capabilities get() = config.value.capabilities()
}

/** The branch a live message is about, or null for messages that are not about a branch's records or notes. */
internal fun WsMessage.recordBranch(): String? = when (this) {
    is EntryCreatedMessage -> branch
    is EntryUpdatedMessage -> branch
    is EntryDeletedMessage -> branch
    is EntryMovedMessage -> branch
    is SessionOpenedMessage -> branch
    is SessionClosedMessage -> branch
    is NoteChangedMessage -> branch
    else -> null
}
