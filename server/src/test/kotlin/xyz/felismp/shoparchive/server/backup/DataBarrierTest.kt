package xyz.felismp.shoparchive.server.backup

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.BarrierTimeoutException
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.auth.AuditLog
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.RecordingSender
import xyz.felismp.shoparchive.server.records.login
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DataBarrierTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun aChangeInProgressMakesFreezeWaitAndAFreezeMakesAChangeWait() {
        val barrier = DataBarrier()
        val inChange = CountDownLatch(1)
        val endChange = CountDownLatch(1)
        val change = thread { barrier.mutate { inChange.countDown(); endChange.await(10, TimeUnit.SECONDS) } }
        assertTrue(inChange.await(10, TimeUnit.SECONDS))

        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freeze = thread { barrier.freeze(10_000) { frozen.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertFalse(frozen.await(200, TimeUnit.MILLISECONDS), "froze while a change was running")
        // A change that starts while the freeze is waiting is not held up by it (so it cannot block a thread another change needs).
        barrier.mutate { }

        endChange.countDown()
        assertTrue(frozen.await(10, TimeUnit.SECONDS))
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val late = thread { started.countDown(); barrier.mutate { done.countDown() } }
        started.await()
        assertFalse(done.await(200, TimeUnit.MILLISECONDS), "a change ran while frozen")
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        listOf(change, freeze, late).forEach { it.join(10_000) }
    }

    @Test
    fun aChangeThatNeverEndsMakesFreezeGiveUp() {
        val barrier = DataBarrier()
        val inChange = CountDownLatch(1)
        val end = CountDownLatch(1)
        val change = thread { barrier.mutate { inChange.countDown(); end.await(10, TimeUnit.SECONDS) } }
        inChange.await()

        assertFailsWith<BarrierTimeoutException> { barrier.freeze(100) { } }

        end.countDown()
        change.join(10_000)
        barrier.freeze(100) { }
    }

    @Test
    fun aChangeInsideAChangeDoesNotWaitForAFreezeThatIsWaiting() {
        val barrier = DataBarrier()
        val outer = CountDownLatch(1)
        val go = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val change = thread {
            barrier.mutate {
                outer.countDown()
                go.await(10, TimeUnit.SECONDS)
                barrier.mutate { finished.countDown() }
            }
        }
        outer.await()
        val freeze = thread { runCatching { barrier.freeze(2_000) { } } }
        Thread.sleep(100)
        go.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        change.join(10_000)
        freeze.join(10_000)
    }

    @Test
    fun resettingAnAccountIsHeldOffWhileABackupBuildsItsZipSoItIsNeverHalfInIt() {
        val env = AuthEnv(dir.resolve("auth"))
        env.login("alice")
        val userFile = env.root.resolve("user/alice.yml")
        val before = Files.readString(userFile)
        val deviceFiles = Files.list(env.root.resolve("data/devices")).use { it.toList() }
        assertEquals(1, deviceFiles.size)
        val deviceBefore = Files.readString(deviceFiles.single())

        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freeze = thread { env.barrier.freeze(10_000) { frozen.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue(frozen.await(10, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        val reset = thread { env.auth.accounts.reset(RecordingSender(), "alice"); done.countDown() }

        assertFalse(done.await(300, TimeUnit.MILLISECONDS), "the reset ran while frozen")
        // What a backup sees: neither file has changed.
        assertEquals(before, Files.readString(userFile))
        assertEquals(deviceBefore, Files.readString(deviceFiles.single()))

        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        freeze.join(10_000)
        reset.join(10_000)
        assertTrue(Files.readString(userFile) != before)
    }

    @Test
    fun theAuditLogAndTheUserAndDeviceFilesAreHeldOffWhileFrozen() {
        val env = AuthEnv(dir.resolve("auth"))
        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freeze = thread { env.barrier.freeze(10_000) { frozen.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue(frozen.await(10, TimeUnit.SECONDS))
        val done = CountDownLatch(3)
        thread { env.auth.audit.record("test", null, null, "1.1.1.1", "ok"); done.countDown() }
        thread { env.users.addUser("bob", "none", listOf("main")); done.countDown() }
        thread { env.auth.devices.create("d", "p", xyz.felismp.shoparchive.shared.DeviceMode.SHARED, "bob", "id", "cred"); done.countDown() }

        assertFalse(done.await(300, TimeUnit.MILLISECONDS))
        assertFalse(Files.exists(env.root.resolve("user/bob.yml")))
        // Reading is never held, not even by the lock a change would be holding: users and devices are read while frozen.
        val read = CountDownLatch(1)
        thread { env.users.userNames(); env.users.find("bob"); env.auth.devices.get("x"); env.auth.devices.count(); read.countDown() }
        assertTrue(read.await(5, TimeUnit.SECONDS), "a read waited for a backup")

        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        freeze.join(10_000)
        assertTrue(Files.exists(env.root.resolve("user/bob.yml")))
    }

    @Test
    fun aBackupGivesUpWhenAChangeNeverEndsAndLeavesNoZipAndTheWriterFree() {
        val env = BackupEnv(dir.resolve("root"), "  pause-timeout-seconds: 1\n").also { it.populate() }
        val inChange = CountDownLatch(1)
        val end = CountDownLatch(1)
        val change = thread { env.barrier.mutate { inChange.countDown(); end.await(20, TimeUnit.SECONDS) } }
        inChange.await()

        val failed = assertIs<BackupOutcome.Failed>(env.service.run())

        assertTrue("did not finish" in failed.reason && "nothing was backed up" in failed.reason, failed.reason)
        assertEquals(emptyList(), env.root.zips())
        assertEquals(emptyList(), env.root.parts())
        assertEquals(42, env.writer.run { 42 })
        end.countDown()
        change.join(10_000)
        assertIs<BackupOutcome.Ok>(env.service.run())
    }

    @Test
    fun backupsAndChangesFromTheRecordWriterAndFromOtherThreadsNeverDeadlock() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val audit = AuditLog(env.root, { ZoneOffset.UTC }, env.clock, env.barrier)
        Files.createDirectories(env.root.resolve("data/audit"))
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val workers = listOf(
            thread { while (!stop.get()) { env.writer.run { audit.record("writer", null, null, "1.1.1.1", "ok") }; Thread.sleep(2) } },
            thread { while (!stop.get()) { audit.record("request", null, null, "1.1.1.1", "ok"); Thread.sleep(2) } },
            // A change that holds the barrier for a moment, nested, as a multi-file change does.
            thread { while (!stop.get()) { env.barrier.mutate { env.barrier.mutate { Thread.sleep(1) } }; Thread.sleep(2) } },
        )
        val results = (1..5).map { env.service.run() }

        stop.set(true)
        workers.forEach { it.join(20_000) }
        assertTrue(workers.none { it.isAlive }, "a thread is stuck")
        assertTrue(results.all { it != null })
    }

    @Test
    fun auditLinesWrittenWhileBackingUpAreWholeInTheZip() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val audit = AuditLog(env.root, { ZoneOffset.UTC }, env.clock, env.barrier)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = thread { while (!stop.get()) { audit.record("request", "alice", null, "1.1.1.1", "ok"); Thread.sleep(1) } }
        Thread.sleep(50)

        val ok = assertIs<BackupOutcome.Ok>(env.service.run())

        stop.set(true)
        worker.join(10_000)
        val text = env.root.resolve("backups/${ok.zip}").zipText("data/audit/2026/10/02.log")
        assertTrue(text.isNotEmpty() && text.endsWith("\n") && text.lines().dropLast(1).all { it.endsWith("result=ok") }, "a line is cut off")
    }
}
