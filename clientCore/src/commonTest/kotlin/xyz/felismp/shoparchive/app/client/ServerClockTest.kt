package xyz.felismp.shoparchive.app.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerClockTest {
    // Fri, 18 Sep 2026 12:00:00 GMT
    private val noon = 1_789_732_800_000L

    @Test fun theOffsetIsTheServersDateMinusTheDevices() {
        assertEquals(0L, clockOffsetMillis("Fri, 18 Sep 2026 12:00:00 GMT", noon))
        assertEquals(90_000L, clockOffsetMillis("Fri, 18 Sep 2026 12:01:30 GMT", noon))
        assertEquals(-3_600_000L, clockOffsetMillis("Fri, 18 Sep 2026 11:00:00 GMT", noon))
    }

    @Test fun noDateOrADateThatCannotBeReadIsNoOffset() {
        assertNull(clockOffsetMillis(null, noon))
        assertNull(clockOffsetMillis("yesterday", noon))
    }

    @Test fun onlyMoreThanFiveMinutesWarnsInEitherDirection() {
        assertNull(clockWarningMinutes(null))
        assertNull(clockWarningMinutes(0))
        assertNull(clockWarningMinutes(300_000))
        assertNull(clockWarningMinutes(-300_000))
        assertEquals(6, clockWarningMinutes(300_001))
        assertEquals(6, clockWarningMinutes(-360_000))
        assertEquals(60, clockWarningMinutes(3_600_000))
    }
}
