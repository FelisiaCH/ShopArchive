package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The two kinds of listener: the core's own run at once on the publisher, plugins' run on a thread of their own that a hung plugin cannot hold up the write with. */
class ShopEventsTest {
    private fun event(type: String = "entry.created") = object : ShopEvent {
        override val type = type
        override val branch = "main"
        override val at = "2026-10-03T15:00:00+07:00"
    }

    @Test
    fun theCoreListenersRunBeforeThePluginListenersAreEvenQueued() {
        val order = mutableListOf<String>()
        val events = DefaultShopEvents(Runnable::run)
        events.subscribe("entry.created", ShopEventListener { order += "plugin" })
        events.addCoreListener("entry.created", ShopEventListener { order += "core" })

        events.publish(event())

        assertEquals(listOf("core", "plugin"), order)
    }

    @Test
    fun aPluginListenerThatHangsNeitherBlocksThePublisherNorTheCoreListener() {
        val release = CountDownLatch(1)
        val inside = CountDownLatch(1)
        val events = DefaultShopEvents()
        var core = 0
        events.addCoreListener("entry.created", ShopEventListener { core++ })
        events.subscribe("entry.created", ShopEventListener { inside.countDown(); release.await() })

        events.publish(event())
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the plugin listener was called on its own thread")
        events.publish(event())
        events.publish(event())

        assertEquals(3, core, "the publisher came back every time, and the core listener ran each time")
        release.countDown()
        events.close()
    }

    @Test
    fun whenTheQueueIsFullFurtherEventsAreNotGivenToPluginsAndAreCounted() {
        val release = CountDownLatch(1)
        val events = DefaultShopEvents(DefaultShopEvents.newPluginExecutor(2))
        var core = 0
        events.addCoreListener("entry.created", ShopEventListener { core++ })
        events.subscribe("entry.created", ShopEventListener { release.await() })

        repeat(10) { events.publish(event()) }

        assertEquals(10, core)
        assertTrue(events.droppedCount() in 6..8, events.droppedCount().toString())
        release.countDown()
        events.close()
    }

    @Test
    fun aCoreListenerThatThrowsDoesNotStopThePluginListeners() {
        val events = DefaultShopEvents(Runnable::run)
        var heard = 0
        events.addCoreListener("entry.created", ShopEventListener { error("broke") })
        events.subscribe("entry.created", ShopEventListener { heard++ })

        events.publish(event())

        assertEquals(1, heard)
    }

    @Test
    fun aPluginThatWasClosedBeforeItsEventWasHandledHearsNothing() {
        val queued = mutableListOf<Runnable>()
        val events = DefaultShopEvents { queued += it }
        var heard = 0
        val subscription = events.subscribe("entry.created", ShopEventListener { heard++ })

        events.publish(event())
        subscription.close()
        queued.forEach { it.run() }

        assertEquals(0, heard)
    }
}
