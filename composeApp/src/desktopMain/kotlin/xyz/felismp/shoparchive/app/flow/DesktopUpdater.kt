package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.app.client.PlatformContext
import xyz.felismp.shoparchive.app.client.appDir
import xyz.felismp.shoparchive.app.client.isWindows
import xyz.felismp.shoparchive.shared.UpdatePlatform
import java.nio.file.Path
import kotlin.system.exitProcess

/** Windows only: the installer is an MSI. Elsewhere (development) there is nothing to install an update with. */
actual fun createAppUpdater(context: PlatformContext): AppUpdater? =
    if (isWindows) WindowsUpdater(Path.of(System.getenv("LOCALAPPDATA") ?: appDir().toString(), "ShopArchive", "updates")) else null

/**
 * Runs `msiexec /i <file>` and closes the app, so the installer can replace the files it is running from.
 * The download is in [folder] under the user's app data, and stays until the next update clears it.
 */
class WindowsUpdater(
    override val folder: Path,
    private val start: (List<String>) -> Unit = { ProcessBuilder(it).start() },
    private val exit: () -> Unit = { exitProcess(0) },
) : AppUpdater {
    override val platform = UpdatePlatform.WINDOWS

    override suspend fun install(file: Path): Install {
        start(listOf("msiexec", "/i", file.toString()))
        exit()
        return Install.Started
    }
}
