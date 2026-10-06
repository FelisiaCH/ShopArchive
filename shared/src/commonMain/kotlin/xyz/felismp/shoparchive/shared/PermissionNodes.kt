package xyz.felismp.shoparchive.shared

/**
 * The permission nodes the app shows or hides screens by. The server registers these same strings (its node registry
 * takes them from here), and `GET /config` lists the ones the caller holds in [ConfigResponse.permissions].
 */
object PermissionNodes {
    const val ENTRY_CREATE = "shoparchive.entry.create"
    const val ENTRY_VIEW_OWN = "shoparchive.entry.view.own"
    const val ENTRY_VIEW_ALL = "shoparchive.entry.view.all"
    const val ENTRY_EDIT_OWN = "shoparchive.entry.edit.own"
    const val ENTRY_EDIT_ALL = "shoparchive.entry.edit.all"
    const val ENTRY_DELETE_OWN = "shoparchive.entry.delete.own"
    const val ENTRY_DELETE_ALL = "shoparchive.entry.delete.all"
    const val DAY_OPEN = "shoparchive.day.open"
    const val DAY_CLOSE = "shoparchive.day.close"
    const val EXPORT = "shoparchive.export"
    const val DASHBOARD_VIEW = "shoparchive.dashboard.view"
    const val NOTIFICATIONS_VIEW = "shoparchive.notifications.view"
    const val NOTIFICATIONS_SEND = "shoparchive.notifications.send"

    /** A node that starts like this lets its holder run that command in the app's console; the app shows the console to anyone who holds one. */
    const val COMMAND_PREFIX = "shoparchive.command."
}
