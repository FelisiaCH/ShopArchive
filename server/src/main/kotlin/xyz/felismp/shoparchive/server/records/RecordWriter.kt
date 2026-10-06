package xyz.felismp.shoparchive.server.records

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The one thread that writes records and sessions. Whatever must be true while a write happens (a day is open, an id is new,
 * a session is not closed yet) is checked inside [run] too, so two requests can never both pass the check and then both write.
 * Calling [run] from inside [run] just runs the block, so a service can wrap several store calls in one.
 */
internal class RecordWriter {
    private val thread = Executors.newSingleThreadExecutor { task -> Thread(task, "record-writer").apply { isDaemon = true } }

    @Volatile
    private var writerThread: Thread? = null

    fun <T> run(block: () -> T): T {
        if (Thread.currentThread() === writerThread) return block()
        val future = thread.submit<T> {
            writerThread = Thread.currentThread()
            block()
        }
        try {
            return future.get()
        } catch (e: ExecutionException) {
            // The caller sees the exception the block threw, as if it had run the block itself.
            throw e.cause ?: e
        }
    }

    /** Lets the writes already queued finish, then stops. A [run] after this is refused. */
    fun close(waitSeconds: Long = 30) {
        thread.shutdown()
        thread.awaitTermination(waitSeconds, TimeUnit.SECONDS)
    }
}
