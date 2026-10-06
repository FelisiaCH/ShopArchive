package xyz.felismp.shoparchive.server

import net.minecrell.terminalconsole.TerminalConsoleAppender
import org.jline.reader.Candidate
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch

/**
 * Console command loop, run on the main thread - the JVM stays alive because main() never returns.
 * On a real terminal it reads through JLine (prompt, history, tab completion); piped or run as a service,
 * it reads plain lines.
 */
internal class Console(private val commands: Commands) {
    fun run() {
        if (TerminalConsoleAppender.getTerminal() != null) runInteractive() else runPlain(System.`in`)
    }

    private fun runInteractive() {
        val reader = LineReaderBuilder.builder()
            .terminal(TerminalConsoleAppender.getTerminal())
            .appName("ShopArchive")
            .completer { _, line, candidates ->
                // words() ends with the word under the cursor, which is what Commands.complete expects.
                commands.complete(line.words()).forEach { candidates.add(Candidate(it)) }
            }
            .build()
        TerminalConsoleAppender.setReader(reader)
        try {
            while (true) {
                val line = try {
                    reader.readLine("> ")
                } catch (e: UserInterruptException) { // Ctrl+C
                    return Shutdown.stop(0)
                } catch (e: EndOfFileException) { // Ctrl+D
                    return Shutdown.stop(0)
                }
                commands.run(ConsoleSender, line)
            }
        } finally {
            TerminalConsoleAppender.setReader(null)
        }
    }

    internal fun runPlain(input: InputStream) {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        while (true) {
            val line = reader.readLine()
            if (line == null) {
                // stdin closed (e.g. a systemd service with StandardInput=null): block forever instead
                // of busy-looping or returning from main(), either of which would let the JVM exit.
                Log.info("stdin closed; server keeps running (send SIGTERM or Ctrl+C to stop)")
                CountDownLatch(1).await()
                return
            }
            // A UTF-8 BOM shows up on Windows whenever the console is piped from a UTF-8 file or from
            // PowerShell; without this the first command of such a session is always "unknown".
            commands.run(ConsoleSender, line.removePrefix("﻿"))
        }
    }
}
