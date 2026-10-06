package xyz.felismp.shoparchive.server

import java.util.concurrent.locks.ReentrantReadWriteLock

/** The backup could not get every business-data writer to stand still in time; nothing was written. */
internal class BarrierTimeoutException(message: String) : Exception(message)

/**
 * Lets a backup see the files of the root that are not written by the record writer (users, roles, devices, audit, branches,
 * categories, banned IPs) at one instant. Whoever changes such files holds the read side for the whole change ([mutate]): a change
 * that touches several files (reset an account: device files, then the user file) is then never half in a backup. A backup holds the
 * write side ([freeze]) only while it builds the zip. Reading files never takes this lock, so reads never wait.
 *
 * What counts as a change: anything that can write a business file, and that includes loading. [loadConfigFile] creates a missing file,
 * saves a migration copy and rewrites a file that is not in template form, so `load`/`reload` of a store, and a re-read because a file changed
 * by hand, run inside [mutate] for their whole duration. A pure read of what is already in memory never takes this lock; a read that must look at
 * the file (a freshness check) uses [xyz.felismp.shoparchive.server.config.peekConfigFile], which writes nothing, and leaves the normalizing to the
 * next change. A store takes the barrier before its own monitor (public `x` calls `xInLock`), never the other way round.
 *
 * Lock order: the record writer first, then this barrier. A backup runs on the record writer's thread and takes [freeze] inside it,
 * so code that holds [mutate] must never call into the record writer and wait for it (it would wait for the backup, which waits for it);
 * the other way round - a job on the record writer that calls [mutate], as an audit line does - is fine.
 *
 * [freeze] does not queue for the lock (`tryLock` barges): a queued writer would make a new [mutate] wait while its thread may hold
 * a store's monitor that another [mutate] holder needs. It retries every millisecond until it finds no change in progress, or gives up after
 * its timeout. The price: a change that never stops for a moment can keep it waiting; changes are short and arrive in bursts, so a gap comes at once.
 */
internal class DataBarrier {
    private val lock = ReentrantReadWriteLock()

    /** Whether the calling thread is inside [mutate]. */
    val inMutate: Boolean get() = lock.readHoldCount > 0

    /** Runs [block] as one change of business data. Waits only while a backup is building its zip. Re-entrant. */
    fun <T> mutate(block: () -> T): T {
        lock.readLock().lock()
        try {
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    /** Runs [block] with no [mutate] running or starting. @throws BarrierTimeoutException a change was still running after [timeoutMs] */
    fun <T> freeze(timeoutMs: Long, block: () -> T): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!lock.writeLock().tryLock()) {
            if (System.nanoTime() > deadline) throw BarrierTimeoutException("a change of users, devices or other data did not finish within ${timeoutMs / 1000} s")
            Thread.sleep(1)
        }
        try {
            return block()
        } finally {
            lock.writeLock().unlock()
        }
    }
}
