package xyz.felismp.shoparchive.server.notify

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.Notifier
import xyz.felismp.shoparchive.server.auth.TestClock
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.write
import xyz.felismp.shoparchive.shared.NotificationState
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The outbox's states and what moves a message between them, with a clock the test moves and a deliverer that answers at once. */
class OutboxTest {
    @TempDir
    lateinit var root: Path

    private val clock = TestClock()
    private val deliverer = FakeDeliverer()
    private var notifier: Notifier? = ScriptedNotifier()

    @BeforeTest
    fun setUp() = prepareRoot(root)

    private fun config(notify: String = ""): ConfigService {
        if (notify.isNotEmpty()) root.write("config/shoparchive.yml", "config-version: 1\n\nnotify:\n$notify")
        return ConfigService(root, log = RecordingLog(), clock = clock).also { it.load() }
    }

    /** One server run over [root]: the store read from the files, like a start. */
    private fun outbox(config: ConfigService = config()): Outbox {
        val store = OutboxStore(root, config, clock).also { it.load() }
        return Outbox(store, config, { notifier }, clock, deliverer)
    }

    private fun draft(code: String = "20261003-AAAAA", fields: Map<String, String> = mapOf("amount" to "150000", "item" to "coffee")) =
        Draft(code, "entry.created", "2026-10-03T15:00:00+07:00", "main", "noy", "lo", fields)

    private fun Outbox.only() = items().single()

    // --- sent ---

    @Test
    fun aMessageIsQueuedInAFileThenSentOnTheNextRoundAndKept() {
        val outbox = outbox()

        val queued = outbox.enqueue(draft())

        assertEquals(NotificationState.QUEUED, queued.state)
        assertTrue(Files.exists(root.resolve("data/outbox/${queued.id}.yml")))
        assertEquals(1, outbox.processDue())

        val sent = outbox.only()
        assertEquals(NotificationState.SENT, sent.state)
        assertEquals(1, sent.attempts)
        assertEquals(0, sent.failures)
        assertNotNull(sent.sentAt)
        assertTrue("state: sent" in Files.readString(root.resolve("data/outbox/${sent.id}.yml")))
        assertEquals(0, outbox.processDue(), "a sent message is not offered again")
        assertEquals(1, deliverer.seen.size)
    }

    @Test
    fun theNotifierGetsTheTimeoutFromTheConfigAndTheNumberOfThisTry() {
        val outbox = outbox(config("  timeout-seconds: 12\n"))
        outbox.enqueue(draft())
        deliverer.script += Attempt.Answer(DeliveryResult.Retry("busy"))

        outbox.processDue()
        clock.advance(Duration.ofMinutes(5))
        outbox.processDue()

        assertEquals(12, deliverer.lastTimeoutSeconds)
        assertEquals(listOf(1, 2), deliverer.seen.map { it.attempt })
    }

