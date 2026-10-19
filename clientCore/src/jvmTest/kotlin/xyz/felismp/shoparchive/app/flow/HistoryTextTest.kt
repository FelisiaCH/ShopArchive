package xyz.felismp.shoparchive.app.flow

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTextTest {
    private val before = Locale.getDefault()

    @BeforeTest fun english() = Locale.setDefault(Locale.US)

    @AfterTest fun restore() = Locale.setDefault(before)

    @Test fun tendersGetTheirWordsAndAGroupedAmount() {
        assertEquals("150,000 LAK · Cash; 150,000 LAK · Online", historyTenders("LAK cash 150000; LAK online 150000", "Cash", "Online"))
        assertEquals("1,200.50 THB · Online", historyTenders("THB online 1200.50", "Cash", "Online"))
    }

    @Test fun aTenderPartThatDoesNotMatchStaysAsItCame() {
        assertEquals("LAK card 5; 5 LAK · Cash", historyTenders("LAK card 5; LAK cash 5", "Cash", "Online"))
        assertEquals("something else", historyTenders("something else", "Cash", "Online"))
        assertEquals("", historyTenders("", "Cash", "Online"))
    }

    @Test fun transfersGroupOnlyTheAmount() {
        assertEquals("BCEL 850,000 LAK", historyTransfers("BCEL 850000 LAK"))
        assertEquals("BCEL 850,000 LAK; LDB 12.50 THB", historyTransfers("BCEL 850000 LAK; LDB 12.50 THB"))
        assertEquals("BCEL lots LAK", historyTransfers("BCEL lots LAK"))
    }

    @Test fun slipsAreNumbered() {
        assertEquals(listOf(1, 2), historySlipNumbers("slip-1.jpg; slip-2.png"))
        assertEquals(null, historySlipNumbers("slip-1.jpg; photo.jpg"))
        assertEquals(null, historySlipNumbers("slip-0.jpg"))
        assertEquals(null, historySlipNumbers(""))
    }

    @Test fun aBankTimeLosesItsSecondsAndAnythingElseStays() {
        assertEquals("2026-09-18 19:18", bankTimeText("2026-09-18 19:18:42"))
        assertEquals("2026-09-18T19:18:42", bankTimeText("2026-09-18T19:18:42"))
        assertEquals("2026-09-18 19:18", bankTimeText("2026-09-18 19:18"))
        assertEquals("18/09 19:18", bankTimeText("18/09 19:18"))
        assertEquals("", bankTimeText(""))
    }
}
