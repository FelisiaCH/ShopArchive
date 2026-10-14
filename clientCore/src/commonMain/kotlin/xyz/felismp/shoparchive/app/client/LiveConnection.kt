package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import xyz.felismp.shoparchive.shared.WsMessage
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class ConnectionStatus { CONNECTED, CONNECTING, OFFLINE }

private const val FIRST_DELAY_MS = 1_000L
private const val MAX_DELAY_MS = 30_000L

/**
 * Keeps the WebSocket up while [hasToken] is true: reconnects with exponential backoff (1 s doubling to 30 s,
 * reset by a successful connect). [session] opens one socket and returns when it ends or throws when it cannot open.
 * [refetch] fires on every successful connect after the first attempt, so screens can reload what they missed.
 */
@OptIn(ExperimentalAtomicApi::class)
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

    private val job = AtomicReference<Job?>(null)
    // Wakes the backoff wait. Rendezvous: a signal only arrives while the loop waits, so none is left over to skip a later wait.
    private val retry = Channel<Unit>(Channel.RENDEZVOUS)

    /** Starts (or keeps) the loop; call it after unlocking. It ends by itself when the token is gone. */
    fun start() {
        while (true) {
            val current = job.load()
            if (current != null && !current.isCompleted && !current.isCancelled) return
            // Made first and started once it is the one kept, so two calls at the same time cannot run two loops.
            val fresh = scope.launch(start = CoroutineStart.LAZY) { run() }
            if (job.compareAndSet(current, fresh)) {
                fresh.start()
                return
            }
            fresh.cancel()
        }
    }

    /** Cuts a backoff wait short and restarts the backoff, e.g. when the app comes back after the phone slept; does nothing while connecting or connected. */
    fun retryNow() {
        if (job.load()?.isActive == true && _status.value == ConnectionStatus.OFFLINE) retry.trySend(Unit)
    }

    fun stop() {
        job.exchange(null)?.cancel()
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
                val woken = withTimeoutOrNull(wait) { retry.receive() } != null
                wait = if (woken) FIRST_DELAY_MS else minOf(wait * 2, MAX_DELAY_MS)
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
