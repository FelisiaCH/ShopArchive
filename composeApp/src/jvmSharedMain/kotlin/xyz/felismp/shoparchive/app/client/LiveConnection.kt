package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import xyz.felismp.shoparchive.shared.WsMessage

enum class ConnectionStatus { CONNECTED, CONNECTING, OFFLINE }

private const val FIRST_DELAY_MS = 1_000L
private const val MAX_DELAY_MS = 30_000L

/**
 * Keeps the WebSocket up while [hasToken] is true: reconnects with exponential backoff (1 s doubling to 30 s,
 * reset by a successful connect). [session] opens one socket and returns when it ends or throws when it cannot open.
 * [refetch] fires on every successful connect after the first attempt, so screens can reload what they missed.
 */
class LiveConnection(
    private val scope: CoroutineScope,
    private val hasToken: () -> Boolean,
    private val session: suspend (onOpen: () -> Unit, onText: (String) -> Unit) -> Unit,
) {
    private val _status = MutableStateFlow(ConnectionStatus.OFFLINE)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<WsMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<WsMessage> = _messages.asSharedFlow()

    private val _refetch = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val refetch: SharedFlow<Unit> = _refetch.asSharedFlow()

    private var job: Job? = null

    /** Starts (or keeps) the loop; call it after unlocking. It ends by itself when the token is gone. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _status.value = ConnectionStatus.OFFLINE
    }

    private suspend fun run() {
        var attempt = 0
        var wait = FIRST_DELAY_MS
        try {
            while (hasToken()) {
                _status.value = ConnectionStatus.CONNECTING
                try {
                    session(
                        {
                            _status.value = ConnectionStatus.CONNECTED
                            wait = FIRST_DELAY_MS
                            if (attempt > 0) _refetch.tryEmit(Unit)
                        },
                        { text -> decode(text)?.let { _messages.tryEmit(it) } },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Could not open: fall through to the backoff.
                }
                attempt++
                if (!hasToken()) break
                _status.value = ConnectionStatus.OFFLINE
                delay(wait)
                wait = minOf(wait * 2, MAX_DELAY_MS)
            }
        } finally {
            _status.value = ConnectionStatus.OFFLINE
        }
    }

    private fun decode(text: String): WsMessage? = try {
        clientJson.decodeFromString(WsMessage.serializer(), text)
    } catch (_: SerializationException) {
        null // a message type this app does not know yet
    }
}

/** A [LiveConnection] over this client's socket. */
fun ApiClient.liveConnection(scope: CoroutineScope) = LiveConnection(scope, { isUnlocked }, ::webSocketSession)
