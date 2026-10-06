package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.records.stamp
import xyz.felismp.shoparchive.server.writeAtomically
import xyz.felismp.shoparchive.shared.NotificationState
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A message that is made but has no id yet; the outbox gives it one when it is put in. */
internal class Draft(
    val code: String,
    val event: String,
    val createdAt: String,
    val branch: String,
    val user: String,
    val locale: String,
    val fields: Map<String, String>,
    val resendOf: String? = null,
    /** The id of the entry or the day session the message is about, so the outbox can tell what has a message already. */
    val subject: String? = null,
)

/** What came of one call of a notifier. */
internal sealed interface Attempt {
    class Answer(val result: DeliveryResult) : Attempt

    /** No answer within the time allowed: the message may or may not have arrived. */
    data object TimedOut : Attempt

    class Threw(val error: Throwable) : Attempt

    /** The outbox is stopping and gave up waiting . Also unknown. */
    data object Stopped : Attempt
}

/** Calls a notifier, waits at most [timeoutSeconds] for it. A seam for tests: they answer without threads or waiting. */
internal interface Deliverer {
    fun deliver(notifier: Notifier, notification: Notification, timeoutSeconds: Int): Attempt

    /** Ends the wait of a call in progress (it comes back as [Attempt.Stopped]) and makes later calls do the same. */
    fun stop() {}
}

/**
 * Calls the notifier on a thread of its own and waits for it, so a channel that hangs cannot hold the outbox: after the time is up the
 * call is interrupted and the outbox goes on (a thread that ignores the interrupt is left to finish by itself; the next message gets another thread).
 * The outbox's own thread is never interrupted, so it cannot be caught in the middle of writing a file: [stop] ends the wait from outside.
 */
