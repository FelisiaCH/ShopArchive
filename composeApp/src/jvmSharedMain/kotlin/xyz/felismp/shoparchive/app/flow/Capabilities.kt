package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.PermissionNodes
import java.time.LocalDate

/**
 * What the signed-in person may do, from the permission nodes `GET /config` listed ([held]). The screens leave out what the
 * server would refuse instead of showing it disabled or failing on it. A person whose rights change gets the new set at the
 * next config load (the next unlock). Before the config has loaded nothing is allowed.
 */
class Capabilities(private val held: Set<String>, private val editWindowDays: Int = 0, private val userId: String = "") {
    val recordEntries: Boolean get() = PermissionNodes.ENTRY_CREATE in held
    val viewEntries: Boolean get() = PermissionNodes.ENTRY_VIEW_OWN in held || PermissionNodes.ENTRY_VIEW_ALL in held
    val openDay: Boolean get() = PermissionNodes.DAY_OPEN in held
    val closeDay: Boolean get() = PermissionNodes.DAY_CLOSE in held
    val dashboard: Boolean get() = PermissionNodes.DASHBOARD_VIEW in held
    val export: Boolean get() = PermissionNodes.EXPORT in held
    val viewNotifications: Boolean get() = PermissionNodes.NOTIFICATIONS_VIEW in held
    val sendNotifications: Boolean get() = PermissionNodes.NOTIFICATIONS_SEND in held

    /** The person holds the node of at least one console command: the server refuses every other line, so the screen is left out without one. */
    val console: Boolean get() = held.any { it.startsWith(PermissionNodes.COMMAND_PREFIX) }

    /**
     * Whether the person may edit or move [entry]: with the `.all` node always; otherwise with the `.own` node, when they recorded it
     * (compared by user id: [userId] from the config against the entry's `createdBy.id`, as the server does; a name can be renamed or reused) and its day is within `records.edit-window-days` of [today]. This
     * mirrors the server's rule so the buttons are left out; the server stays the authority and still words a refusal at a day boundary.
     */
    fun canEdit(entry: EntryDto, today: LocalDate): Boolean = canChange(entry, today, PermissionNodes.ENTRY_EDIT_OWN, PermissionNodes.ENTRY_EDIT_ALL)

    /** Whether the person may delete [entry]; the same rule as [canEdit] with the delete nodes. */
    fun canDelete(entry: EntryDto, today: LocalDate): Boolean = canChange(entry, today, PermissionNodes.ENTRY_DELETE_OWN, PermissionNodes.ENTRY_DELETE_ALL)

    private fun canChange(entry: EntryDto, today: LocalDate, own: String, all: String): Boolean {
        if (all in held) return true
        if (own !in held || userId.isEmpty() || entry.createdBy.id != userId) return false
        val day = runCatching { LocalDate.parse(entry.date) }.getOrNull() ?: return false
        return !day.isBefore(today.minusDays(editWindowDays.toLong()))
    }

    /** The Reports tabs the person may use, in order. */
    val reportsTabs: List<ReportsTab>
        get() = listOfNotNull(
            ReportsTab.OVERVIEW.takeIf { dashboard },
            ReportsTab.CLOSE_DAY.takeIf { closeDay },
            ReportsTab.EXPORT.takeIf { export },
            ReportsTab.NOTIFICATIONS.takeIf { viewNotifications },
        )

    /** False when every Reports tab is out, so Reports itself is left out of the navigation. */
    val reports: Boolean get() = reportsTabs.isNotEmpty()

    fun allows(destination: Destination): Boolean = when (destination) {
        Destination.TODAY, Destination.MORE -> true
        Destination.OPEN_DAY -> openDay
        Destination.RECORD -> recordEntries
        Destination.HISTORY -> viewEntries
        Destination.REPORTS -> reports
        Destination.CONSOLE -> console
    }

    companion object {
        val NONE = Capabilities(emptySet())
    }
}

/** The capabilities the loaded config grants; none while it has not loaded. */
internal fun ConfigState.capabilities(): Capabilities = (this as? ConfigState.Ready)?.let { Capabilities(it.config.permissions.toSet(), it.config.records.editWindowDays, it.config.userId) } ?: Capabilities.NONE
