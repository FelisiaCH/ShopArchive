package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.app.client.ExportFile
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.DashboardTotals
import xyz.felismp.shoparchive.shared.EntryCreatedMessage
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsStateTest {
    private fun totals() = DashboardTotals("0", "0", "0", "0", "0", "0", 0)
    private fun currency(code: String) = DashboardCurrency(code, totals(), totals(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun TestScope.setup(): Pair<Harness, ReportsState> {
        val h = Harness(backgroundScope)
        h.api.dashboard = DashboardDto("2026-10-04", "2026-10-04", listOf(currency("LAK"), currency("THB")), emptyList())
        return h to ReportsState(h.env)
    }

    @Test fun startsOnTodayForTheChosenBranch() = runTest(UnconfinedTestDispatcher()) {
        val (h, reports) = setup()
        assertEquals(Triple("2026-10-04", "2026-10-04", "main"), h.api.dashboardCalls.single())
        assertEquals(listOf("LAK", "THB"), reports.ui.value.shown.map { it.currency })
        assertFalse(reports.ui.value.loading)
    }

    @Test fun theQuickRangesEndTodayAndTheWeekStartsOnMonday() = runTest(UnconfinedTestDispatcher()) {
        val (h, reports) = setup() // 2026-10-04 is a Sunday
        reports.selectRange(ReportRange.WEEK)
        assertEquals(Triple("2026-09-28", "2026-10-04", "main"), h.api.dashboardCalls.last())
        reports.selectRange(ReportRange.MONTH)
        assertEquals(Triple("2026-10-01", "2026-10-04", "main"), h.api.dashboardCalls.last())
        h.date = LocalDate.parse("2026-10-07") // a Wednesday
        assertEquals("2026-10-05" to "2026-10-07", rangeDates(ReportRange.WEEK, h.date)!!.let { it.first.toString() to it.second.toString() })
        assertNull(rangeDates(ReportRange.CUSTOM, h.date))
    }

    @Test fun aCustomRangeIsAskedForOnlyWhenTheServerWouldTakeIt() = runTest(UnconfinedTestDispatcher()) {
        val (h, reports) = setup()
        val asked = h.api.dashboardCalls.size
        reports.setFrom("2026-09-01")
        assertEquals(ReportRange.CUSTOM, reports.ui.value.range)
        assertEquals(Triple("2026-09-01", "2026-10-04", "main"), h.api.dashboardCalls.last())
        reports.setTo("2026-08-01")
        assertEquals(RangeProblem.BACKWARDS, reports.ui.value.rangeProblem)
        reports.setTo("20")
        assertEquals(RangeProblem.NOT_A_DATE, reports.ui.value.rangeProblem)
        assertEquals(asked + 1, h.api.dashboardCalls.size)
    }

    @Test fun anotherBranchAndTheCurrencyFilter() = runTest(UnconfinedTestDispatcher()) {
        val (h, reports) = setup()
        reports.setBranch("second")
        assertEquals("second", h.api.dashboardCalls.last().third)
        reports.setCurrency("THB")
        assertEquals(listOf("THB"), reports.ui.value.shown.map { it.currency })
        reports.setCurrency(null)
        assertEquals(2, reports.ui.value.shown.size)
    }

    @Test fun aRefusalIsExplainedAndNoDataIsShown() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.dashboardError = ClientError.Api(403, ErrorCode.FORBIDDEN, "This needs the permission shoparchive.dashboard.view.", reason = ErrorReasons.PERMISSION_MISSING)
        val reports = ReportsState(h.env)
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), reports.ui.value.failure)
        assertNull(reports.ui.value.data)
        h.api.dashboardError = null
        reports.reload()
        assertNull(reports.ui.value.failure)
    }

    @Test fun liveEntriesOfTheShownBranchAndAReconnectLoadAgain() = runTest(UnconfinedTestDispatcher()) {
        val (h, _) = setup()
        val first = h.api.dashboardCalls.size
        h.messages.tryEmit(EntryCreatedMessage("e", "2026-10-04", "other"))
        assertEquals(first, h.api.dashboardCalls.size)
        h.messages.tryEmit(EntryCreatedMessage("e", "2026-10-04", "main"))
        h.refetch.tryEmit(Unit)
        assertEquals(first + 2, h.api.dashboardCalls.size)
    }

    @Test fun theChangeAgainstThePreviousPeriodWorksWithNegativeTotals() = runTest(UnconfinedTestDispatcher()) {
        val (_, reports) = setup()
        assertEquals(-800L, reports.delta("LAK", "-700", "100"))
        assertEquals(-200L, reports.delta("LAK", "-700", "-500"))
        assertEquals(300L, reports.delta("LAK", "-200", "-500"))
        assertEquals(1075L, reports.delta("THB", "10.50", "-0.25")) // minor units: two decimals
        assertEquals(-1250L, reports.delta("THB", "-2.50", "10.00"))
        assertEquals(0L, reports.delta("LAK", "0", "0"))
        assertNull(reports.delta("LAK", "x", "0"))
        assertNull(reports.delta("XXX", "1", "0"))
    }

    // ---- export ----

    @Test fun exportAsksForTheRangeBranchAndFormatThenOffersTheBytes() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val export = ExportState(h.env)
        assertEquals("2026-10-01", export.ui.value.from)
        assertEquals("csv", export.ui.value.format)
        export.setFormat("xlsx")
        export.setFrom("2026-09-01")
        export.setTo("2026-09-30")
        export.setBranch("second")
        h.api.exportFile = ExportFile("shoparchive-2026-09-01_2026-09-30.xlsx", byteArrayOf(5, 6))
        export.export()
        assertEquals(listOf(listOf("2026-09-01", "2026-09-30", "second", "xlsx")), h.api.exports)
        val ready = assertIs<ExportPhase.Ready>(export.ui.value.phase)
        assertEquals(listOf<Byte>(5, 6), ready.file.bytes.toList())
        assertEquals("shoparchive-2026-09-01_2026-09-30.xlsx", ready.file.fileName)
        export.delivered(SaveResult.SAVED)
        assertEquals(SaveResult.SAVED, export.ui.value.saved)
        assertIs<ExportPhase.Ready>(export.ui.value.phase)
    }

    @Test fun exportUsesTheShellsBranchWhenNoneWasPicked() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val export = ExportState(h.env)
        export.export()
        assertEquals("main", h.api.exports.single()[2])
    }

    @Test fun exportWaitsForTheReauthPromptThroughTheCallsWrapper() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        var asked = 0
        val reauthing = object : Calls {
            override suspend fun <T> run(block: suspend () -> T): T = try {
                block()
            } catch (e: ClientError.Api) {
                if (e.code != ErrorCode.REAUTH_REQUIRED) throw e
                asked++
                block()
            }
        }
        var calls = 0
        val failing = object : xyz.felismp.shoparchive.app.client.RecordsApi by h.api {
            override suspend fun export(from: String, to: String, branch: String, format: String): ExportFile {
                if (++calls == 1) throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "Enter your PIN again.")
                return h.api.export(from, to, branch, format)
            }
        }
        val export = ExportState(Env(backgroundScope, failing, reauthing, h.canWrite, h.config, h.branch, h.refetch, h.messages, { h.date }, { h.millis }))
        export.export()
        assertEquals(1, asked)
        assertIs<ExportPhase.Ready>(export.ui.value.phase)
    }

    @Test fun aRefusedExportExplainsAndCanBeTriedAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.exportError = ClientError.Api(403, ErrorCode.FORBIDDEN, "This needs the permission shoparchive.export.", reason = ErrorReasons.PERMISSION_MISSING)
        val export = ExportState(h.env)
        export.export()
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), assertIs<ExportPhase.Failed>(export.ui.value.phase).failure)
        assertTrue(export.ui.value.canExport)
        h.api.exportError = null
        export.export()
        assertIs<ExportPhase.Ready>(export.ui.value.phase)
    }

    @Test fun aCancelledPinPromptOnExportIsReportedInTheAppsWordsAndCanBeTriedAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        h.api.exportError = ClientError.ReauthCancelled()
        val export = ExportState(h.env)
        export.export()
        assertEquals(Failure.Client(Problem.ReauthCancelled), assertIs<ExportPhase.Failed>(export.ui.value.phase).failure)
        assertTrue(export.ui.value.canExport)
    }

    @Test fun exportNeedsAValidRangeAndTheConnection() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope)
        val export = ExportState(h.env)
        export.setTo("nope")
        export.export()
        h.canWrite.value = false
        export.setTo("2026-10-04")
        export.export()
        assertTrue(h.api.exports.isEmpty())
        assertTrue(export.ui.value.blocked)
    }

    @Test fun losingTheDashboardPermissionClearsTheOverviewAndGainingItLoadsIt() = runTest(UnconfinedTestDispatcher()) {
        val (h, reports) = setup()
        assertEquals(2, reports.ui.value.shown.size)
        val asked = h.api.dashboardCalls.size
        h.config.value = ConfigState.Ready(testConfig(permissions = ALL_NODES - xyz.felismp.shoparchive.shared.PermissionNodes.DASHBOARD_VIEW))
        assertNull(reports.ui.value.data)
        assertTrue(reports.ui.value.shown.isEmpty())
        assertFalse(reports.ui.value.loading)
        assertEquals(asked, h.api.dashboardCalls.size)
        h.config.value = ConfigState.Ready(testConfig())
        assertEquals(2, reports.ui.value.shown.size)
        assertEquals(asked + 1, h.api.dashboardCalls.size)
    }

    @Test fun aPersonWhoStartsWithoutTheDashboardGetsItWhenTheConfigGrantsIt() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, config = ConfigState.Ready(testConfig(permissions = emptyList())))
        h.api.dashboard = DashboardDto("2026-10-04", "2026-10-04", listOf(currency("LAK")), emptyList())
        val reports = ReportsState(h.env)
        assertNull(reports.ui.value.data)
        assertTrue(h.api.dashboardCalls.isEmpty())
        h.config.value = ConfigState.Ready(testConfig())
        assertEquals(listOf("LAK"), reports.ui.value.shown.map { it.currency })
    }
}