internal class ThreadedDeliverer(
    private val executor: ExecutorService = newDeliveryExecutor(),
    /** The unit of the timeout; seconds, except in tests that must not wait that long. */
    private val unit: TimeUnit = TimeUnit.SECONDS,
) : Deliverer {
    @Volatile
    private var current: Future<*>? = null

    @Volatile
    private var stopped = false

    override fun deliver(notifier: Notifier, notification: Notification, timeoutSeconds: Int): Attempt {
        if (stopped) return Attempt.Stopped
        val future = executor.submit<DeliveryResult> { notifier.deliver(notification) }
        current = future
        return try {
            Attempt.Answer(future.get(timeoutSeconds.toLong(), unit))
        } catch (e: TimeoutException) {
            future.cancel(true)
            Attempt.TimedOut
        } catch (e: CancellationException) {
            Attempt.Stopped
        } catch (e: ExecutionException) {
            Attempt.Threw(e.cause ?: e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Attempt.Stopped
        } finally {
            current = null
        }
    }

    override fun stop() {
        stopped = true
        current?.cancel(true)
        executor.shutdownNow()
    }

    companion object {
        fun newDeliveryExecutor(): ExecutorService = Executors.newCachedThreadPool { task -> Thread(task, "notify-delivery").apply { isDaemon = true } }
    }
}

/** Whether [error], or what caused it, says the channel did not answer in time (as opposed to answering with an error). */
internal fun isTimeout(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is TimeoutException || it is InterruptedIOException || it.javaClass.simpleName.contains("Timeout") }

/**
 * `data/outbox/<id>.yml`, one file per message, in memory too. Files are written whole (temporary file, fsync, rename), never deleted:
 * a sent message stays as the record of what was sent. A file that cannot be read is logged and left alone.
 */
/**
 * The messages in `data/outbox/`. Every write is a change of business data, so it runs in [barrier] (taken before this store's lock, so a write
 * waiting for a backup never holds up the reads, which take no lock).
 */
internal class OutboxStore(root: Path, private val config: ConfigService, private val clock: Clock, private val barrier: DataBarrier = DataBarrier()) {
    private val dir: Path = root.resolve("data/outbox")
    private val items = ConcurrentHashMap<String, OutboxItem>()
    private val idTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", java.util.Locale.ROOT)

    fun load() {
        Files.createDirectories(dir)
        loadSince()
        items.clear()
        val names = Files.newDirectoryStream(dir, "*.yml").use { stream -> stream.map { it.fileName.toString() }.sorted() }
        for (name in names) {
            try {
                val item = parseOutboxItem("data/outbox/$name", String(Files.readAllBytes(dir.resolve(name)), Charsets.UTF_8))
                if (item.id + ".yml" != name) throw OutboxFormatException("holds the message ${item.id}, which does not match its name")
                items[item.id] = item
            } catch (e: OutboxFormatException) {
                Log.error("data/outbox/$name: ${e.message}; left alone and not used")
            }
        }
        Log.info("data/outbox/: ${items.size} messages loaded, ${items.values.count { it.state == NotificationState.QUEUED || it.state == NotificationState.UNKNOWN }} still to send")
    }

    /**
     * The time notifications first ran on this root, kept in `data/outbox/.since`: records made before it never get a message by reconciling,
     * so the first start after an upgrade does not send the history. Set when the file is missing. Whole seconds, like the records'
     * own times: a finer watermark would put a record made later in the same second before it, and that record would never be reconciled.
     */
    var since: Instant = Instant.EPOCH
        private set

    private fun loadSince() {
        val file = dir.resolve(".since")
        since = try {
            Instant.parse(String(Files.readAllBytes(file), Charsets.UTF_8).trim()).truncatedTo(ChronoUnit.SECONDS)
        } catch (e: java.io.IOException) {
            writeSince(file)
        } catch (e: java.time.format.DateTimeParseException) {
            Log.warn("data/outbox/.since is not a time; the first start with notifications is taken to be now")
            writeSince(file)
        }
    }

    private fun writeSince(file: Path): Instant =
        clock.instant().truncatedTo(ChronoUnit.SECONDS).also { barrier.mutate { writeAtomically(file, it.toString().toByteArray(Charsets.UTF_8)) } }

    fun find(id: String): OutboxItem? = items[id]

    /** [text] as the one id it is, or as the one id it starts with; null if there is none or more than one. */
    fun resolve(text: String): OutboxItem? = items[text] ?: items.values.filter { it.id.startsWith(text) }.singleOrNull()

    fun all(): List<OutboxItem> = items.values.sortedBy { it.id }

    /** Ids whose newest state is in memory only because the disk refused it, with the time to try writing again. */
    private val unsaved = ConcurrentHashMap<String, Instant>()

    fun isUnsaved(id: String): Boolean = unsaved.containsKey(id)

    /** When the earliest unsaved state is to be written again, or null if all are on disk. */
    fun nextSaveAt(): Instant? = unsaved.values.minOrNull()

    /**
     * Makes [item] the stored one and writes it. The state is kept in memory whatever the disk says: if the write fails the item
     * is remembered as unsaved and [saveUnsaved] writes it again after [retryAfterSeconds]. Returns whether it is on disk.
     */
    fun put(item: OutboxItem, retryAfterSeconds: Long): Boolean = barrier.mutate { putInLock(item, retryAfterSeconds) }

    @Synchronized
    private fun putInLock(item: OutboxItem, retryAfterSeconds: Long): Boolean {
        items[item.id] = item
        return try {
            Files.createDirectories(dir)
            writeAtomically(dir.resolve("${item.id}.yml"), renderOutboxItem(item).toByteArray(Charsets.UTF_8))
            unsaved.remove(item.id)
            true
        } catch (e: java.io.IOException) {
            Log.error("data/outbox/${item.id}.yml cannot be written (${e.message}); its state is kept in memory and written again in $retryAfterSeconds s", e)
            unsaved[item.id] = clock.instant().plusSeconds(retryAfterSeconds)
            false
        }
    }

    /** Writes again every unsaved state that is due. */
    fun saveUnsaved(retryAfterSeconds: Long) {
        val now = clock.instant()
        for ((id, at) in unsaved.entries.toList()) {
            if (at.isAfter(now)) continue
            items[id]?.let { put(it, retryAfterSeconds) } ?: unsaved.remove(id)
        }
    }

    /** Puts [draft] in as a new queued message. The id is the time, then a number: sortable, and different even for messages of one second. */
    fun add(draft: Draft): OutboxItem = barrier.mutate { addInLock(draft) }

    @Synchronized
    private fun addInLock(draft: Draft): OutboxItem {
        val prefix = idTime.format(clock.instant().atZone(config.timezone))
        val id = generateSequence(1) { it + 1 }.map { "$prefix-%03d".format(it) }.first { !items.containsKey(it) && !Files.exists(dir.resolve("$it.yml")) }
        val notification = Notification(id, draft.code, draft.event, draft.createdAt, draft.branch, draft.user, draft.locale, draft.fields, 1, draft.resendOf)
        return OutboxItem(notification, NotificationState.QUEUED, subject = draft.subject).also { putInLock(it, config.notify.backoffBaseSeconds.toLong()) }
    }
}

/**
 * Sends the messages of the outbox, one at a time, through the notifier that is in use at that moment, and keeps each one's state:
 * `queued` until the channel takes it (`sent`); a try with no answer in `notify.timeout-seconds` or a timeout error makes it `unknown` and it is tried again
 * later, which is not a failure; a [DeliveryResult.Retry] or another error is tried again after a growing wait (`notify.backoff.*`) and counts, and after
 * `notify.max-attempts` of them (a [DeliveryResult.Failed] counts like a [DeliveryResult.Retry]) the message is `failed`. Failed and sent messages are kept and never offered again
 * unless somebody sends them again, which makes a new message. Every change of state is written to the file before the next message is tried,
 * so a restart picks up where this stopped; a message that was being tried when the server stopped is unknown, and may arrive twice (it carries its code).
 *
 * [processDue] is the whole of the work and runs on the thread [start] makes; tests call it themselves with a clock they move.
 */
internal class Outbox(
    private val store: OutboxStore,
    private val config: ConfigService,
    private val notifier: () -> Notifier?,
    private val clock: Clock,
    private val deliverer: Deliverer,
) {
    private val lock = Object()
    private var woken = false

    @Volatile
    private var stopping = false

    private var worker: Thread? = null

    /** Called at the start of every round of the worker, before it sends; an exception is logged and does not stop the round. */
    var housekeeping: () -> Unit = {}

    /** Writes a new message and makes the worker look at it now. */
    fun enqueue(draft: Draft): OutboxItem = store.add(draft).also { wake() }

    fun find(idOrPrefix: String): OutboxItem? = store.resolve(idOrPrefix)

    fun items(): List<OutboxItem> = store.all()

    /** When notifications first ran on this root (see [OutboxStore.since]). */
    val since: Instant get() = store.since

    private val lineageLock = Any()

    /**
     * Runs [body] (which puts a message into the outbox) unless a message about the same subject and event (the original, or any copy sent again) is still
     * queued or unknown; then it returns null. Check and [body] happen under one lock, so two sends again of one subject cannot both pass.
     */
    fun <R : Any> unlessWaiting(event: String, subject: String?, code: String, body: () -> R): R? = synchronized(lineageLock) {
        val waiting = store.all().any {
            it.notification.event == event && (if (subject != null) it.subject == subject else it.notification.code == code) &&
                (it.state == NotificationState.QUEUED || it.state == NotificationState.UNKNOWN)
        }
        if (waiting) null else body()
    }

    /** A copy of [item] as a new message in the outbox: same code and words, [OutboxItem.id] named in `resendOf`. */
    fun resend(item: OutboxItem): OutboxItem {
        val n = item.notification
        return enqueue(Draft(n.code, n.event, stamp(clock, config), n.branch, n.user, n.locale, n.fields, resendOf = item.id, subject = item.subject))
    }

    /** Tries every message that is due once, oldest first. Returns how many were tried. */
    fun processDue(): Int {
        store.saveUnsaved(config.notify.backoffBaseSeconds.toLong())
        val now = clock.instant()
        // An item whose last outcome is not on disk is not offered again until it is: a crash now would replay the old file.
        val due = store.all().filter { !store.isUnsaved(it.id) && (it.state == NotificationState.QUEUED || it.state == NotificationState.UNKNOWN) && (it.nextAttempt?.let { at -> !at.isAfter(now) } ?: true) }
        var tried = 0
        for (item in due) {
            if (stopping) break
            tryOnce(item)
            tried++
        }
        return tried
    }

    private fun tryOnce(item: OutboxItem) {
        val settings = config.notify
        val sender = notifier()
        val attempt = if (sender == null) {
            Attempt.Answer(DeliveryResult.Retry("no notifier is registered"))
        } else {
            val n = item.notification
            deliverer.deliver(sender, Notification(n.id, n.code, n.event, n.createdAt, n.branch, n.user, n.locale, n.fields, item.attempts + 1, n.resendOf), settings.timeoutSeconds)
        }
        val now = clock.instant()
        val at = stamp(clock, config)
        val tries = item.attempts + 1
        fun later(): Instant = now.plusSeconds(backoffSeconds(tries, settings.backoffBaseSeconds, settings.backoffMaxSeconds))
        val next = when (attempt) {
            is Attempt.Answer -> when (val result = attempt.result) {
                DeliveryResult.Sent -> item.copy(state = NotificationState.SENT, attempts = tries, lastAttempt = at, nextAttempt = null, sentAt = at, lastError = null)
                is DeliveryResult.Retry -> refused(item, tries, at, result.reason, later(), settings.maxAttempts)
                is DeliveryResult.Failed -> refused(item, tries, at, result.reason, later(), settings.maxAttempts)
            }
            Attempt.TimedOut ->
                item.copy(state = NotificationState.UNKNOWN, attempts = tries, lastAttempt = at, nextAttempt = later(), lastError = "no answer within ${settings.timeoutSeconds} s; it may have arrived")
            Attempt.Stopped -> {
                item.copy(state = NotificationState.UNKNOWN, lastAttempt = at, nextAttempt = now, lastError = "the server stopped while it was being sent; it may have arrived")
            }
            is Attempt.Threw ->
                // Only the class: a plugin's exception may quote a secret (a URL with a token), and this is stored and shown to admins.
                if (isTimeout(attempt.error)) item.copy(state = NotificationState.UNKNOWN, attempts = tries, lastAttempt = at, nextAttempt = later(), lastError = "${attempt.error.javaClass.simpleName}: no answer; it may have arrived")
                else refused(item, tries, at, "${attempt.error.javaClass.simpleName} (the plugin's own log may say more)", later(), settings.maxAttempts)
        }
        store.put(next, settings.backoffBaseSeconds.toLong())
        if (next.state == NotificationState.FAILED) Log.warn("Notification ${item.id} (${item.notification.code}) failed for good: ${next.lastError}")
        else if (next.state != NotificationState.SENT) Log.debug("Notification ${item.id} is ${next.state.name.lowercase()}: ${next.lastError}; next try ${next.nextAttempt}")
    }

    /** A try the channel refused: tried again later, or given up when this was the last one allowed. */
    private fun refused(item: OutboxItem, tries: Int, at: String, reason: String, later: Instant, maxAttempts: Int): OutboxItem {
        val failures = item.failures + 1
        val failed = failures >= maxAttempts
        return item.copy(
            state = if (failed) NotificationState.FAILED else NotificationState.QUEUED, attempts = tries, failures = failures, lastAttempt = at,
            nextAttempt = if (failed) null else later, lastError = reasonText(reason),
        )
    }

    /** A reason is one short line: it is shown in lists and the console. */
    private fun reasonText(reason: String) = reason.replace(Regex("\\s+"), " ").trim().take(300).ifEmpty { "no reason given" }

    /** The earliest time a message is due again, or null when nothing waits (messages that are due now give the present). */
    fun nextDue(): Instant? {
        val now = clock.instant()
        val delivery = store.all().filter { !store.isUnsaved(it.id) && (it.state == NotificationState.QUEUED || it.state == NotificationState.UNKNOWN) }.minOfOrNull { it.nextAttempt ?: now }
        return listOfNotNull(delivery, store.nextSaveAt()).minOrNull()
    }

    fun wake() = synchronized(lock) {
        woken = true
        lock.notifyAll()
    }

    /** Starts the thread that sends what is due and sleeps until the next time something is due or a message is put in. */
    fun start() {
        check(worker == null) { "the outbox is started already" }
        worker = Thread(::run, "notify-outbox").apply { isDaemon = true; start() }
    }

    private fun run() {
        while (!stopping) {
            try {
                housekeeping()
                processDue()
            } catch (e: Exception) {
                Log.error("The notification outbox failed on one round: ${e.javaClass.simpleName}: ${e.message}", e)
            }
            synchronized(lock) {
                if (!woken && !stopping) {
                    val wait = nextDue()?.let { due -> (due.toEpochMilli() - clock.millis()).coerceIn(1, MAX_SLEEP_MS) } ?: MAX_SLEEP_MS
                    lock.wait(wait)
                }
                woken = false
            }
        }
    }

    /** Stops the worker: a message being tried is recorded as unknown (its wait is ended, the worker itself is not interrupted); waits up to [waitMillis] for it. */
    fun stop(waitMillis: Long = 10_000) {
        stopping = true
        deliverer.stop()
        wake()
        worker?.join(waitMillis)
    }

    companion object {
        /** The longest the worker sleeps without looking, so a clock that jumped or a missed wake-up delays a message by a minute at most. */
        private const val MAX_SLEEP_MS = 60_000L

        /** The wait after the [tries]th try: [base] doubled for every try after the first, never above [max]. */
        fun backoffSeconds(tries: Int, base: Int, max: Int): Long {
            var wait = base.toLong()
            repeat(tries - 1) { if (wait < max) wait *= 2 }
            return minOf(wait, max.toLong())
        }
    }
}
