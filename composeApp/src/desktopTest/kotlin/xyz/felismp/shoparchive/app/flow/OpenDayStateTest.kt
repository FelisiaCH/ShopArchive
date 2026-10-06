package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpenDayStateTest {
    private fun kotlinx.coroutines.test.TestScope.setup(): Pair<Harness, OpenDayState> {
        val h = Harness(backgroundScope)
        return h to OpenDayState(h.env, TodayState(h.env))
    }

    @Test fun oneFieldPerCurrencyOfTheShop() = runTest(UnconfinedTestDispatcher()) {
        val (_, day) = setup()
        assertEquals(listOf("LAK", "THB"), day.ui.value.currencies.map { it.code })
        assertEquals(null, day.ui.value.blocked)
    }

    @Test fun anAmountMustFitItsCurrencysDecimals() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        day.setAmount("LAK", "12.5")      // kip has no decimals
        day.setAmount("THB", "12.345")    // baht has two
        day.confirm()
        assertEquals(setOf("LAK", "THB"), day.ui.value.invalid)
        assertTrue(h.api.opened.isEmpty())
        day.setAmount("LAK", "12500")
        assertEquals(setOf("THB"), day.ui.value.invalid)
    }

    @Test fun typedTextIsKeptAndOnlyValidAmountsAreSent() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        for (typed in listOf("1,50", "-700", "1,5", "x", "1.2.3")) {
            day.setAmount("LAK", typed)
            assertEquals(typed, day.ui.value.amounts["LAK"])
            day.confirm()
            assertEquals(setOf("LAK"), day.ui.value.invalid, typed)
        }
        day.setAmount("LAK", "2,500")
        day.setAmount("THB", "1,234.50")
        day.confirm()
        assertEquals(mapOf("LAK" to "2500", "THB" to "1234.50"), h.api.opened.single().second.float)
    }

    @Test fun anEmptyFieldIsZeroAndLeftOutTheRequest() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        day.setAmount("THB", "12.5")
        day.confirm()
        assertEquals("main" to OpenSessionRequest(mapOf("THB" to "12.50")), h.api.opened.single())
        assertEquals(OpenDayPhase.Opened, day.ui.value.phase)
    }

    @Test fun zeroAndTypedSeparatorsAreHandled() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        day.setAmount("LAK", "1,500 000") // pasted with separators
        day.setAmount("THB", "0")
        day.confirm()
        assertEquals(mapOf("LAK" to "1500000", "THB" to "0.00"), h.api.opened.single().second.float)
    }

    @Test fun openingTheDayLoadsTodayAgain() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        val before = h.api.dashboardCalls.size
        day.confirm()
        assertEquals(before + 1, h.api.dashboardCalls.size)
    }

    @Test fun aDayOpenedByAnotherDeviceToday() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        h.api.openAnswer = { OpenSessionResponse(h.api.session("s1"), alreadyOpenBy = h.api.alice) }
        day.confirm()
        assertEquals(h.api.alice, assertIs<OpenDayPhase.AlreadyOpen>(day.ui.value.phase).by)
    }

    @Test fun anEarlierDayStillOpenIsToldAndNotOpenedOver() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        h.api.openAnswer = { OpenSessionResponse(h.api.session("s0", date = "2026-10-03"), alreadyOpenBy = h.api.alice) }
        day.confirm()
        assertEquals("2026-10-03", assertIs<OpenDayPhase.PreviousOpen>(day.ui.value.phase).session.businessDate)
    }

    @Test fun offlineBlocksConfirm() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        h.canWrite.value = false
        assertEquals(OpenDayBlock.OFFLINE, day.ui.value.blocked)
        day.confirm()
        assertTrue(h.api.opened.isEmpty())
        h.canWrite.value = true
        assertEquals(null, day.ui.value.blocked)
    }

    @Test fun theServersRefusalIsShownInTheAppsWords() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        h.api.openError = ClientError.Api(403, ErrorCode.FORBIDDEN, "You may not open the day.", reason = ErrorReasons.PERMISSION_MISSING)
        day.setAmount("LAK", "5")
        day.confirm()
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), assertIs<OpenDayPhase.Failed>(day.ui.value.phase).failure)
        assertEquals("5", day.ui.value.amounts["LAK"]) // what was typed stays
    }

    @Test fun anotherBranchStartsWithAnEmptyForm() = runTest(UnconfinedTestDispatcher()) {
        val (h, day) = setup()
        day.setAmount("LAK", "5")
        h.branch.value = "second"
        assertTrue(day.ui.value.amounts.isEmpty())
    }
}
