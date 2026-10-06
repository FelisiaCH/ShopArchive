package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** How many events may wait for the listeners of plugins. They are small; the limit only stops a plugin that hangs from making the server hold events for ever. */
internal const val EVENT_QUEUE_LIMIT = 1000

/**
 * The listeners of the core (see [addCoreListener]) run first, one after the other on the thread that publishes: they are the outbox, which must have the
 * message before the write is answered. The listeners of plugins run later, in order, on one thread of their own ([Executor] [pluginRunner]), so a write never
 * waits for plugin code and a plugin that hangs cannot hold a record. When more than [EVENT_QUEUE_LIMIT] events wait for a hung plugin, further events are
 * not given to plugins and a warning says so. A listener that throws is logged and does not stop the others or reach the publisher.
 */
internal class DefaultShopEvents(
    private val pluginRunner: Executor = newPluginExecutor(EVENT_QUEUE_LIMIT),
) : ShopEvents {
    private class Subscription(val type: String, val listener: ShopEventListener)

    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private val coreListeners = CopyOnWriteArrayList<Subscription>()
    private val dropped = AtomicLong()

    override fun subscribe(type: String, listener: ShopEventListener): AutoCloseable {
        val subscription = Subscription(type, listener)
        subscriptions += subscription
        return AutoCloseable { subscriptions -= subscription }
    }

    /** A listener of the core: called on the publishing thread, before the plugins' listeners are queued. */
    fun addCoreListener(type: String, listener: ShopEventListener): AutoCloseable {
        val subscription = Subscription(type, listener)
        coreListeners += subscription
        return AutoCloseable { coreListeners -= subscription }
    }

    override fun publish(event: ShopEvent) {
        for (subscription in coreListeners) if (subscription.type == event.type) call(subscription, event)
        if (subscriptions.none { it.type == event.type }) return
        try {
            pluginRunner.execute {
                // The ones subscribed when the event is handled, so a plugin that was disabled meanwhile hears nothing.
                for (subscription in subscriptions) if (subscription.type == event.type) call(subscription, event)
            }
        } catch (e: RejectedExecutionException) {
            val count = dropped.incrementAndGet()
            if (count == 1L || count % 100 == 0L) Log.warn("The plugins' event listeners are not keeping up (a listener may be hung): $count events were not given to them, the last was ${event.type}")
        }
    }

    private fun call(subscription: Subscription, event: ShopEvent) {
        try {
            subscription.listener.onEvent(event)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            Log.warn("A listener of ${event.type} failed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    /** How many plugin events were dropped because the queue was full. */
    fun droppedCount(): Long = dropped.get()

    /** How many plugin listeners are subscribed; for `plugins` and tests. */
    fun subscriberCount(): Int = subscriptions.size

    /** Stops the thread of the plugins' listeners; events still waiting are not delivered. */
    fun close() {
        (pluginRunner as? ThreadPoolExecutor)?.shutdownNow()
    }

    companion object {
        /** One daemon thread, a queue of [limit]; what does not fit is refused (and counted by [publish]). */
        fun newPluginExecutor(limit: Int): ThreadPoolExecutor =
            ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue(limit)) { task -> Thread(task, "shop-events").apply { isDaemon = true } }
    }
}
