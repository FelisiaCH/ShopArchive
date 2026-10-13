package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.NotificationImages
import xyz.felismp.shoparchive.api.ShopEventTypes
import xyz.felismp.shoparchive.api.SlipFile
import xyz.felismp.shoparchive.server.Log
import java.io.IOException

internal fun slipContentType(file: String) = if (file.endsWith(".png")) "image/png" else "image/jpeg"

/** The slips of the entry an `entry.created` message is about (its subject), as they are now; a slip that cannot be read is logged and left out. */
internal class DefaultNotificationImages(private val store: RecordStore) : NotificationImages {
    override fun images(notification: Notification): List<SlipFile> {
        if (notification.event != ShopEventTypes.ENTRY_CREATED) return emptyList()
        val entry = notification.subject?.let(store::find) ?: return emptyList()
        return entry.slips.mapNotNull { slip ->
            try {
                SlipFile(store.slipBytes(entry, slip.file), slipContentType(slip.file))
            } catch (e: IOException) {
                Log.warn("Notification ${notification.id}: the file ${slip.file} of entry ${entry.id} cannot be read (${e.javaClass.simpleName}); the message goes without it")
                null
            }
        }
    }
}
