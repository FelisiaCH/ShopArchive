package xyz.felismp.shoparchive.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import sun.misc.Signal

/**
 * Ordered shutdown hook registry. Later phases register the record writer, network connections, etc.
 * from their own code as boot proceeds; hooks run in *reverse* registration order (last registered
 * stops first), so a connection registered late in boot shuts down before the writers registered
 * earlier that it depends on.
 *
 * [stop] is the one way to stop the server: it runs the registry, then calls exitProcess, so every other
 * JVM shutdown hook (libraries, plugins) runs to completion afterwards - nothing here ever halts the JVM.
 */
object Shutdown {
    /** How long the fallback JVM hook waits for the registry to finish, until boot replaces it with `shutdown.hook-timeout-ms`. */
    private const val REGISTRY_WAIT_MS = 30_000L

    private val hooks = mutableListOf<Pair<String, () -> Unit>>()

    @Volatile private var failed = false

    /** Set by whoever first runs the registry ([stop] or the fallback hook); later callers do nothing. */
    private val registryStarted = AtomicBoolean(false)

    /** Counted down when the registry hooks have all run, so the fallback hook can wait for the registry whoever is running it. */
    private val registryDone = CountDownLatch(1)

    /** Set from the config at boot (see applyConfig); tests set it directly to shorten the fallback hook's wait. */
    internal var registryWaitMs = REGISTRY_WAIT_MS

    /** Marks the process as failed, so [stop] exits with 1 instead of the requested code. */
    fun fail() {
        failed = true
    }

    fun register(name: String, block: () -> Unit) {
        synchronized(hooks) { hooks.add(name to block) }
    }

    /**
     * Runs the registry on the calling thread, then exits with 1 if anything failed (a registry hook threw,
     * or [fail] was called), else [code]. Idempotent: the first caller wins, a later or concurrent call
     * returns immediately (never waits - a hook calling [stop] would wait on itself). Must never block inside a JVM shutdown hook - exitProcess there would wait on
     * the very shutdown it belongs to - so if the JVM is already shutting down, only the registry runs.
     */
    fun stop(code: Int) {
        if (!runRegistryOnce()) return
        if (jvmIsShuttingDown()) return
        exitProcess(if (failed) 1 else code)
    }

    /**
     * Must only be called after startup has fully succeeded. SIGTERM and SIGINT call stop(0), which is what
     * gives exit 0 instead of the JVM's default 143/130 for a normal stop (the phase plan requires 0).
     * The plain shutdown hook is only a fallback that makes sure the registry has run (main died from
     * an uncaught exception, or other code called exit); it never exits or halts, so the exit code is
     * whatever the JVM already chose. It never runs registry hooks on the JVM shutdown-hook thread itself
     * (a registry hook calling System.exit there would block forever): it hands the registry to a helper
     * thread - which does nothing if [stop] already started it - and waits for it, bounded. So the JVM does
     * not finish while cleanup is in progress, and a registry hook that calls System.exit only blocks the
     * helper: the wait times out, one error is logged and JVM shutdown continues.
     */
    fun install() {
        try {
            for (name in listOf("TERM", "INT")) Signal.handle(Signal(name)) { stop(0) }
        } catch (e: Exception) {
            // e.g. the JVM was started with -Xrs, which reserves the signals
            Log.warn("cannot handle SIGTERM/SIGINT (${e.message}); a signal stop will exit non-zero")
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            // Daemon: if a registry hook blocks inside System.exit, this helper must never keep the JVM alive.
            Thread({ runRegistryOnce() }, "shutdown-registry").apply { isDaemon = true }.start()
            awaitRegistry()
        })
    }

    /** Visible for tests: whether [stop] would exit 1, without stopping anything. */
    internal fun hasFailed(): Boolean = failed

    /** Visible for tests: runs hooks (reverse order), never exits the JVM. */
    internal fun runHooks() {
        // Snapshot-and-clear: hooks are one-shot (a JVM shuts down exactly once), and clearing keeps
        // repeated calls - as tests make - from re-running hooks left over from an earlier call.
        val snapshot = synchronized(hooks) {
            val copy = hooks.toList()
            hooks.clear()
            copy
        }
        for ((name, block) in snapshot.asReversed()) {
            try {
                block()
            } catch (e: Throwable) {
                Log.error("shutdown hook '$name' failed: ${e.message}")
                failed = true
            }
        }
    }

    private fun runRegistryOnce(): Boolean {
        if (!registryStarted.compareAndSet(false, true)) return false
        try {
            runHooks()
        } finally {
            Log.close() // last: the hooks above log their goodbyes first
            registryDone.countDown() // before stop() goes on to exitProcess
        }
        return true
    }

    private fun awaitRegistry() {
        if (!registryDone.await(registryWaitMs, TimeUnit.MILLISECONDS)) {
            Log.error("shutdown registry did not finish within $registryWaitMs ms; continuing JVM shutdown")
        }
    }

    /** addShutdownHook throws IllegalStateException once shutdown has begun; there is no direct query. */
    private fun jvmIsShuttingDown(): Boolean {
        val probe = Thread {}
        return try {
            Runtime.getRuntime().addShutdownHook(probe)
            Runtime.getRuntime().removeShutdownHook(probe)
            false
        } catch (e: IllegalStateException) {
            true
        }
    }
}
