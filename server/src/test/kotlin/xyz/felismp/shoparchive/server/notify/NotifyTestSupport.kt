package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.DeliveryResult
import xyz.felismp.shoparchive.api.Notification
import xyz.felismp.shoparchive.api.Notifier

/**
 * A [Deliverer] that answers at once. Each call takes the next scripted [Attempt]; with none left it calls the notifier itself, on this
 * thread, and turns what it throws into [Attempt.Threw]. So a state machine test never waits and never needs a thread.
 */
internal class FakeDeliverer : Deliverer {
    val script = ArrayDeque<Attempt>()

    /** Every notification handed over, as the notifier saw it. */
    val seen = mutableListOf<Notification>()
    var lastTimeoutSeconds = 0

    override fun deliver(notifier: Notifier, notification: Notification, timeoutSeconds: Int): Attempt {
        seen += notification
        lastTimeoutSeconds = timeoutSeconds
        script.removeFirstOrNull()?.let { return it }
        return try {
            Attempt.Answer(notifier.deliver(notification))
        } catch (e: Throwable) {
            Attempt.Threw(e)
        }
    }
}

/** A notifier that gives the next scripted result (Sent when the script is empty) and remembers what it got. */
internal class ScriptedNotifier(vararg results: DeliveryResult) : Notifier {
    val script = ArrayDeque(results.toList())
    val got = mutableListOf<Notification>()
    override fun deliver(notification: Notification): DeliveryResult {
        got += notification
        return script.removeFirstOrNull() ?: DeliveryResult.Sent
    }
}

internal fun Attempt.answer(result: DeliveryResult) = Attempt.Answer(result)

/** `notify.max-attempts: 1`: a failed answer ends the message at once, so a test need not move the clock through the retries. */
internal const val ONE_ATTEMPT = "notify:\n  max-attempts: 1\n"
