package xyz.felismp.shoparchive.server.notify

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The outbox is business data: a backup building its zip sees no message half written, and reading the outbox never waits for it. */
class OutboxBarrierTest {
    @TempDir
    lateinit var root: Path

    private fun messages() = Files.list(root.resolve("data/outbox")).use { l -> l.filter { it.fileName.toString().endsWith(".yml") }.toList() }

    @Test
    fun aNewMessageWaitsWhileFrozenAndReadingTheOutboxDoesNot() {
        val env = AuthEnv(root, withNotify = true)
        val outbox = env.notify!!.outbox
        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freeze = thread { env.barrier.freeze(10_000) { frozen.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue(frozen.await(10, TimeUnit.SECONDS))

        val queued = CountDownLatch(1)
        val add = thread { outbox.enqueue(Draft("20261003-AAAAA", "entry.created", "2026-10-03T15:00:00+07:00", "main", "noy", "lo", emptyMap())); queued.countDown() }

        assertFalse(queued.await(300, TimeUnit.MILLISECONDS), "a message was queued while frozen")
        assertEquals(0, messages().size)
        val read = CountDownLatch(1)
        thread { outbox.items(); read.countDown() }
        assertTrue(read.await(5, TimeUnit.SECONDS), "reading the outbox waited for the backup")

        release.countDown()
        assertTrue(queued.await(10, TimeUnit.SECONDS))
        freeze.join(10_000)
        add.join(10_000)
        assertEquals(1, messages().size)
    }
}
