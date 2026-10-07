package xyz.felismp.shoparchive.server

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import xyz.felismp.shoparchive.server.config.ConfigService

/**
 * Test-only entry point run in a child JVM by ShutdownTest (exit codes and JVM shutdown hooks can't be
 * observed in-process). args[0] = scenario, args[1] = a directory the scenario may write marker files to.
 * Registry hooks report themselves as `ran:<name>` lines on stdout.
 */
object ShutdownChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[1])
        fun hook(name: String) = Shutdown.register(name) { println("ran:$name") }

        when (args[0]) {
            "normal" -> {
                hook("a"); hook("b"); hook("c")
                Shutdown.stop(0)
            }
            "throwing" -> {
                hook("first")
                Shutdown.register("boom") { throw RuntimeException("boom") }
                hook("last")
                Shutdown.stop(0)
            }
            "slow-raw-hook" -> {
                hook("a")
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        Thread.sleep(500)
                        Files.writeString(dir.resolve("marker"), "done")
                    }
                )
                Shutdown.stop(0)
            }
            "fail-then-stop" -> {
                hook("a")
                Shutdown.fail()
                Shutdown.stop(0)
            }
            "stop-code" -> {
                hook("a")
                Shutdown.stop(7)
            }
            // A registry hook calling stop() again must return at once, not deadlock or change the code.
            "reentrant-stop" -> {
                Shutdown.register("inner") { println("ran:inner"); Shutdown.stop(5) }
                Shutdown.stop(0)
            }
            // stop() called from a raw JVM shutdown hook (JVM already exiting) must not hang on exitProcess.
            "stop-in-jvm-hook" -> {
                hook("a")
                Runtime.getRuntime().addShutdownHook(Thread { Shutdown.stop(0) })
                exitProcess(3)
            }
            // main dying with the fallback hook installed: registry still runs, the JVM keeps its own exit code.
            "fallback-main-throws" -> {
                hook("a")
                Shutdown.install()
                throw RuntimeException("main died")
            }
            // Registry still running on main (a ~1 s hook) when another thread starts JVM shutdown: the fallback
            // hook must wait for it, so the marker is written before the JVM finishes. The latch makes sure
            // the exit really comes after the registry hook has started.
            "exit-during-registry" -> {
                Shutdown.install()
                val started = CountDownLatch(1)
                Shutdown.register("slow") {
                    started.countDown()
                    Thread.sleep(1000)
                    Files.writeString(dir.resolve("marker"), "done")
                }
                Thread {
                    started.await()
                    exitProcess(3)
                }.start()
                Shutdown.stop(0)
            }
            // Fallback hook runs the registry (nobody called stop): a registry hook that calls System.exit must
            // not hang the JVM - the helper thread blocks, the fallback's bounded wait ends, exit code stays 3.
            "fallback-first-exits" -> {
                Shutdown.install()
                Shutdown.registryWaitMs = 500
                Shutdown.register("exits") { exitProcess(4) }
                exitProcess(3)
            }
            // Fallback hook runs ordinary registry hooks (reverse order) and the JVM keeps main's exit code.
            "fallback-first-normal" -> {
                hook("a"); hook("b")
                Shutdown.install()
                exitProcess(5)
            }
            // A registry hook that calls System.exit blocks its thread inside exit, waiting for the fallback
            // hook; the fallback's bounded wait must end (shortened here) rather than deadlock.
            "registry-hook-exits" -> {
                Shutdown.install()
                Shutdown.registryWaitMs = 500
                Shutdown.register("exits") { exitProcess(4) }
                Shutdown.stop(0)
            }
            // The fallback hook's wait comes from `shutdown.hook-timeout-ms` in <dir>/root/config/shoparchive.yml
            // (the test writes it), not from Shutdown's built-in default.
            "configured-wait" -> {
                val config = ConfigService(dir.resolve("root"))
                config.load()
                applyConfig(config)
                Shutdown.install()
                Shutdown.register("exits") { exitProcess(4) }
                Shutdown.stop(0)
            }
            // Log started, a secret on the terminal-only channel, a hook that logs while stopping: the hook's line
            // must still reach latest.log (the log is closed last), the secret must reach stdout only.
            "logging-stop" -> {
                Log.start(dir.resolve("log-root"))
                Log.terminalOnly("secret-code-4711")
                Log.info("line-from-main")
                Shutdown.register("late") { Log.info("line-from-hook") }
                Shutdown.stop(0)
            }
            // A command reply at log-level warn: it must still reach the console, but not latest.log.
            "output-at-warn" -> {
                Log.start(dir.resolve("log-root"), level = "warn")
                Log.output("command-reply-4711")
                Log.info("hidden-info-line")
                Shutdown.stop(0)
            }
            // An uncaught exception on another thread: crash report written, process marked failed, so stop(0) exits 1.
            "uncaught-exception" -> {
                Log.start(dir.resolve("log-root"))
                CrashReport.install(dir.resolve("log-root"), ZoneId.of("UTC"))
                Thread({ throw IllegalStateException("kaboom") }, "worker-7").apply { start(); join() }
                Shutdown.stop(0)
            }
            else -> error("unknown scenario ${args[0]}")
        }
    }
}
