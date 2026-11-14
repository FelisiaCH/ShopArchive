package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.client.RecordStart
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.Capabilities
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.DayStatus
import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.app.flow.OpenDayBlock
import xyz.felismp.shoparchive.app.flow.OpenDayPhase
import xyz.felismp.shoparchive.app.flow.OpenDayUi
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.app.flow.TodayData
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.SessionStatus
import xyz.felismp.shoparchive.shared.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewsTest {
    private val session = SessionDto("s1", "main", "2026-11-14", UserRef("u1", "Noy"), "2026-11-14T08:12:30+07:00", emptyMap(), SessionStatus.OPEN)
    private val opener = Capabilities(setOf(PermissionNodes.DAY_OPEN))
    private val nobody = Capabilities(emptySet())

    private fun today(status: DayStatus, round: Int = 1, staleFrom: String? = null, clockOff: Int? = null) =
        TodayData("main", "2026-11-14", status, staleFrom, currencies = emptyList(), recent = emptyList(), statsFailure = null, clockOffMinutes = clockOff, round = round)

    private fun view(data: TodayData, can: Capabilities = opener, canWrite: Boolean = true) = todayView(data, can, canWrite) { it }

    // ---- login side ----

    @Test
    fun theServersScreenWaitsThenOffersARetryOnlyAfterAFailure() {
        val idle = AppState.Servers(emptyList(), emptyList(), false, null)
        assertFalse(serversView(idle, autoAddFired = false).retry)
        assertTrue(serversView(idle.copy(busy = true), autoAddFired = true).run { !retry && !error })
        val failed = serversView(idle.copy(problem = Problem.Unreachable), autoAddFired = true)
        assertTrue(failed.retry && failed.error)
        assertEquals("err_unreachable", failed.message.key)
        assertTrue(serversView(idle, autoAddFired = true).retry) // came back here with nothing going on: never a dead end
    }

    @Test
    fun theShellSaysWhatItIsWaitingFor() {
        assertEquals(ShellBody.Loading, shellBody(ConfigState.Loading, null))
        assertEquals(ShellBody.Loading, shellBody(ConfigState.Loading, "main"))
        assertEquals(ShellBody.Failed(Message("cfg_failed")), shellBody(ConfigState.Failed(Failure.Unreachable), "main"))
    }

    @Test
    fun theConnectionBannerOnlyShowsWhenNotConnected() {
        assertNull(connectionBanner(ConnectionStatus.CONNECTED))
        assertEquals("status_connecting", connectionBanner(ConnectionStatus.CONNECTING)?.key)
        assertEquals("offline_banner", connectionBanner(ConnectionStatus.OFFLINE)?.key)
    }

    // ---- Today ----

    @Test
    fun aRoundShowsFromTheSecondOne() {
        assertEquals(listOf("day_open_by"), view(today(DayStatus.Open(session), round = 1)).chips.map { it.key })
        val second = view(today(DayStatus.Open(session), round = 2)).chips
        assertEquals(listOf("day_open_by", "day_round"), second.map { it.key })
        assertEquals(listOf("2"), second[1].args)
        assertEquals(listOf("day_closed", "day_round"), view(today(DayStatus.Closed(session), round = 3)).chips.map { it.key })
    }

    @Test
    fun theOpenTimeIsTheShopsClock() {
        assertEquals(listOf("08:12", "Noy"), view(today(DayStatus.Open(session))).chips.single().args)
        assertEquals("not a time", clockTime("not a time"))
    }

    @Test
    fun aDayNotOpenedOffersOpeningItToSomeoneWhoMay() {
        val v = view(today(DayStatus.NotOpened))
        assertTrue(v.notOpened)
        assertEquals("openday_card_body", v.body?.key)
        assertEquals("openday_btn", v.openButton?.label)
        assertTrue(v.openButton!!.enabled && v.openButton.primary)
        val other = view(today(DayStatus.NotOpened), can = nobody)
        assertEquals("openday_card_body_other", other.body?.key)
        assertNull(other.openButton)
    }

    @Test
    fun aClosedDayOffersANewRoundAndAnOpenOneOffersNone() {
        val closed = view(today(DayStatus.Closed(session)))
        assertEquals("openday_again_btn", closed.openButton?.label)
        assertFalse(closed.openButton!!.primary)
        assertNull(view(today(DayStatus.Closed(session)), can = nobody).openButton)
        assertNull(view(today(DayStatus.Open(session))).openButton)
    }

    @Test
    fun withoutAConnectionOpeningIsOffAndSaysWhy() {
        val v = view(today(DayStatus.NotOpened), canWrite = false)
        assertFalse(v.openButton!!.enabled)
        assertTrue(v.needsConnection)
        assertFalse(view(today(DayStatus.Open(session)), canWrite = false).needsConnection)
    }

    @Test
    fun anOpenDayOffersClosingOnlyToSomeoneWhoMay() {
        val closer = Capabilities(setOf(PermissionNodes.DAY_CLOSE))
        val v = view(today(DayStatus.Open(session)), can = closer)
        assertEquals("closeday_btn", v.closeButton?.label)
        assertFalse(v.closeButton!!.primary)
        assertTrue(view(today(DayStatus.Open(session), staleFrom = "2026-11-12"), can = closer).closeButton!!.primary)
        assertNull(view(today(DayStatus.Open(session))).closeButton)
        assertNull(view(today(DayStatus.Closed(session)), can = closer).closeButton)
    }

    @Test
    fun theSettingsOptionsAreTheAppsOrder() {
        assertEquals(listOf(null, "en", "lo", "th"), languageOptions.map { it.tag })
        assertEquals(listOf("lang_system", "lang_en", "lang_lo", "lang_th"), languageOptions.map { it.label })
        assertEquals(listOf(RecordStart.INCOME, RecordStart.EXPENSE, RecordStart.LAST), recordStartOptions.map { it.start })
        assertEquals(listOf("type_income", "type_expense", "record_start_last"), recordStartOptions.map { it.label })
    }

    @Test
    fun aStaleDayAndAWrongClockAreSaid() {
        val v = view(today(DayStatus.Open(session), staleFrom = "2026-11-12", clockOff = 9))
        assertEquals(listOf("clock_off", "day_stale_other"), v.banners.map { it.key })
        assertEquals(listOf("9"), v.banners[0].args)
        assertEquals("day_stale", view(today(DayStatus.Open(session), staleFrom = "2026-11-12"), can = Capabilities(setOf(PermissionNodes.DAY_CLOSE))).banners.single().key)
    }

    @Test
    fun todayWithoutTotalsSaysNothingRecordedYet() {
        assertTrue(view(today(DayStatus.Open(session))).empty)
    }

    // ---- Open day ----

    private val lak = CurrencyInfo("LAK", 0)

    private fun openDay(amounts: Map<String, String> = emptyMap(), kept: Map<String, String> = emptyMap(), prefill: Map<String, String> = emptyMap(), phase: OpenDayPhase = OpenDayPhase.Idle, blocked: OpenDayBlock? = null, invalid: Set<String> = emptySet()) =
        openDayView(OpenDayUi(listOf(lak), amounts, prefill, kept, invalid, phase, blocked))

    @Test
    fun aNewRoundStartsEmptyAndOnlyHintsWhatWasKept() {
        val v = openDay(kept = mapOf("LAK" to "500000"))
        assertEquals("", v.fields.single().text)
        val hint = assertNotNull(v.keptHint)
        assertEquals("openday_kept_hint", hint.key)
        assertTrue(hint.args.single().startsWith("500") && hint.args.single().endsWith(" LAK"), hint.args.single())
        assertTrue(v.confirmEnabled)
    }

    @Test
    fun aFirstDayIsFilledWithWhatTheLastCloseKept() {
        val v = openDay(prefill = mapOf("LAK" to "500000"))
        assertEquals("500000", v.fields.single().text)
        assertNull(v.keptHint)
        assertNotNull(v.fields.single().shown)
    }

    @Test
    fun aTypedAmountIsShownAndAWrongOneIsFlagged() {
        assertTrue(openDay(amounts = mapOf("LAK" to "1500")).fields.single().shown!!.endsWith(" LAK"))
        val bad = openDay(amounts = mapOf("LAK" to "12x")).fields.single()
        assertTrue(bad.invalid)
        assertNull(bad.shown)
        assertTrue(openDay(invalid = setOf("LAK")).fields.single().invalid)
    }

    @Test
    fun confirmIsOffWhileSavingOfflineOrLoading() {
        assertFalse(openDay(phase = OpenDayPhase.Saving).confirmEnabled)
        assertTrue(openDay(phase = OpenDayPhase.Saving).saving)
        assertFalse(openDay(blocked = OpenDayBlock.LOADING).confirmEnabled)
        val offline = openDay(blocked = OpenDayBlock.OFFLINE)
        assertFalse(offline.confirmEnabled)
        assertTrue(offline.needsConnection)
    }

    @Test
    fun theOutcomeOfOpeningIsSaid() {
        val already = openDay(phase = OpenDayPhase.AlreadyOpen(UserRef("u2", "Dao")))
        assertEquals(Message("openday_already", listOf("Dao")), already.banner?.message)
        assertTrue(already.goToday)
        val previous = openDay(phase = OpenDayPhase.PreviousOpen(session))
        assertEquals(Message("openday_previous", listOf("2026-11-14", "Noy")), previous.banner?.message)
        assertTrue(previous.banner!!.error && !previous.goToday)
        assertEquals("err_unreachable", openDay(phase = OpenDayPhase.Failed(Failure.Unreachable)).banner?.message?.key)
        assertNull(openDay().banner)
    }
}
