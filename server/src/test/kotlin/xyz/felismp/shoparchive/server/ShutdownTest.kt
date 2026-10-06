package xyz.felismp.shoparchive.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.ConfigService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ShutdownTest {
    @TempDir
    lateinit var dir: Path

    // --- in-process: the registry itself (stop() exits the JVM, so it is only exercised in a child) ---

    @Test
    fun hooksRunInReverseRegistrationOrder() {
        val order = mutableListOf<String>()
        Shutdown.register("a") { order += "a" }
        Shutdown.register("b") { order += "b" }
        Shutdown.register("c") { order += "c" }

        Shutdown.runHooks()

        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test
    fun throwingHookDoesNotStopTheRestButMarksFailure() {
        val order = mutableListOf<String>()
        Shutdown.register("first") { order += "first" }
        Shutdown.register("boom") { throw RuntimeException("boom") }
        Shutdown.register("last") { order += "last" }

        Shutdown.runHooks()

        // "boom" ran (and threw) between "last" and "first" - both survivors ran, and the process is failed.
        assertEquals(listOf("last", "first"), order)
        assertTrue(Shutdown.hasFailed())
    }

    @Test
    fun failMarksTheProcessFailed() {
        // fail() is one-way (there's exactly one JVM shutdown to report); this only checks it flips,
        // not that it can flip back.
        Shutdown.fail()
        assertTrue(Shutdown.hasFailed())
    }

    // --- child JVM: exit codes and JVM shutdown hook completion ---

    private class Result(val exitCode: Int, val ran: List<String>, val output: String)

    private fun runChild(scenario: String): Result {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val output = dir.resolve("$scenario.out")
        // The child inherits nothing from the Gradle test task: pass the tmpdir on and skip perf data
        // (hsperfdata), so it cannot write outside the checkout.
        val process = ProcessBuilder(
            java, "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}", "-XX:-UsePerfData",
            "-cp", System.getProperty("java.class.path"), "xyz.felismp.shoparchive.server.ShutdownChild", scenario, dir.toString()
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        // A hang (e.g. exitProcess from inside a shutdown hook) must fail the test, not block the build.
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("child '$scenario' did not exit within 30s; output:\n${Files.readString(output)}")
        }
        val lines = Files.readAllLines(output)
        return Result(process.exitValue(), lines.filter { it.startsWith("ran:") }.map { it.removePrefix("ran:") }, lines.joinToString("\n"))
    }

    @Test
    fun stopRunsHooksInReverseOrderAndExitsZero() {
        val result = runChild("normal")

        assertEquals(0, result.exitCode)
        assertEquals(listOf("c", "b", "a"), result.ran)
    }

    @Test
    fun stopExitsWithTheRequestedCode() {
        val result = runChild("stop-code")

        assertEquals(7, result.exitCode)
        assertEquals(listOf("a"), result.ran)
    }

    @Test
    fun throwingRegistryHookMakesStopExitOneAndTheRestStillRan() {
        val result = runChild("throwing")

        assertEquals(1, result.exitCode)
        assertEquals(listOf("last", "first"), result.ran)
    }

    @Test
    fun failThenStopZeroExitsOne() {
        val result = runChild("fail-then-stop")

        assertEquals(1, result.exitCode)
        assertEquals(listOf("a"), result.ran)
    }

    @Test
    fun slowRawJvmShutdownHookIsNotCutOff() {
        val result = runChild("slow-raw-hook")

        assertEquals(0, result.exitCode)
        assertTrue(Files.exists(dir.resolve("marker")), "the 500 ms raw shutdown hook was cut off before it wrote its marker")
    }

    @Test
    fun stopCalledFromARegistryHookReturnsAndKeepsTheFirstCode() {
        val result = runChild("reentrant-stop")

        assertEquals(0, result.exitCode)
        assertEquals(listOf("inner"), result.ran)
    }

    @Test
    fun stopCalledWhileTheJvmIsAlreadyExitingDoesNotHang() {
        val result = runChild("stop-in-jvm-hook")

        assertEquals(3, result.exitCode)
        assertEquals(listOf("a"), result.ran)
    }

    @Test
    fun fallbackHookWaitsForARegistryThatAnotherThreadIsStillRunning() {
        val result = runChild("exit-during-registry")

        assertEquals(3, result.exitCode)
        assertTrue(Files.exists(dir.resolve("marker")), "the slow registry hook was cut off by a concurrent System.exit")
    }

    @Test
    fun registryHookCallingSystemExitDoesNotHangTheFallbackHook() {
        val result = runChild("registry-hook-exits")

        assertEquals(4, result.exitCode)
        assertTrue("did not finish" in result.output, "expected the registry-timeout error; output:\n${result.output}")
    }

    @Test
    fun registryHookCallingSystemExitDoesNotHangWhenTheFallbackHookRunsTheRegistry() {
        val result = runChild("fallback-first-exits")

        assertEquals(3, result.exitCode)
        assertTrue("did not finish" in result.output, "expected the registry-timeout error; output:\n${result.output}")
    }

    @Test
    fun configuredHookTimeoutIsWhatTheFallbackHookWaits() {
        Files.createDirectories(dir.resolve("root/config"))
        Files.writeString(dir.resolve("root/config/shoparchive.yml"), "config-version: 1\nshutdown:\n  hook-timeout-ms: 1234\n")

        val result = runChild("configured-wait")

        assertEquals(4, result.exitCode)
        assertTrue("did not finish within 1234 ms" in result.output, "expected the configured wait in the error; output:\n${result.output}")
    }

    @Test
    fun applyConfigSetsTheRegistryWaitFromTheConfig() {
        val root = dir.resolve("applied-root")
        Files.createDirectories(root.resolve("config"))
        Files.writeString(root.resolve("config/shoparchive.yml"), "config-version: 1\nshutdown:\n  hook-timeout-ms: 4321\n")
        val config = ConfigService(root)
        config.load()
        val before = Shutdown.registryWaitMs
        try {
            applyConfig(config)

            assertEquals(4321L, Shutdown.registryWaitMs)
        } finally {
            Shutdown.registryWaitMs = before
        }
    }

    @Test
    fun fallbackHookRunsTheRegistryInReverseOrderWhenMainCallsExit() {
        val result = runChild("fallback-first-normal")

        assertEquals(5, result.exitCode)
        assertEquals(listOf("b", "a"), result.ran)
        assertTrue("did not finish" !in result.output, "unexpected registry-timeout error; output:\n${result.output}")
    }

    @Test
    fun fallbackHookRunsTheRegistryWhenMainDies() {
        val result = runChild("fallback-main-throws")

        assertEquals(1, result.exitCode) // the JVM's own code for an uncaught exception in main; the fallback never exits
        assertEquals(listOf("a"), result.ran)
    }

    // --- the log: closed last, terminal-only channel, crash report ---

    @Test
    fun theLogIsClosedAfterTheHooksAndASecretOnlyReachesTheConsole() {
        val result = runChild("logging-stop")

        assertEquals(0, result.exitCode)
        assertTrue("pairing-code-4711" in result.output, "the secret never reached the console; output:\n${result.output}")
        val latest = Files.readString(dir.resolve("log-root/logs/latest.log"))
        assertTrue("line-from-main" in latest && "line-from-hook" in latest, latest)
        assertTrue("pairing-code-4711" !in latest, latest)
    }

    @Test
    fun aCommandReplyReachesTheConsoleEvenWhenTheLogLevelHidesInfo() {
        val result = runChild("output-at-warn")

        assertEquals(0, result.exitCode)
        assertTrue("command-reply-4711" in result.output, "the reply never reached the console; output:\n${result.output}")
        assertTrue("hidden-info-line" !in result.output, result.output)
        val latest = Files.readString(dir.resolve("log-root/logs/latest.log"))
        assertTrue("command-reply-4711" !in latest && "hidden-info-line" !in latest, latest)
    }

    @Test
    fun anUncaughtExceptionWritesACrashReportAndMakesStopExitOne() {
        val result = runChild("uncaught-exception")

        assertEquals(1, result.exitCode)
        val reports = Files.list(dir.resolve("log-root/crash-reports")).use { it.toList() }
        val report = Files.readString(reports.single())
        assertTrue(Regex("\\d{8}-\\d{6}\\.txt").matches(reports.single().fileName.toString()), reports.toString())
        assertTrue("Thread: worker-7" in report && "java.lang.IllegalStateException: kaboom" in report, report)
        assertTrue("Version: " in report && "Java " in report && " on " in report, report)
        assertTrue("Crash report written to ${reports.single()}" in result.output, "the path was not logged; output:\n${result.output}")
    }
}
