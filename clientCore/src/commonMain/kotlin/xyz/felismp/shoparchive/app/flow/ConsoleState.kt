package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The most lines the console keeps on screen; the oldest go first. */
internal const val CONSOLE_MAX_LINES = 500

/** The most lines kept to bring back with the up arrow. */
internal const val CONSOLE_MAX_HISTORY = 100

/** One line of the console: what the person ran, what the server said, or why a line did not run (worded by the screen). */
sealed interface ConsoleLine {
    data class Typed(val text: String) : ConsoleLine
    data class Out(val text: String) : ConsoleLine
    data class Failed(val failure: Failure) : ConsoleLine
}

/** [input] is the text field; [busy] while a line or a completion is with the server; [blocked] while the live connection is down. */
data class ConsoleUi(val lines: List<ConsoleLine>, val input: String, val busy: Boolean, val blocked: Boolean) {
    val canSend: Boolean get() = input.isNotBlank() && !busy && !blocked
}

/**
 * The console: the commands of the server's console, typed in the app. The server decides what a person may run and what it
 * says; this keeps what was typed and answered (until the session ends), the history for the up arrow, and tab completion.
 */
class ConsoleState(private val env: Env) {
    private val _ui = MutableStateFlow(ConsoleUi(emptyList(), "", busy = false, blocked = !env.canWrite.value))
    val ui: StateFlow<ConsoleUi> = _ui.asStateFlow()

    private val history = ArrayList<String>()

    /** Where the arrow keys are in [history]; -1 when not browsing, and then [draft] is what was typed. */
    private var cursor = -1
    private var draft = ""

    init {
        env.scope.launch { env.canWrite.map { !it }.collect { blocked -> _ui.value = _ui.value.copy(blocked = blocked) } }
    }

    private fun change(next: (ConsoleUi) -> ConsoleUi) {
        _ui.value = next(_ui.value)
    }

    private fun add(vararg more: ConsoleLine) = change { it.copy(lines = (it.lines + more).takeLast(CONSOLE_MAX_LINES)) }

    fun setInput(text: String) {
        cursor = -1
        change { it.copy(input = text) }
    }

    /** Runs the line in the text field; its answer is added below it, and the field is emptied. */
    fun send() {
        val line = _ui.value.input.trim()
        if (!_ui.value.canSend) return
        if (history.lastOrNull() != line) history += line
        if (history.size > CONSOLE_MAX_HISTORY) history.removeAt(0)
        cursor = -1
        add(ConsoleLine.Typed(line))
        change { it.copy(input = "", busy = true) }
        env.scope.launch {
            try {
                add(*env.calls.run { env.api.runCommand(line) }.map { ConsoleLine.Out(it) }.toTypedArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                add(ConsoleLine.Failed(e.toFailure()))
            }
            change { it.copy(busy = false) }
        }
    }

    /** The up arrow: the line run before the one in the field. */
    fun previous() {
        if (history.isEmpty()) return
        if (cursor == -1) {
            draft = _ui.value.input
            cursor = history.size
        }
        cursor = (cursor - 1).coerceAtLeast(0)
        change { it.copy(input = history[cursor]) }
    }

    /** The down arrow: the line run after the one in the field, and at the end what was typed before the arrows. */
    fun next() {
        if (cursor == -1) return
        cursor++
        if (cursor >= history.size) {
            cursor = -1
            change { it.copy(input = draft) }
        } else {
            change { it.copy(input = history[cursor]) }
        }
    }

    /**
     * Tab: asks the server which words could end the line and puts in what they all start with (the whole word and a space when there
     * is only one). When that adds nothing and there are several, they are listed. The answer is dropped if the field changed meanwhile.
     */
    fun complete() {
        val typed = _ui.value.input
        if (_ui.value.busy || _ui.value.blocked || typed.isBlank()) return
        change { it.copy(busy = true) }
        env.scope.launch {
            try {
                val candidates = env.calls.run { env.api.completeCommand(typed) }
                if (_ui.value.input == typed) applyCompletion(typed, candidates)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                add(ConsoleLine.Failed(e.toFailure()))
            }
            change { it.copy(busy = false) }
        }
    }

    private fun applyCompletion(typed: String, candidates: List<String>) {
        if (candidates.isEmpty()) return
        val head = typed.substring(0, typed.lastIndexOf(' ') + 1)
        val word = typed.substring(head.length)
        val common = candidates.reduce { a, b -> a.commonPrefixWith(b) }
        if (common.length > word.length || candidates.size == 1) {
            change { it.copy(input = head + common + if (candidates.size == 1) " " else "") }
        } else {
            add(ConsoleLine.Out(candidates.joinToString("  ")))
        }
    }
}
