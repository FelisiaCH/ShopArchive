package xyz.felismp.shoparchive.server.net

import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.shared.SayMessage
import xyz.felismp.shoparchive.shared.WsMessage
import java.util.concurrent.CopyOnWriteArrayList

/** Seconds between the server's pings on an open WebSocket. */
internal const val WS_PING_SECONDS = 15

/**
 * The ping interval for a server whose connections are dropped after [timeoutSeconds] of silence
 * (`network.request-timeout-seconds`): [WS_PING_SECONDS], or half the timeout if that is shorter, so a live
 * socket is never silent long enough to be dropped.
 */
internal fun wsPingSeconds(timeoutSeconds: Int): Int = if (timeoutSeconds > WS_PING_SECONDS) WS_PING_SECONDS else maxOf(1, timeoutSeconds / 2)

private val json = Json { encodeDefaults = true }

/** The open WebSockets and what is pushed to them. */
internal class DefaultEventService : EventService {
    private class Socket(val principal: Principal, val send: (String) -> Boolean)

    private val sockets = CopyOnWriteArrayList<Socket>()

    override fun register(principal: Principal, send: (String) -> Boolean): AutoCloseable {
        val socket = Socket(principal, send)
        sockets += socket
        return AutoCloseable { sockets -= socket }
    }

    override fun broadcast(message: WsMessage): Int = push(message) { true }

    override fun broadcastTo(message: WsMessage, wanted: (Principal) -> Boolean): Int = push(message, wanted)

    override fun notifyUser(userId: String, message: WsMessage, exceptDeviceId: String?) {
        // By account, not by name: after a rename the name can belong to someone else.
        push(message) { it.userId == userId && it.deviceId != exceptDeviceId }
    }

    override fun openConnections(): Int = sockets.size

    private fun push(message: WsMessage, wanted: (Principal) -> Boolean): Int {
        val text = json.encodeToString(WsMessage.serializer(), message)
        // A socket that cannot take the message (its queue is full) is slow or dead; its own handler closes it.
        return sockets.filter { wanted(it.principal) }.count { it.send(text) }
    }
}

/** `say <message>`: shows the message in every open app. */
internal fun registerSayCommand(commands: CommandRegistry, services: ServiceRegistry) {
    commands.register(object : Command {
        override val name = "say"
        override val description = "Send a message to every connected app"

        override fun execute(sender: CommandSender, args: List<String>) {
            if (args.isEmpty()) return sender.sendMessage("Usage: say <message>")
            val events = services.get(EventService::class.java) ?: return sender.sendMessage("The server is not ready to send messages")
            val reached = events.broadcast(SayMessage(args.joinToString(" ").take(MAX_SAY_CHARS), from = "console"))
            sender.sendMessage("Sent to $reached connected ${if (reached == 1) "app" else "apps"}")
        }
    }, "core")
}

private const val MAX_SAY_CHARS = 1000
