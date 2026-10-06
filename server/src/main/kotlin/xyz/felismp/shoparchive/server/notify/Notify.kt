package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.DayClosedEvent
import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.NotificationService
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.ShopEventTypes
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.net.CORE_SERVICE_PRIORITY
import xyz.felismp.shoparchive.server.records.stamp
import xyz.felismp.shoparchive.shared.NotificationState
import java.nio.file.Path
import java.time.Clock

/** The notifier the core ships: it only logs one line, so a server without a channel plugin still shows what would have been sent. Always succeeds. */
internal class LogNotifier : Notifier {
    override fun deliver(notification: Notification): DeliveryResult {
        Log.info("Notification ${notification.id}: ${describe(notification)}")
        return DeliveryResult.Sent
    }
}

/**
 * The notifications of the core: puts a message in the outbox for every event listed in `notify.events`, sends the outbox in the background
 * and shows its state. [closedDay] gives the summary of a closed day for [NotificationService.notifyDay] (null if there is none).
 *
 * The outbox is the only thing between a record and its message, so the file of a message is written (and fsynced) before the write that
 * caused it is answered, on the thread of the request: a few milliseconds, and nothing that waits for a channel. Sending happens on the worker only.
 */
internal class Notify(
    root: Path,
    private val config: ConfigService,
    private val services: ServiceRegistry,
    closedDay: (branch: String, id: String) -> DayClosedEvent?,
    /** The events of the records made at or after the time, oldest first: what reconciling compares with the outbox. */
    private val recentEvents: (since: java.time.Instant) -> List<ShopEvent> = { emptyList() },
    private val clock: Clock = Clock.systemUTC(),
    deliverer: Deliverer = ThreadedDeliverer(),
    barrier: xyz.felismp.shoparchive.server.DataBarrier = xyz.felismp.shoparchive.server.DataBarrier(),
) {
    private val store = OutboxStore(root, config, clock, barrier)
    val outbox = Outbox(store, config, { services.get(Notifier::class.java) }, clock, deliverer)
    private val subscriptions = mutableListOf<AutoCloseable>()

    private val access = xyz.felismp.shoparchive.server.records.Access(services)
    private val service = DefaultNotificationService(outbox, access, closedDay, config, clock)

    /** Makes the check of the outbox against the records and the live listener not queue the same message twice. */
    private val enqueueLock = Any()

    @Volatile
    private var reconcileNeeded = false

    init {
        outbox.housekeeping = { if (reconcileNeeded) { reconcileNeeded = false; reconcile() } }
        services.register(Notifier::class.java, LogNotifier(), CORE_SERVICE_PRIORITY, "core")
        services.register(NotificationService::class.java, service, CORE_SERVICE_PRIORITY, "core")
    }

    /** Reads the outbox (messages left from before are still to send) and starts listening for events. The worker is started apart, with `outbox.start()`. */
    fun listen() {
        store.load()
        reconcile()
        val events = services.get(ShopEvents::class.java) ?: error("ShopEvents is not registered")
        // Every type, whatever `notify.events` says now: a reload of the list applies from the next event without a restart.
        for (type in ShopEventTypes.all) {
            val listener = ShopEventListener(::onEvent)
            // The core's own listener runs before the plugins' and on the publishing thread, whatever the plugins do.
            subscriptions += (events as? DefaultShopEvents)?.addCoreListener(type, listener) ?: events.subscribe(type, listener)
        }
    }

    /**
     * Queues the message of every record of the last `notify.reconcile-days` days (and not before [Outbox.since]) whose event is in `notify.events` and that has no
     * message yet, so a record saved just before the server stopped, or whose message could not be made, still gets one. Safe to run again: a record that has a
     * message (of any state, a repeat included) is left alone. Returns how many it queued.
     */
    fun reconcile(): Int {
        val days = config.notify.reconcileDays
        if (days == 0) return 0
        val from = maxOf(outbox.since, clock.instant().minus(java.time.Duration.ofDays(days.toLong())))
        var queued = 0
        for (event in recentEvents(from)) {
            if (event.type !in config.notify.events) continue
            val draft = draftFor(event, config.locale, stamp(clock, config)) ?: continue
            if (enqueueOnce(draft)) queued++
        }
        if (queued > 0) Log.info("Notifications: $queued records had no message; queued them")
        return queued
    }

    /** Queues [draft] unless its record has a message already. */
    private fun enqueueOnce(draft: Draft): Boolean = synchronized(enqueueLock) {
        if (draft.subject != null && outbox.items().any { it.subject == draft.subject && it.notification.event == draft.event }) return false
        outbox.enqueue(draft)
        true
    }

    /** Stops listening and stops the worker; a message being sent is left unknown. */
    fun close() {
        subscriptions.forEach { it.close() }
        subscriptions.clear()
        outbox.stop()
    }

    private fun onEvent(event: ShopEvent) {
        if (event.type !in config.notify.events) return
        try {
            val draft = draftFor(event, config.locale, stamp(clock, config)) ?: return
            enqueueOnce(draft)
        } catch (e: Exception) {
            // The record is saved; the worker's next round queues its message from the records.
            Log.warn("Could not queue the message of ${event.type}: ${e.javaClass.simpleName}: ${e.message}; it is queued again from the records shortly")
            reconcileNeeded = true
            outbox.wake()
        }
    }

    /** The lines `status` adds. */
    fun statusLines(): List<String> {
        val items = outbox.items()
        fun count(state: NotificationState) = items.count { it.state == state }
        val lines = mutableListOf(
            "Notifications: ${count(NotificationState.QUEUED)} queued, ${count(NotificationState.UNKNOWN)} unknown (no answer yet, tried again by itself), " +
                "${count(NotificationState.FAILED)} failed, ${count(NotificationState.SENT)} sent; via ${services.get(Notifier::class.java)?.javaClass?.simpleName ?: "no notifier"}",
        )
        items.lastOrNull { it.state == NotificationState.FAILED }?.let {
            lines += "Last notification failure: ${it.id} (${it.notification.code}) at ${it.lastAttempt}: ${it.lastError}. Send it again with: notify resend ${it.id}"
        }
        return lines
    }
}
