package xyz.felismp.shoparchive.spike.app

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Against a REAL running spike-server: SPIKE_URL=https://127.0.0.1:8443 SPIKE_PIN=<pin from its console> ./gradlew :app:desktopTest
 * Skipped when the variables are not set.
 */
class LiveServerTest {
    private val url = System.getenv("SPIKE_URL")
    private val pin = System.getenv("SPIKE_PIN")

    @Test fun pinPrintedByServerWorksAndAnyOtherPinFails() = runBlocking<Unit> {
        assumeTrue("SPIKE_URL / SPIKE_PIN not set", url != null && pin != null)
        assertContains(httpsPing(url, pin), "\"ok\":true")
        assertTrue(wsEcho(url, pin).startsWith("echo OK"))
        // Same pin with one character changed is still well-formed base64 (32 bytes) but must be refused.
        val wrong = (if (pin.first() == 'A') "B" else "A") + pin.drop(1)
        assertFailsWith<Throwable> { httpsPing(url, wrong) }
        assertFailsWith<Throwable> { wsEcho(url, wrong) }
    }
}
