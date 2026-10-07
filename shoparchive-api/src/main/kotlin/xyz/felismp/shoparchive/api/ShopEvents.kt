package xyz.felismp.shoparchive.api

import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.SessionDto

/** The event types the core publishes. A plugin may publish and listen to types of its own. */
object ShopEventTypes {
    const val ENTRY_CREATED = "entry.created"
    const val DAY_CLOSED = "day.closed"
    const val DEVICE_NEW = "device.new"

    /** The types the core publishes. */
    val all: List<String> = listOf(ENTRY_CREATED, DAY_CLOSED, DEVICE_NEW)
}

/** Something that happened in the shop and was already saved. Events are facts: a listener cannot veto or change what they describe. */
interface ShopEvent {
    /** One of [ShopEventTypes], or a type a plugin made up. */
    val type: String

    /** The branch key. */
    val branch: String

    /** When it happened, like every time in the files: `2026-10-03T09:15:00+07:00`. */
    val at: String
}

/** A new entry was recorded (not an entry that was sent again, changed, moved or deleted). [branchName] is the branch's display name and [categoryName] the category's names, null if it has none. */
class EntryCreatedEvent(val entry: EntryDto, val branchName: String, val categoryName: LocalizedName? = null) : ShopEvent {
    override val type get() = ShopEventTypes.ENTRY_CREATED
    override val branch get() = entry.branch
    override val at get() = entry.createdAt
}

/**
 * One currency of a closed day. Every amount is text with the currency's decimals, like the DTOs; [variance] has a minus sign when the drawer is short.
 * [income] and [expense] are all the money of the day's entries that are not deleted, [net] is income minus expense; the cash and online (transfer)
 * columns split the same sums by how it was paid. [counted], [expected], [variance] and [handover] are what the close stored; [kept]
 * is what stays in the drawer for the next day, [counted] - [handover].
 */
class CurrencyDay(
    val currency: String,
    val income: String,
    val expense: String,
    val net: String,
    val cashIn: String,
    val cashOut: String,
    val onlineIn: String,
    val onlineOut: String,
    val float: String,
    val counted: String,
    val expected: String,
    val variance: String,
    val kept: String,
    val handover: String,
)

/** A day was closed. [session] is the closed session ([SessionDto.close] is set); [currencies] are the counted ones and any other the day's entries were in, in the shop's order. */
class DayClosedEvent(
    val session: SessionDto,
    val branchName: String,
    val currencies: List<CurrencyDay>,
    /** Entries of the day that are not deleted. */
    val entryCount: Int,
    /** Slip images of those entries. */
    val slipCount: Int,
    /** True for a day closed before the day's sums were kept with it: they were added up from the entries as they are now and may differ from what the close compared with. */
    val recomputed: Boolean = false,
) : ShopEvent {
    override val type get() = ShopEventTypes.DAY_CLOSED
    override val branch get() = session.branch
    override val at get() = session.close?.closedAt ?: session.openedAt
}

/**
 * A login put [username] on a device they were not on before: a new device, or a shared one that did not hold them. Logging in again on the same device is not one.
 * [deviceLabel] and [platform] are what the device is called on the server; [branch] is the user's first branch, empty if they have none.
 */
class NewDeviceEvent(
    val username: String,
    val deviceId: String,
    val deviceLabel: String,
    val platform: String,
    override val branch: String,
    override val at: String,
) : ShopEvent {
    override val type get() = ShopEventTypes.DEVICE_NEW
}

fun interface ShopEventListener {
    fun onEvent(event: ShopEvent)
}

/**
 * Tells plugins and the core what happened, after it was saved. Routes and records services call it through the [ServiceRegistry].
 *
 * Listeners of plugins run in the order of the events on one thread of the server's, after the write succeeded and not before the answer goes back to the app:
 * the write never waits for a plugin, and a listener that throws is logged while the others still run. A listener that hangs holds up the later events of all plugins;
 * when too many events wait, new ones are not delivered to plugins and the server logs a warning, so keep listeners short and put anything slow on your own thread.
 * The core's own listeners (the outbox that makes the messages for `notify.events`) are not among them and are never held up by plugins.
 * A plugin's subscriptions end when it is disabled or fails, even if it never closes them.
 */
interface ShopEvents {
    /** Calls [listener] for every event of [type] from now on; close the result to stop. */
    fun subscribe(type: String, listener: ShopEventListener): AutoCloseable

    /** Hands [event] to the listeners of its type. Never throws because of a listener. */
    fun publish(event: ShopEvent)
}
