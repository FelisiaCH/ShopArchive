package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import xyz.felismp.shoparchive.app.client.UpdatesApi
import xyz.felismp.shoparchive.shared.UpdateFile

/** Why an update did not go on. */
sealed interface UpdateProblem {
    /** A call to the server failed: said like any other failed call. */
    data class Call(val failure: Failure) : UpdateProblem

    /** What arrived is not the file the server listed (wrong size or SHA-256): it is deleted and never installed. */
    data object Damaged : UpdateProblem

    /** The file could not be written here, or the system refused to start the installer. */
    data object CannotInstall : UpdateProblem
}

/** What the update banner and the Settings section show. Nothing is offered unless a newer file for this platform exists. */
sealed interface UpdateUi {
    /** Nothing to say: no check yet, nothing newer, the person said later, or this platform cannot install updates. */
    data object Idle : UpdateUi

    /** A check the person asked for is running. */
    data object Checking : UpdateUi

    /** A check the person asked for found nothing newer. */
    data object UpToDate : UpdateUi

    data class Available(val file: UpdateFile) : UpdateUi

    data class Downloading(val file: UpdateFile, val bytes: Long) : UpdateUi

    /** The file is downloaded and checked; the system wants the person's permission to install from this app first. */
    data class NeedsPermission(val file: UpdateFile) : UpdateUi

    /** [file] is null when the check itself failed. */
    data class Failed(val file: UpdateFile?, val problem: UpdateProblem) : UpdateUi
}

/** Looks for a newer installer on the server and installs it when the person says so; what the update banner and the Settings section work with. */
interface AppUpdates {
    val ui: StateFlow<UpdateUi>

    /** Asks the server. A check at start says nothing when it finds nothing or fails; one the person asked for ([manual]) says both. */
    fun check(manual: Boolean = true)

    /** Hides the offer until the next check (the next unlock, or the person's own). */
    fun later()

    /** Downloads the offered file, checks it and starts the installer. */
    fun install()
}

/** Makes the [AppUpdates] of one unlocked session, working with the session's [UpdatesApi] and [Calls]. */
typealias UpdatesFactory = (scope: CoroutineScope, api: UpdatesApi, calls: Calls) -> AppUpdates
