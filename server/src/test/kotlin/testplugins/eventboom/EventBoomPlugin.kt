package testplugins.eventboom

import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Subscribes in onEnable, then fails: the server must end the subscription. */
class EventBoomPlugin : ShopPlugin() {
    override fun onEnable() {
        context.services.get(ShopEvents::class.java)!!.subscribe("entry.created", ShopEventListener {
            Files.writeString(context.dataFolder.resolve("events.txt"), "called\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        })
        error("enable failed after subscribing")
    }
}
