package xyz.felismp.shoparchive.app.flow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import xyz.felismp.shoparchive.app.client.PlatformContext
import xyz.felismp.shoparchive.shared.UpdatePlatform
import java.nio.file.Path

actual fun createAppUpdater(context: PlatformContext): AppUpdater? = AndroidUpdater(context.applicationContext)

/**
 * Installs an APK through the system's package installer (an `ACTION_VIEW` of a content URI from this app's FileProvider; the system asks
 * the person to confirm). The app needs "install unknown apps" for itself: if that is off, the system's page for it is opened instead and
 * [Install.NeedsPermission] is returned, so the person can allow it and tap Install again. The download is in the app's cache folder.
 */
class AndroidUpdater(private val context: Context) : AppUpdater {
    override val platform = UpdatePlatform.ANDROID
    override val folder: Path = context.cacheDir.toPath().resolve("updates")

    override suspend fun install(file: Path): Install {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return Install.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file.toFile())
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return Install.Started
    }
}
