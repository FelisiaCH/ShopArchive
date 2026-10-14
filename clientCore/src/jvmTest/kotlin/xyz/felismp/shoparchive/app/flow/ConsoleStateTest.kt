package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleStateTest {
    private fun TestScope.setup(): Pair<Harness, ConsoleState> {
        val h = Harness(backgroundScope)
        return h to ConsoleState(h.env)
    }

    private fun ConsoleState.run(line: String) {
        setInput(line)
        send()
    }

    @Test fun aLineIsRunAndItsAnswerIsShownBelowIt() = runTest(UnconfinedTestDispatcher()) {
        val (h, console) = setup()
        h.api.commandAnswer = { listOf("one", "two") }
        console.run("  status ")

        assertEquals(listOf("status"), h.api.commandLines)
        assertEquals(listOf(ConsoleLine.Typed("status"), ConsoleLine.Out("one"), ConsoleLine.Out("two")), console.ui.value.lines)
        assertEquals("", console.ui.value.input)
        assertFalse(console.ui.value.busy)
    }

    @Test fun anEmptyLineIsNotSentNorOneWhileOfflineNorOneWhileAnotherIsRunning() = runTest(UnconfinedTestDispatcher()) {
        val (h, console) = setup()
        console.run("   ")
        h.canWrite.value = false
        console.run("offline")
        assertTrue(console.ui.value.blocked)
        h.canWrite.value = true
        assertEquals(emptyList(), h.api.commandLines)

        val gate = CompletableDeferred<Unit>()
        h.api.commandGate = gate
        console.run("slow")
        assertTrue(console.ui.value.busy)
        console.run("second")
        assertEquals(listOf("slow"), h.api.commandLines)
        gate.complete(Unit)
        assertFalse(console.ui.value.busy)
    }

    @Test fun aRefusalIsShownAsAFailureWithItsReason() = runTest(UnconfinedTestDispatcher()) {
        val (h, console) = setup()
        h.api.commandError = ClientError.Api(403, ErrorCode.FORBIDDEN, "no", reason = ErrorReasons.COMMAND_CONSOLE_ONLY)
        console.run("op noy")

        assertEquals(ConsoleLine.Typed("op noy"), console.ui.value.lines[0])
        assertEquals(ConsoleLine.Failed(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.COMMAND_CONSOLE_ONLY)), console.ui.value.lines[1])
        assertFalse(console.ui.value.busy)
    }

    @Test fun theUpAndDownArrowsWalkThroughTheLinesRunBeforeAndBackToWhatWasTyped() = runTest(UnconfinedTestDispatcher()) {
        val (_, console) = setup()
        for (line in listOf("status", "user list", "version")) console.run(line)
        console.setInput("sta")

        console.previous()
        assertEquals("version", console.ui.value.input)
        console.previous()
        assertEquals("user list", console.ui.value.input)
        console.previous()
        console.previous()
        assertEquals("status", console.ui.value.input, "stops at the oldest")
        console.next()
        assertEquals("user list", console.ui.value.input)
        console.next()
        console.next()
        assertEquals("sta", console.ui.value.input, "back to what was typed")
        console.next()
        assertEquals("sta", console.ui.value.input)
    }

    @Test fun theSameLineTwiceInARowIsKeptOnceInTheHistory() = runTest(UnconfinedTestDispatcher()) {
        val (_, console) = setup()
        repeat(2) { console.run("status") }
        console.previous()
        console.previous()
        assertEquals("status", console.ui.value.input)
        console.next()
        assertEquals("", console.ui.value.input)
    }

    @Test fun tabPutsInTheWholeWordWhenThereIsOneAndTheCommonStartWhenThereAreSeveral() = runTest(UnconfinedTestDispatcher()) {
        val (h, console) = setup()
        h.api.completions = listOf("status")
        console.setInput("sta")
        console.complete()
        assertEquals("status ", console.ui.value.input)
        assertEquals(listOf("sta"), h.api.completeLines)

        h.api.completions = listOf("user", "userinfo")
        console.setInput("us")
        console.complete()
        assertEquals("user", console.ui.value.input)

        h.api.completions = listOf("plugin", "perm")
        console.setInput("user p")
        console.complete()
        assertEquals("user p", console.ui.value.input, "nothing to add")
        assertEquals(ConsoleLine.Out("plugin  perm"), console.ui.value.lines.last(), "the choices are listed instead")
    }

    @Test fun theOldestLinesGoWhenTheConsoleIsFull() = runTest(UnconfinedTestDispatcher()) {
        val (h, console) = setup()
        h.api.commandAnswer = { List(CONSOLE_MAX_LINES) { n -> "line $n" } }
        console.run("help")
        console.run("again")

        val lines = console.ui.value.lines
        assertEquals(CONSOLE_MAX_LINES, lines.size)
        assertEquals(ConsoleLine.Out("line ${CONSOLE_MAX_LINES - 1}"), lines.last())
        assertIs<ConsoleLine.Out>(lines.first())
    }
}
