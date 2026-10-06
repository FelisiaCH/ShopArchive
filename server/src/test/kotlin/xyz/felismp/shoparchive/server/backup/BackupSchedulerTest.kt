package xyz.felismp.shoparchive.server.backup

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.config.write
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupSchedulerTest {
    @TempDir
    lateinit var dir: Path

    private class Sender : CommandSender {
        val lines = mutableListOf<String>()
        override val name = "test"
        override fun sendMessage(message: String) { lines += message }
    }

    private fun env(clock: TestClock, yml: String? = null) = BackupEnv(dir.resolve("root"), yml, clock).also { it.populate() }

    private fun scheduler(env: BackupEnv, clock: TestClock, pause: ((() -> Unit) -> Unit)? = null): BackupScheduler {
        val service = if (pause == null) env.service else BackupService(env.root, { env.config.backup }, { ZoneOffset.UTC }, pause, env.log, clock, serverId = SERVER_ID)
        return BackupScheduler(service, { env.config.backup }, { ZoneOffset.UTC }, clock, env.log, executor = Executors.newSingleThreadScheduledExecutor()).also { it.start() }
    }

    @Test
    fun theDailyBackupRunsOnceADayAtTheConfiguredTime() {
        val clock = TestClock(Instant.parse("2026-10-02T01:00:00Z"))
        val env = env(clock, "  time: \"03:00\"\n")
        env.service.run() // a recent backup: no catch-up
        val before = env.root.zips().size
        val scheduler = scheduler(env, clock)

        scheduler.tick()
        clock.advance(3600) // 02:00
        scheduler.tick()
        assertEquals(before, env.root.zips().size, "ran before its time")

        clock.advance(3600) // 03:00
        scheduler.tick()
        assertEquals(before + 1, env.root.zips().size)
        clock.advance(1800)
        scheduler.tick()
        clock.advance(3600 * 10)
        scheduler.tick()
        assertEquals(before + 1, env.root.zips().size, "ran twice in a day")

        clock.advance(3600 * 24) // next day, later than 03:00
        scheduler.tick()
        assertEquals(before + 2, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun aStartAfterTodaysTimeWithARecentBackupDoesNotRunAtOnce() {
        val clock = TestClock(Instant.parse("2026-10-02T10:00:00Z"))
        val env = env(clock)
        env.service.run()
        val scheduler = scheduler(env, clock)

        scheduler.tick()
        clock.advance(3600)
        scheduler.tick()

        assertEquals(1, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun aStartWhenTheLastBackupIsOlderThan24HoursMakesOneShortlyAfterAndOnlyOne() {
        val clock = TestClock(Instant.parse("2026-10-02T10:00:00Z"))
        val env = env(clock)
        env.put("backups/20261001-030000.zip", "old") // 31 hours ago
        val scheduler = scheduler(env, clock)

        scheduler.tick()
        assertEquals(1, env.root.zips().size, "ran before the delay")
        clock.advance(61)
        scheduler.tick()
        assertEquals(2, env.root.zips().size)
        clock.advance(3600)
        scheduler.tick()
        assertEquals(2, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun aDailyRunRightAfterAStartWithNoBackupMeansNoCatchUpToo() {
        val clock = TestClock(Instant.parse("2026-10-02T02:59:30Z"))
        val env = env(clock)
        val scheduler = scheduler(env, clock)

        clock.advance(40) // 03:00:10: the daily run
        scheduler.tick()
        assertEquals(1, env.root.zips().size)
        clock.advance(30) // the catch-up delay is over
        scheduler.tick()

        assertEquals(1, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun aConsoleBackupBeforeTheCatchUpSettlesIt() {
        val clock = TestClock(Instant.parse("2026-10-02T10:00:00Z"))
        val env = env(clock)
        val scheduler = scheduler(env, clock)
        env.service.run()

        clock.advance(61)
        scheduler.tick()

        assertEquals(1, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun aStartWithNoBackupAtAllMakesOne() {
        val clock = TestClock(Instant.parse("2026-10-02T10:00:00Z"))
        val env = env(clock)
        val scheduler = scheduler(env, clock)

        clock.advance(61)
        scheduler.tick()

        assertEquals(1, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun aBackupOfTheLast24HoursMeansNoCatchUp() {
        val clock = TestClock(Instant.parse("2026-10-02T10:00:00Z"))
        val env = env(clock)
        env.put("backups/20261001-110000.zip", "recent")
        val scheduler = scheduler(env, clock)

        clock.advance(120)
        scheduler.tick()

        assertEquals(1, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun nothingRunsWhileItIsOffAndAReloadSwitchesItOn() {
        val clock = TestClock(Instant.parse("2026-10-02T02:00:00Z"))
        val env = env(clock, "  enabled: false\n")
        val scheduler = scheduler(env, clock)

        clock.advance(86_400 * 2)
        scheduler.tick()
        assertEquals(0, env.root.zips().size)

        env.root.write("config/shoparchive.yml", "config-version: 1\n\nbackup:\n  enabled: true\n  time: \"03:00\"\n")
        env.reload()
        scheduler.tick()
        assertEquals(0, env.root.zips().size, "03:00 is not here yet")
        clock.advance(3600)
        scheduler.tick()
        assertEquals(1, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun aFailedDailyBackupIsTriedAgainAfterHalfAnHourNotBefore() {
        val clock = TestClock(Instant.parse("2026-10-02T02:59:00Z"))
        val env = env(clock)
        env.service.run()
        var fail = true
        val scheduler = scheduler(env, clock) { block -> if (fail) throw java.io.IOException("disk full") else env.writer.run(block) }

        clock.advance(120) // 03:01
        scheduler.tick()
        assertEquals(1, env.root.zips().size)
        assertTrue(env.log.errors.any { "disk full" in it })

        fail = false
        clock.advance(600)
        scheduler.tick()
        assertEquals(1, env.root.zips().size, "retried too soon")
        clock.advance(1300)
        scheduler.tick()
        assertEquals(2, env.root.zips().size)
        clock.advance(3600 * 5)
        scheduler.tick()
        assertEquals(2, env.root.zips().size)
        scheduler.stop()
    }

    @Test
    fun theConsoleCommandRunsABackupAndReportsIt() {
        val env = env(TestClock(Instant.parse("2026-10-02T10:00:00Z")))
        val commands = Commands()
        registerBackupCommand(commands, env.service, env.root)
        val sender = Sender()

        commands.run(sender, "backup")

        assertEquals(1, env.root.zips().size)
        assertTrue(sender.lines.any { it.startsWith("Backup done: backups/20261002-100000.zip") && "2 new slips" in it }, sender.lines.toString())
    }

    @Test
    fun theConsoleCommandSaysSoWhenABackupIsAlreadyRunning() {
        val env = env(TestClock(Instant.parse("2026-10-02T10:00:00Z")))
        val commands = Commands()
        val inside = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val service = BackupService(env.root, { env.config.backup }, { ZoneOffset.UTC }, { block -> inside.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS); block() }, env.log, env.clock, serverId = SERVER_ID)
        registerBackupCommand(commands, service, env.root)
        val first = Thread { commands.run(Sender(), "backup") }.apply { start() }
        assertTrue(inside.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val sender = Sender()

        commands.run(sender, "backup")

        assertTrue("A backup is already running." in sender.lines, sender.lines.toString())
        release.countDown()
        first.join(10_000)
        assertEquals(1, env.root.zips().size)
    }

    @Test
    fun theConsoleRestoresSlipsAndRefusesBadUse() {
        val old = BackupEnv(dir.resolve("old")).also { it.populate() }
        val ok = old.service.run() as BackupOutcome.Ok
        val new = dir.resolve("new").also { Files.createDirectories(it) }
        Files.copy(old.root.resolve("backups/${ok.zip}"), dir.resolve("b.zip"))
        java.util.zip.ZipFile(dir.resolve("b.zip").toFile()).use { zip -> Files.write(new.resolve(MANIFEST_NAME), zip.getInputStream(zip.getEntry(MANIFEST_NAME)).readBytes()) }
        val restorer = BackupEnv(new)
        val commands = Commands()
        registerBackupCommand(commands, restorer.service, new)
        val sender = Sender()

        commands.run(sender, "backup restore-slips ${old.root.resolve("backups/slips")}")
        commands.run(sender, "backup nonsense")

        assertTrue("Slips: 2 put back, 0 were there already, 0 problems" in sender.lines, sender.lines.toString())
        assertTrue(Files.exists(new.resolve("record/2026/10/02/e1/slip-1.jpg")))
        assertTrue(sender.lines.last().startsWith("Usage: backup"))
    }
}
