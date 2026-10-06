package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.felismp.shoparchive.app.client.UpdatesApi
import xyz.felismp.shoparchive.shared.AppVersion
import xyz.felismp.shoparchive.shared.UpdateFile
import xyz.felismp.shoparchive.shared.UpdatePlatform
import xyz.felismp.shoparchive.shared.hasValidName
import xyz.felismp.shoparchive.shared.newestUpdate
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** What the platform does with a downloaded installer. [folder] is where installers are put: this app's own folder, never the system temp dir. */
interface AppUpdater {
    /** The files this platform installs: `.apk` on Android, `.msi` on Windows. */
    val platform: UpdatePlatform

    val folder: Path

    /**
     * Hands [file] (already checked) to the system's installer. [Install.Started] means the system took over (on Windows the app closes
     * itself first, so this may not return); [Install.NeedsPermission] means the person must allow it in the system settings that were just opened.
     */
    suspend fun install(file: Path): Install
}

enum class Install { Started, NeedsPermission }

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

/**
 * Looks for a newer installer on the server (once when it is made, i.e. at every unlock, and when the person asks), and downloads
 * and installs it when they say so. Everything about what counts as newer is in [newestUpdate]; the file is checked against the
 * SHA-256 and size the server listed before the installer sees it.
 */
class UpdateState(
    private val scope: CoroutineScope,
    private val api: UpdatesApi,
    private val calls: Calls,
    private val updater: AppUpdater,
    private val own: AppVersion,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    checkNow: Boolean = true,
) {
    private val _ui = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    init {
        if (checkNow) check(manual = false)
    }

    private fun busy() = _ui.value.let { it is UpdateUi.Checking || it is UpdateUi.Downloading }

    /** Asks the server. A check at start says nothing when it finds nothing or fails; one the person asked for ([manual]) says both. */
    fun check(manual: Boolean = true) {
        if (busy()) return
        if (manual) _ui.value = UpdateUi.Checking
        scope.launch {
            try {
                val files = calls.run { api.updates() }
                val newest = newestUpdate(files, updater.platform, own)
                _ui.value = when {
                    newest != null -> UpdateUi.Available(newest)
                    manual -> UpdateUi.UpToDate
                    else -> UpdateUi.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = if (manual) UpdateUi.Failed(null, UpdateProblem.Call(e.toFailure())) else UpdateUi.Idle
            }
        }
    }

    /** Hides the offer until the next check (the next unlock, or the person's own). */
    fun later() {
        if (!busy()) _ui.value = UpdateUi.Idle
    }

    /** Downloads the offered file (a file already here that matches is not fetched again), checks it and starts the installer. */
    fun install() {
        val file = when (val now = _ui.value) {
            is UpdateUi.Available -> now.file
            is UpdateUi.NeedsPermission -> now.file
            is UpdateUi.Failed -> now.file
            else -> null
        } ?: return
        install(file)
    }

    /** [file] is checked again here (name, metadata, folder, links) even though [newestUpdate] only offers valid ones: the listing came from the network. */
    internal fun install(file: UpdateFile) {
        _ui.value = UpdateUi.Downloading(file, 0)
        scope.launch {
            _ui.value = try {
                val target = withContext(io) { download(file) }
                when (updater.install(target)) {
                    Install.Started -> UpdateUi.Idle
                    Install.NeedsPermission -> UpdateUi.NeedsPermission(file)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DamagedDownload) {
                UpdateUi.Failed(file, UpdateProblem.Damaged)
            } catch (e: java.io.IOException) {
                UpdateUi.Failed(file, UpdateProblem.CannotInstall)
            } catch (e: Exception) {
                UpdateUi.Failed(file, if (e is xyz.felismp.shoparchive.app.client.ClientError) UpdateProblem.Call(e.toFailure()) else UpdateProblem.CannotInstall)
            }
        }
    }

    private class DamagedDownload : Exception()

    private suspend fun download(file: UpdateFile): Path {
        // Nothing is created, written or deleted for a name the server should never have listed.
        if (!file.hasValidName()) throw java.io.IOException("not a valid update file name: ${file.file}")
        val folder = updater.folder.toAbsolutePath().normalize()
        val target = folder.resolve(file.file).normalize()
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        if (target.parent != folder || Files.isSymbolicLink(target) || Files.isSymbolicLink(partial)) throw java.io.IOException("unsafe update target: $target")
        Files.createDirectories(folder)
        // Old installers are not kept: only the file being installed stays in the folder.
        Files.newDirectoryStream(folder).use { entries -> entries.filter { it != target }.forEach(Files::deleteIfExists) }
        if (!matches(target, file)) {
            api.downloadUpdate(file.file, target) { bytes -> _ui.value = UpdateUi.Downloading(file, bytes) }
            if (!matches(target, file)) {
                Files.deleteIfExists(target)
                throw DamagedDownload()
            }
        }
        return target
    }

    private fun matches(path: Path, file: UpdateFile): Boolean =
        Files.isRegularFile(path) && Files.size(path) == file.size && sha256(path) == file.sha256

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
