package xyz.felismp.shoparchive.server.net

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.tls.ServerCertificate
import java.util.concurrent.atomic.AtomicInteger

/** The server could not start listening (port in use, address not on this machine, ...). */
internal class HttpStartException(message: String, cause: Throwable) : Exception(message, cause)

/** What the HTTPS server is started with; read from the config once, so a change needs a restart. */
internal class HttpSettings(
    val bindAddress: String,
    val port: Int,
    val maxBodyBytes: Long,
    val timeoutSeconds: Int,
    val rateLimitPerMinute: Int = 10,
    /** Read per request, so a reload of the slip limits reaches it at once. */
    val entryBodyBytes: () -> Long = { maxBodyBytes },
)

/**
 * Counts open TCP connections: up when the listener accepts one, down when it closes. Sits on the listening
 * socket rather than on each connection, so it also counts a client that has not finished the TLS handshake.
 */
@ChannelHandler.Sharable
private class ConnectionCounter : ChannelInboundHandlerAdapter() {
    val open = AtomicInteger()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is Channel) {
            open.incrementAndGet()
            msg.closeFuture().addListener { open.decrementAndGet() }
        }
        super.channelRead(ctx, msg)
    }
}

/**
 * Closes a connection that has moved no data in either direction for the configured time. Ktor's own
 * read timeout only starts once a request has begun, so a client that stalls in the middle of its headers
 * (or just sits idle) would otherwise keep the connection for ever. A WebSocket's heartbeat must be faster than this.
 */
@ChannelHandler.Sharable
private class CloseWhenIdle : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) ctx.close() else super.userEventTriggered(ctx, evt)
    }
}

/** The HTTPS listener (Ktor on Netty). TLS only: there is no plain HTTP port. */
internal class HttpServer(
    private val settings: HttpSettings,
    private val certificate: ServerCertificate,
    private val services: ServiceRegistry,
) {
    private val connections = ConnectionCounter()

    private val engine = embeddedServer(Netty, configure = {
        // All three are the one `request-timeout-seconds`: a client too slow to send a request or to take a response, or silent for that long, is dropped.
        requestReadTimeoutSeconds = settings.timeoutSeconds
        responseWriteTimeoutSeconds = settings.timeoutSeconds
        channelPipelineConfig = {
            addLast(IdleStateHandler(0, 0, settings.timeoutSeconds))
            addLast(CloseWhenIdle())
        }
        configureBootstrap = { handler(connections) }
        sslConnector(certificate.keyStore, certificate.alias, { certificate.password }, { certificate.password }) {
            host = settings.bindAddress
            port = settings.port
        }
    }) {
        apiModule(services, settings.maxBodyBytes, settings.timeoutSeconds, settings.rateLimitPerMinute, settings.entryBodyBytes)
    }

    val openConnections: Int get() = connections.open.get()

    /** Starts listening, or throws [HttpStartException]. */
    fun start() {
        try {
            engine.start(wait = false)
        } catch (e: Exception) {
            runCatching { engine.stop(0, 0) }
            throw HttpStartException("cannot listen on ${settings.bindAddress}:${settings.port}: ${rootMessage(e)}", e)
        }
    }

    /** Stops accepting, gives running requests a moment to finish, then closes the rest. */
    fun stop() = engine.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)

    private fun rootMessage(e: Throwable): String {
        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }
}