    @Test
    fun idsAreUniqueAndSortInTheOrderTheMessagesWereMadeEvenWithinOneSecond() {
        val outbox = outbox()

        val ids = (1..12).map { outbox.enqueue(draft(code = "C$it")).id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
        assertEquals(ids, outbox.items().map { it.id })
    }

    @Test
    fun theMessagesAreSentOldestFirst() {
        val outbox = outbox()
        val first = outbox.enqueue(draft(code = "ONE"))
        val second = outbox.enqueue(draft(code = "TWO"))

        outbox.processDue()

        assertEquals(listOf(first.id, second.id), deliverer.seen.map { it.id })
    }

    // --- retry and backoff ---

    @Test
    fun aRetryIsTriedAgainAfterTheBaseWaitThenTwiceAsLongUpToTheMax() {
        val outbox = outbox(config("  backoff:\n    base-seconds: 10\n    max-seconds: 25\n"))
        outbox.enqueue(draft())
        deliverer.script += listOf(Attempt.Answer(DeliveryResult.Retry("a")), Attempt.Answer(DeliveryResult.Retry("b")), Attempt.Answer(DeliveryResult.Retry("c")), Attempt.Answer(DeliveryResult.Retry("d")))

        assertEquals(1, outbox.processDue())
        assertEquals(NotificationState.QUEUED, outbox.only().state)
        assertEquals("a", outbox.only().lastError)
        assertEquals(clock.now.plusSeconds(10), outbox.only().nextAttempt)

        clock.advance(Duration.ofSeconds(9))
        assertEquals(0, outbox.processDue(), "not due yet")
        clock.advance(Duration.ofSeconds(1))
        assertEquals(1, outbox.processDue())
        assertEquals(clock.now.plusSeconds(20), outbox.only().nextAttempt)

        clock.advance(Duration.ofSeconds(20))
        outbox.processDue()
        assertEquals(clock.now.plusSeconds(25), outbox.only().nextAttempt, "capped at max-seconds")

        clock.advance(Duration.ofSeconds(25))
        outbox.processDue()
        assertEquals(clock.now.plusSeconds(25), outbox.only().nextAttempt)
        assertEquals(4, outbox.only().failures)
    }

    @Test
    fun aRetryThenSentEndsSentAndForgetsTheError() {
        val outbox = outbox()
        outbox.enqueue(draft())
        deliverer.script += Attempt.Answer(DeliveryResult.Retry("no network"))
        outbox.processDue()
        clock.advance(Duration.ofHours(1))

        outbox.processDue()

        val item = outbox.only()
        assertEquals(NotificationState.SENT, item.state)
        assertNull(item.lastError)
        assertNull(item.nextAttempt)
        assertEquals(2, item.attempts)
        assertEquals(1, item.failures)
    }

    @Test
    fun theMessageFailsOnlyWhenTheMaxAttemptsOfRefusedTriesAreUsed() {
        val outbox = outbox(config("  max-attempts: 3\n"))
        outbox.enqueue(draft())
        repeat(3) { deliverer.script += Attempt.Answer(DeliveryResult.Retry("down")) }

        repeat(2) {
            outbox.processDue()
            assertEquals(NotificationState.QUEUED, outbox.only().state, "still waiting after ${it + 1} refused tries")
            clock.advance(Duration.ofDays(1))
        }
        outbox.processDue()

        val item = outbox.only()
        assertEquals(NotificationState.FAILED, item.state)
        assertEquals(3, item.failures)
        assertEquals("down", item.lastError)
        assertNull(item.nextAttempt)
        clock.advance(Duration.ofDays(30))
        assertEquals(0, outbox.processDue(), "a failed message waits for a person")
        assertTrue("state: failed" in Files.readString(root.resolve("data/outbox/${item.id}.yml")), "kept, never deleted")
    }

    @Test
    fun aFailedAnswerCountsAsAFailedAttemptAndTheMessageIsTriedAgainUntilTheMaxAttempts() {
        val outbox = outbox(config("  max-attempts: 2\n"))
        outbox.enqueue(draft())
        repeat(2) { deliverer.script += Attempt.Answer(DeliveryResult.Failed("chat not found")) }

        outbox.processDue()

        assertEquals(NotificationState.QUEUED, outbox.only().state, "one failed attempt is not the end")
        assertEquals("chat not found", outbox.only().lastError)
        assertNotNull(outbox.only().nextAttempt)
        clock.advance(Duration.ofHours(1))
        outbox.processDue()

        assertEquals(NotificationState.FAILED, outbox.only().state)
        assertEquals(2, outbox.only().failures)
    }

    @Test
    fun aMessageThatFailedOnceCanStillGetThroughWhenTheSettingIsFixed() {
        val outbox = outbox()
        outbox.enqueue(draft())
        deliverer.script += Attempt.Answer(DeliveryResult.Failed("wrong token"))
        outbox.processDue()
        clock.advance(Duration.ofHours(1))

        outbox.processDue()

        assertEquals(NotificationState.SENT, outbox.only().state)
    }

    @Test
    fun aReasonIsOneShortLine() {
        val outbox = outbox()
        outbox.enqueue(draft())
        deliverer.script += Attempt.Answer(DeliveryResult.Retry("line one\n\n   line two " + "x".repeat(500)))

        outbox.processDue()

        val reason = outbox.only().lastError!!
        assertTrue(reason.startsWith("line one line two x") && '\n' !in reason && reason.length <= 300, reason)
    }

    // --- the disk refuses a state ---

    @Test
    fun aStateTheDiskRefusedIsKeptInMemoryNotDeliveredAgainAndWrittenWhenTheDiskWorksAgain() {
        val outbox = outbox(config("  backoff:\n    base-seconds: 30\n"))
        val queued = outbox.enqueue(draft())
        val folder = root.resolve("data/outbox")
        val file = folder.resolve("${queued.id}.yml")
        val queuedText = Files.readString(file)
        // the folder is taken away and a file put in its place: every write fails
        Files.list(folder).use { l -> l.toList() }.forEach(Files::delete)
        Files.delete(folder)
        Files.writeString(folder, "in the way")

        assertEquals(1, outbox.processDue())
        assertEquals(NotificationState.SENT, outbox.only().state, "the outcome is kept")
        assertEquals(1, deliverer.seen.size)
        assertEquals(clock.now.plusSeconds(30), outbox.nextDue(), "the worker waits for the retry of the write, it does not spin")
        repeat(3) {
            assertEquals(0, outbox.processDue())
            clock.advance(Duration.ofSeconds(5))
        }

        // the disk works again
        Files.delete(folder)
        Files.createDirectories(folder)
        clock.advance(Duration.ofMinutes(5))
        outbox.processDue()

        assertEquals(1, deliverer.seen.size, "never delivered again")
        assertTrue("state: sent" in Files.readString(file), queuedText)
        assertNull(outbox.nextDue())
    }

    // --- unknown ---

    @Test
    fun noAnswerInTimeIsUnknownAndIsTriedAgainWithoutCountingAsAFailure() {
        val outbox = outbox(config("  max-attempts: 2\n  timeout-seconds: 5\n"))
        outbox.enqueue(draft())
        repeat(6) { deliverer.script += Attempt.TimedOut }

        repeat(6) {
            outbox.processDue()
            val item = outbox.only()
            assertEquals(NotificationState.UNKNOWN, item.state, "try ${it + 1}")
            assertEquals(0, item.failures)
            assertTrue("may have arrived" in item.lastError!!)
            clock.advance(Duration.ofDays(1))
        }
        assertEquals(6, outbox.only().attempts)

        // and when it finally answers, it is sent
        outbox.processDue()
        assertEquals(NotificationState.SENT, outbox.only().state)
    }

    @Test
    fun anUnknownMessageWaitsForTheBackoffLikeARetry() {
        val outbox = outbox(config("  backoff:\n    base-seconds: 60\n"))
        outbox.enqueue(draft())
        deliverer.script += Attempt.TimedOut

        outbox.processDue()

        assertEquals(clock.now.plusSeconds(60), outbox.only().nextAttempt)
        assertEquals(0, outbox.processDue())
    }

    @Test
    fun aTimeoutErrorThrownByTheNotifierIsUnknownEvenWhenItIsTheCauseOfWhatWasThrown() {
        val outbox = outbox()
        outbox.enqueue(draft())
        deliverer.script += Attempt.Threw(IllegalStateException("send failed", SocketTimeoutException("Read timed out")))

        outbox.processDue()

        assertEquals(NotificationState.UNKNOWN, outbox.only().state)
        assertEquals(0, outbox.only().failures)
    }

    @Test
    fun anyOtherErrorCountsAsARefusedTryAndNothingOfItsMessageIsStored() {
        val outbox = outbox()
        outbox.enqueue(draft())
        deliverer.script += Attempt.Threw(IllegalStateException("POST https://api.example.org/bot123456:SECRET-TOKEN/send failed"))

        outbox.processDue()

        val item = outbox.only()
        assertEquals(NotificationState.QUEUED, item.state)
        assertEquals(1, item.failures)
        assertTrue(item.lastError!!.startsWith("IllegalStateException"), item.lastError)
        assertFalse("SECRET-TOKEN" in Files.readString(root.resolve("data/outbox/${item.id}.yml")))
    }

    @Test
    fun aMessageBeingSentWhenTheServerStopsIsUnknownAndDueAtOnce() {
        val outbox = outbox()
        outbox.enqueue(draft())
        deliverer.script += Attempt.Stopped

        outbox.processDue()

        val item = outbox.only()
        assertEquals(NotificationState.UNKNOWN, item.state)
        assertEquals(0, item.attempts, "a stop is not a try")
        assertEquals(clock.now, item.nextAttempt)
    }

    // --- restart ---

    @Test
    fun aRestartResumesWhatWasStillToSendWithItsCountsAndKeepsWhatWasSent() {
        val first = outbox(config("  max-attempts: 5\n"))
        val gone = first.enqueue(draft(code = "SENT1"))
        first.processDue()
        val waiting = first.enqueue(draft(code = "WAIT1"))
        deliverer.script += Attempt.Answer(DeliveryResult.Retry("down"))
        first.processDue()
        val dueAt = first.find(waiting.id)!!.nextAttempt

        // a new process: nothing in memory, only the files
        val second = outbox(config("  max-attempts: 5\n"))

        assertEquals(NotificationState.SENT, second.find(gone.id)!!.state)
        val resumed = second.find(waiting.id)!!
        assertEquals(NotificationState.QUEUED, resumed.state)
        assertEquals(1, resumed.failures)
        assertEquals(dueAt, resumed.nextAttempt)
        assertEquals(0, second.processDue())
        clock.advance(Duration.ofHours(1))
        assertEquals(1, second.processDue())
        assertEquals(NotificationState.SENT, second.find(waiting.id)!!.state)
        assertEquals(2, second.find(waiting.id)!!.notification.attempt.let { deliverer.seen.last().attempt })
    }

    @Test
    fun aMessageWithQuotesAndNewlinesComesBackFromItsFileExactly() {
        val fields = mapOf("item" to "say \"hi\"\nnext: line # not a comment", "category" to "", "amount" to "null")
        val written = outbox().enqueue(draft(fields = fields))

        val read = outbox().items().single()

        assertEquals(written.notification.fields, read.notification.fields)
        assertEquals(fields, read.notification.fields)
        assertEquals(written.state, read.state)
        assertEquals(written.id, read.id)
    }

    @Test
    fun aMessageWithAnEmptyBranchComesBackFromItsFileStillQueued() {
        // device.new for a user with no branch: the branch is empty, not missing.
        val written = outbox().enqueue(Draft("20261003-BBBBB", "device.new", "2026-10-03T15:00:00+07:00", "", "dana", "lo", mapOf("user" to "dana")))
        assertTrue("branch: \"\"\n" in Files.readString(root.resolve("data/outbox/${written.id}.yml")))

        val read = outbox().items().single()

        assertEquals(written.id, read.id)
        assertEquals("", read.notification.branch)
        assertEquals(NotificationState.QUEUED, read.state)
        assertEquals(written.notification.fields, read.notification.fields)
    }

    @Test
    fun aFileWithoutABranchIsStillNotUsed() {
        val written = outbox().enqueue(draft())
        val file = root.resolve("data/outbox/${written.id}.yml")
        val text = Files.readString(file)
        Files.writeString(file, text.replace("branch: \"main\"\n", ""))

        assertEquals(emptyList(), outbox().items())
        val e = assertFailsWith<OutboxFormatException> { parseOutboxItem("x.yml", text.replace("branch: \"main\"\n", "")) }
        assertEquals("branch is missing", e.message)
    }

    @Test
    fun aFileThatCannotBeReadIsLeftAloneAndTheOthersAreStillUsed() {
        val good = outbox().enqueue(draft())
        Files.writeString(root.resolve("data/outbox/20261003-000000-001.yml"), "file-version: 1\nid: \"20261003-000000-001\"\nstate: lost\n")
        Files.writeString(root.resolve("data/outbox/20261003-000000-002.yml"), "this is: [not closed")
        val before = Files.readString(root.resolve("data/outbox/20261003-000000-001.yml"))

        val outbox = outbox()

        assertEquals(listOf(good.id), outbox.items().map { it.id })
        assertEquals(before, Files.readString(root.resolve("data/outbox/20261003-000000-001.yml")))
    }

    @Test
    fun aFileFromANewerServerIsNotUsedOrChanged() {
        val good = outbox().enqueue(draft())
        val file = root.resolve("data/outbox/${good.id}.yml")
        val future = Files.readString(file).replace("file-version: 1", "file-version: 2")
        Files.writeString(file, future)

        assertEquals(emptyList(), outbox().items())
        assertEquals(future, Files.readString(file))
    }

    // --- resend ---

    @Test
    fun sendingAgainMakesANewMessageWithTheSameCodeAndWordsAndLeavesTheOldOneAlone() {
        val outbox = outbox()
        val original = outbox.enqueue(draft(code = "20261003-ABCDE"))
        outbox.processDue()
        val before = Files.readString(root.resolve("data/outbox/${original.id}.yml"))
        clock.advance(Duration.ofHours(2))

        val copy = outbox.resend(outbox.find(original.id)!!)

        assertNotEquals(original.id, copy.id)
        assertEquals("20261003-ABCDE", copy.notification.code)
        assertEquals(original.notification.fields, copy.notification.fields)
        assertEquals(original.id, copy.notification.resendOf)
        assertNotEquals(original.notification.createdAt, copy.notification.createdAt, "the copy is made now; the words keep the time of the event")
        assertEquals(NotificationState.QUEUED, copy.state)
        assertEquals(0, copy.attempts)
        assertEquals(before, Files.readString(root.resolve("data/outbox/${original.id}.yml")), "history is never edited")
        assertEquals(1, outbox.processDue(), "only the new one is due")
        assertEquals(copy.id, deliverer.seen.last().id)
        assertEquals(original.id, deliverer.seen.last().resendOf)
    }

    // --- the notifier in use ---

    @Test
    fun theNotifierInUseWhenTheTurnComesSendsTheMessageNotTheOneThatWasThereWhenItWasQueued() {
        val outbox = outbox()
        outbox.enqueue(draft())
        val later = ScriptedNotifier()

        notifier = later
        outbox.processDue()

        assertEquals(1, later.got.size)
    }

    // --- worker thread, with the real deliverer ---

    @Test
    fun theRealDelivererGivesUpWaitingAfterTheTimeoutAndSaysUnknown() {
        val release = CountDownLatch(1)
        val hung = Notifier { release.await(); DeliveryResult.Sent }
        val real = ThreadedDeliverer(unit = TimeUnit.MILLISECONDS)

        val attempt = real.deliver(hung, Notification("i", "c", "entry.created", "t", "main", "noy", "lo", emptyMap()), timeoutSeconds = 30)

        release.countDown()
        real.stop()
        assertTrue(attempt is Attempt.TimedOut, attempt.toString())
    }

    @Test
    fun theRealDelivererPassesOnResultsAndExceptions() {
        val real = ThreadedDeliverer()
        fun run(notifier: Notifier) = real.deliver(notifier, Notification("i", "c", "entry.created", "t", "main", "noy", "lo", emptyMap()), 30)

        assertEquals(DeliveryResult.Sent, (run { DeliveryResult.Sent } as Attempt.Answer).result)
        assertTrue(((run { throw java.io.IOException("x") }) as Attempt.Threw).error is java.io.IOException)
        real.stop()
    }

    @Test
    fun theWorkerSendsAMessageAsSoonAsItIsQueuedAndStopsCleanly() {
        // Counts down once the notifier has answered, so the stop below cannot come before the answer.
        val answered = CountDownLatch(1)
        val real = ThreadedDeliverer()
        val deliverer = object : Deliverer {
            override fun deliver(notifier: Notifier, notification: Notification, timeoutSeconds: Int) =
                real.deliver(notifier, notification, timeoutSeconds).also { answered.countDown() }

            override fun stop() = real.stop()
        }
        val config = config()
        val store = OutboxStore(root, config, java.time.Clock.systemUTC()).also { it.load() }
        val outbox = Outbox(store, config, { notifier }, java.time.Clock.systemUTC(), deliverer)
        outbox.start()

        outbox.enqueue(draft())

        assertTrue(answered.await(10, TimeUnit.SECONDS), "not sent")
        outbox.stop() // waits for the worker, which is writing the state
        assertEquals(NotificationState.SENT, outbox.only().state)
    }

    @Test
    fun stoppingWhileAMessageIsBeingSentLeavesItUnknownNotFailed() {
        val inside = CountDownLatch(1)
        notifier = Notifier { inside.countDown(); CountDownLatch(1).await(); DeliveryResult.Sent }
        val config = config()
        val store = OutboxStore(root, config, java.time.Clock.systemUTC()).also { it.load() }
        val outbox = Outbox(store, config, { notifier }, java.time.Clock.systemUTC(), ThreadedDeliverer())
        outbox.start()
        outbox.enqueue(draft())
        assertTrue(inside.await(10, TimeUnit.SECONDS))

        outbox.stop()

        val item = outbox.only()
        assertEquals(NotificationState.UNKNOWN, item.state)
        assertEquals(0, item.failures)
        assertTrue("state: unknown" in Files.readString(root.resolve("data/outbox/${item.id}.yml")))
    }
}
