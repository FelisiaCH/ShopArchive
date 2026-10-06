package xyz.felismp.shoparchive.app.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.shared.SayMessage
import xyz.felismp.shoparchive.shared.WsMessage
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LiveConnectionTest {
    /** Scripted sockets: each entry decides what one connect attempt does. */
    private class Script(val steps: MutableList<suspend (onOpen: () -> Unit, onText: (String) -> Unit) -> Unit>) {
        val attemptTimes = mutableListOf<Long>()
    }

    private fun TestScope.live(script: Script, hasToken: () -> Boolean = { true }) =
        LiveConnection(this, hasToken) { onOpen, onText ->
            script.attemptTimes += testScheduler.currentTime
            script.steps.removeFirstOrNull()?.invoke(onOpen, onText) ?: throw java.io.IOException("no more steps")
        }

    @Test fun backoffDoublesUpToThirtySecondsAndStatusFollows() = runTest {
        val script = Script(mutableListOf())
        val live = live(script) // every attempt fails
        assertEquals(ConnectionStatus.OFFLINE, live.status.value)
        live.start()
        runCurrent()
        assertEquals(ConnectionStatus.OFFLINE, live.status.value) // attempt 1 failed, waiting
        advanceTimeBy(70_000)
        runCurrent()
        // attempts at 0, 1, 3, 7, 15, 31, 61 s -> gaps 1, 2, 4, 8, 16, 30 (capped)
        assertEquals(listOf(0L, 1_000, 3_000, 7_000, 15_000, 31_000, 61_000), script.attemptTimes)
        live.stop()
    }

    @Test fun connectedStatusMessagesAndRefetchOnReconnect() = runTest {
        val drop = CompletableDeferred<Unit>()
        val script = Script(mutableListOf(
            { open, text -> open(); text("""{"type":"say","message":"hi","from":"bob"}"""); text("""{"type":"unknown.thing"}"""); drop.await() },
            { open, _ -> open(); CompletableDeferred<Unit>().await() },
        ))
        val live = live(script)
        val messages = mutableListOf<WsMessage>()
        var refetches = 0
        val j1 = backgroundScopeCollect(live) { messages += it }
        val j2 = backgroundScopeRefetch(live) { refetches++ }
        live.start()
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, live.status.value)
        assertEquals(listOf<WsMessage>(SayMessage("hi", "bob")), messages) // unknown type skipped
        assertEquals(0, refetches) // first connect: nothing missed

        drop.complete(Unit) // socket ends
        runCurrent()
        assertEquals(ConnectionStatus.OFFLINE, live.status.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, live.status.value)
        assertEquals(1, refetches)
        assertEquals(listOf(0L, 1_000L), script.attemptTimes) // backoff restarted at one second after a good connect
        live.stop()
        assertEquals(ConnectionStatus.OFFLINE, live.status.value)
        j1.cancel(); j2.cancel()
    }

    @Test fun stopsLoopingWhenTheTokenIsGone() = runTest {
        var token = true
        val script = Script(mutableListOf({ _, _ -> token = false; throw java.io.IOException("401") }))
        val live = live(script) { token }
        live.start()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, script.attemptTimes.size)
        assertEquals(ConnectionStatus.OFFLINE, live.status.value)
    }

    private fun TestScope.backgroundScopeCollect(live: LiveConnection, f: (WsMessage) -> Unit) =
        backgroundScope.launchCollect(live.messages, f)

    private fun TestScope.backgroundScopeRefetch(live: LiveConnection, f: () -> Unit) =
        backgroundScope.launchCollect(live.refetch) { f() }
}
