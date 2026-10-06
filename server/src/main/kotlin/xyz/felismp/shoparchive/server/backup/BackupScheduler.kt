package xyz.felismp.shoparchive.server.backup

import xyz.felismp.shoparchive.server.config.BackupSettings
import xyz.felismp.shoparchive.server.config.ConfigLog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Starts [service] once a day at `backup.time` (in the server time zone) while the server runs. [tick] is what a timer calls
 * every [POLL_SECONDS]; it reads the settings each time, so a `reload` that changes the time or turns it off applies at once.
 * A start with no backup in the last 24 hours also makes one, [catchUpDelaySeconds] after the start. A daily run that fails is tried
 * again after [RETRY_MINUTES] (a full disk or a locked file may have cleared), until it works or the day ends.
 * Only one backup runs at a time: the service refuses a second, so a console `backup` and a daily run never overlap.
 */
internal class BackupScheduler(
    private val service: BackupService,
    private val settings: () -> BackupSettings,
    private val zone: () -> ZoneId,
    private val clock: Clock,
    private val log: ConfigLog,
    private val catchUpDelaySeconds: Long = 60,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "backup-scheduler").apply { isDaemon = true } },
) {
    private var startedAt: Instant = clock.instant()
    private var catchUpPending = false

    /** The day a daily run was started (or is not due any more): at most one daily run per day. */
    private var handledDay: LocalDate? = null
    private var retryAt: Instant? = null

    /** Reads what is on disk, then starts the timer. */
    fun start() {
        val now = clock.instant()
        startedAt = now
        val current = settings()
        if (current.enabled) {
            val last = service.lastSuccess()
            catchUpPending = last == null || Duration.between(last, now) > Duration.ofHours(24)
            // A start after today's time is not a missed run: the catch-up above covers a backup that is overdue.
            val local = now.atZone(zone())
            if (!local.toLocalTime().isBefore(current.time)) handledDay = local.toLocalDate()
        }
        executor.scheduleWithFixedDelay({ tickSafely() }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
    }

    /** Stops the timer and waits for a running backup to finish (what is half written is a `.part` that the next backup removes). */
    fun stop(waitSeconds: Long = 10) {
        executor.shutdown()
        if (!executor.awaitTermination(waitSeconds, TimeUnit.SECONDS)) executor.shutdownNow()
    }

    private fun tickSafely() {
        try {
            tick()
        } catch (e: Throwable) {
            log.error("Backup scheduler: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** One look at the clock: starts a backup if one is due. */
    fun tick() {
        val current = settings()
        if (!current.enabled) return
        val now = clock.instant()
        val local = now.atZone(zone())
        val due = when {
            catchUpPending && !now.isBefore(startedAt.plusSeconds(catchUpDelaySeconds)) -> {
                catchUpPending = false
                // Another backup may have been made since the start (the console's, or the daily run just before this).
                val last = service.lastSuccess()
                if (last != null && Duration.between(last, now) <= Duration.ofHours(24)) return
                "catch-up"
            }
            retryAt?.let { !now.isBefore(it) } == true -> "retry"
            handledDay != local.toLocalDate() && !local.toLocalTime().isBefore(current.time) -> {
                handledDay = local.toLocalDate()
                "daily"
            }
            else -> return
        }
        retryAt = null
        log.info("Starting the $due backup")
        when (service.run()) {
            null -> log.info("The $due backup was skipped: another backup is running")
            is BackupOutcome.Failed -> retryAt = now.plus(Duration.ofMinutes(RETRY_MINUTES))
            // Any good backup (this one, or one the admin made at the console meanwhile) settles the catch-up.
            is BackupOutcome.Ok -> catchUpPending = false
        }
    }

    companion object {
        const val POLL_SECONDS = 30L
        const val RETRY_MINUTES = 30L
    }
}
