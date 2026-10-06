package testplugins.eventful

import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Listens to entry.created twice: the first listener always throws, the second writes a line to events.txt. Never closes its subscriptions. */
class EventfulPlugin : ShopPlugin() {
    override fun onEnable() {
        val events = context.services.get(ShopEvents::class.java)!!
        events.subscribe("entry.created", ShopEventListener { error("listener broke with secret-value-1234") })
        events.subscribe("entry.created", ShopEventListener { event: ShopEvent -> note(event) })
    }

    private fun note(event: ShopEvent) {
        Files.writeString(context.dataFolder.resolve("events.txt"), event.type + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
