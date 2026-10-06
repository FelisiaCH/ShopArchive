package xyz.felismp.shoparchive.server.records

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.deletePath
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.errorReason
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.DashboardBreakdown
import xyz.felismp.shoparchive.shared.DashboardCandle
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardDay
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.DashboardItem
import xyz.felismp.shoparchive.shared.DashboardTotals
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ZONE = ZoneId.of("Asia/Vientiane")
private val EXPONENTS = mapOf("LAK" to 0, "THB" to 2)

private fun lak(method: TenderMethod, minor: Long) = Tender("LAK", method, Amount(minor, 0))
private fun thb(method: TenderMethod, minor: Long) = Tender("THB", method, Amount(minor, 2))

private fun entry(
    id: String, date: String, createdAt: String, type: EntryType, item: String, category: String?, vararg tenders: Tender, branch: String = "main", deleted: Boolean = false,
) = Entry(id, LocalDate.parse(date), type, branch, category, item, "", UserRef("u1", "noy"), createdAt, createdAt, deleted, tenders.toList(), emptyList(), null)

private fun totals(income: String, expense: String, net: String, cashIn: String, cashOut: String, cashNet: String, count: Int) =
    DashboardTotals(income, expense, net, cashIn, cashOut, cashNet, count)

// Two days (27 and 28 September), two branches, LAK (no decimals) and THB (two). Times are +07:00; f is written in UTC (02:20Z is 09:20 there).
private val A = entry("a", "2026-09-27", "2026-09-27T09:10:00+07:00", EntryType.INCOME, "coffee", "drink", lak(TenderMethod.CASH, 100000), lak(TenderMethod.ONLINE, 50000), thb(TenderMethod.CASH, 1000))
private val B = entry("b", "2026-09-27", "2026-09-27T09:40:00+07:00", EntryType.EXPENSE, "ice", null, lak(TenderMethod.CASH, 30000))
private val C = entry("c", "2026-09-27", "2026-09-27T10:15:00+07:00", EntryType.INCOME, "coffee", "drink", lak(TenderMethod.ONLINE, 20000))
private val D = entry("d", "2026-09-27", "2026-09-27T10:30:00+07:00", EntryType.EXPENSE, "milk", "stock", lak(TenderMethod.CASH, 80000))
private val E = entry("e", "2026-09-27", "2026-09-27T10:50:00+07:00", EntryType.INCOME, "tea", "drink", lak(TenderMethod.CASH, 5000))
private val F = entry("f", "2026-09-27", "2026-09-27T02:20:00Z", EntryType.INCOME, "cake", "food", lak(TenderMethod.CASH, 7000), thb(TenderMethod.ONLINE, 250), branch = "market")
private val GONE = entry("g", "2026-09-27", "2026-09-27T11:00:00+07:00", EntryType.INCOME, "coffee", "drink", lak(TenderMethod.CASH, 999999), deleted = true)
private val H = entry("h", "2026-09-28", "2026-09-28T08:00:00+07:00", EntryType.INCOME, "coffee", "drink", lak(TenderMethod.CASH, 40000))
private val I = entry("i", "2026-09-28", "2026-09-28T08:30:00+07:00", EntryType.EXPENSE, "ice", null, lak(TenderMethod.ONLINE, 10000), thb(TenderMethod.CASH, 125))
private val P = entry("p", "2026-09-26", "2026-09-26T10:00:00+07:00", EntryType.INCOME, "coffee", "drink", lak(TenderMethod.CASH, 12000))

private fun of(rows: List<Entry>, before: List<Entry> = emptyList(), from: String = "2026-09-27", to: String = "2026-09-28", currency: String? = null) =
    dashboardOf(rows.filter { !it.deleted }, before, LocalDate.parse(from), LocalDate.parse(to), currency, ZONE, EXPONENTS, emptyList())

private fun DashboardDto.currency(code: String): DashboardCurrency = currencies.single { it.currency == code }

/** The numbers of the dashboard (hand-computed from the fixture above) and the two routes. */
class DashboardTest {
    @TempDir
    lateinit var root: Path

    // --- the numbers ---

    @Test
    fun amountsTooLargeToAddUpAreRefusedInsteadOfWrappingAround() {
        val big = (1..9_300).map { entry("x$it", "2026-09-27", "2026-09-27T09:00:00+07:00", EntryType.INCOME, "gold", null, lak(TenderMethod.CASH, xyz.felismp.shoparchive.shared.MAX_MINOR)) }

        val refused = kotlin.test.assertFailsWith<xyz.felismp.shoparchive.api.ApiError> { of(big) }

        assertEquals(400, refused.status)
    }


    @Test
    fun totalsAndCashNetPerCurrencyAndANeverDeletedEntry() {
        val dash = of(listOf(A, B, C, D, E, F, GONE, H, I), listOf(P))
        assertEquals(listOf("LAK", "THB"), dash.currencies.map { it.currency })
        // income 150000+7000+20000+5000+40000, expense 30000+80000+10000, cash in 100000+7000+5000+40000, cash out 30000+80000
        assertEquals(totals("222000", "120000", "102000", "152000", "110000", "42000", 8), dash.currency("LAK").totals)
        // income 10.00+2.50, expense 1.25, cash in 10.00, cash out 1.25
        assertEquals(totals("12.50", "1.25", "11.25", "10.00", "1.25", "8.75", 3), dash.currency("THB").totals)
    }

    @Test
    fun thePreviousPeriodIsTheSameNumberOfDaysBeforeAndEmptyIsZeroWithDecimals() {
        val dash = of(listOf(A, B, C, D, E, F, H, I), listOf(P))
        assertEquals(totals("12000", "0", "12000", "12000", "0", "12000", 1), dash.currency("LAK").previous)
        assertEquals(totals("0.00", "0.00", "0.00", "0.00", "0.00", "0.00", 0), dash.currency("THB").previous)
    }

    @Test
    fun dailyHasOneRowPerDateWithEntries() {
        val dash = of(listOf(A, B, C, D, E, F, H, I))
        assertEquals(listOf(DashboardDay("2026-09-27", "182000", "110000", "72000"), DashboardDay("2026-09-28", "40000", "10000", "30000")), dash.currency("LAK").daily)
        assertEquals(listOf(DashboardDay("2026-09-27", "12.50", "0.00", "12.50"), DashboardDay("2026-09-28", "0.00", "1.25", "-1.25")), dash.currency("THB").daily)
    }

    @Test
    fun byCategoryHasNoneForNoCategoryAndByMethodSplitsTheTenders() {
        val lak = of(listOf(A, B, C, D, E, F, H, I)).currency("LAK")
        assertEquals(
            listOf(
                DashboardBreakdown("drink", EntryType.INCOME, "215000", 4),
                DashboardBreakdown("food", EntryType.INCOME, "7000", 1),
                DashboardBreakdown("none", EntryType.EXPENSE, "40000", 2),
                DashboardBreakdown("stock", EntryType.EXPENSE, "80000", 1),
            ),
            lak.byCategory,
        )
        assertEquals(
            listOf(
                DashboardBreakdown("cash", EntryType.INCOME, "152000", 4),
                DashboardBreakdown("cash", EntryType.EXPENSE, "110000", 2),
                DashboardBreakdown("online", EntryType.INCOME, "70000", 2),
                DashboardBreakdown("online", EntryType.EXPENSE, "10000", 1),
            ),
            lak.byMethod,
        )
    }

    @Test
    fun hourlyCandlesForOneDayWalkTheEntriesInTimeOrderInTheServersZone() {
        // Net after a, f, b: 150000, 157000, 127000 (hour 9; f is 02:20Z); after c, d, e: 147000, 67000, 72000 (hour 10).
        val candles = of(listOf(A, B, C, D, E, F), from = "2026-09-27", to = "2026-09-27").currency("LAK").candles
        assertEquals(
            listOf(
                DashboardCandle("2026-09-27T09:00", "0", "157000", "0", "127000", "157000"),
                DashboardCandle("2026-09-27T10:00", "127000", "147000", "67000", "72000", "25000"),
            ),
            candles,
        )
    }

    @Test
    fun hourlyCandlesAreForTheConfiguredZoneNotTheOffsetInTheFile() {
        val late = entry("z", "2026-09-27", "2026-09-27T20:30:00-04:00", EntryType.INCOME, "x", null, lak(TenderMethod.CASH, 5))
        assertEquals("2026-09-28T07:00", of(listOf(late), from = "2026-09-27", to = "2026-09-27").currency("LAK").candles.single().start)
    }

    @Test
    fun dailyCandlesForARangeContinueTheRunningNetAndEmptyDaysAreLeftOut() {
        // Day 27 ends at 72000; h makes 112000 and i 102000.
        val candles = of(listOf(A, B, C, D, E, F, H, I)).currency("LAK").candles
        assertEquals(
            listOf(
                DashboardCandle("2026-09-27", "0", "157000", "0", "72000", "182000"),
                DashboardCandle("2026-09-28", "72000", "112000", "72000", "102000", "40000"),
            ),
            candles,
        )
        val gap = entry("j", "2026-09-30", "2026-09-30T09:00:00+07:00", EntryType.EXPENSE, "x", null, lak(TenderMethod.CASH, 500))
        assertEquals(listOf("2026-09-27", "2026-09-28", "2026-09-30"), of(listOf(A, H, gap), from = "2026-09-27", to = "2026-10-01").currency("LAK").candles.map { it.start })
        assertEquals(DashboardCandle("2026-09-30", "190000", "190000", "189500", "189500", "0"), of(listOf(A, H, gap), from = "2026-09-27", to = "2026-10-01").currency("LAK").candles.last())
    }

    @Test
    fun topItemsAreBySumThenNameAndCutAtTenAndExpensesAreSeparate() {
        val lak = of(listOf(A, B, C, D, E, F, H, I)).currency("LAK")
        assertEquals(listOf(DashboardItem("coffee", "210000", 3), DashboardItem("cake", "7000", 1), DashboardItem("tea", "5000", 1)), lak.topItems)
        assertEquals(listOf(DashboardItem("milk", "80000", 1), DashboardItem("ice", "40000", 2)), lak.expenseItems)
        val many = (1..11).map { entry("m$it", "2026-09-27", "2026-09-27T09:%02d:00+07:00".format(it), EntryType.INCOME, "item$it", null, lak(TenderMethod.CASH, it * 100L)) }
        val top = of(many, from = "2026-09-27", to = "2026-09-27").currency("LAK").topItems
        assertEquals((11 downTo 2).map { "item$it" }, top.map { it.item })
        assertEquals("1100", top.first().sum)
    }

    @Test
    fun theCurrencyFilterKeepsOnlyThatCurrency() {
        val dash = of(listOf(A, B, C, D, E, F, H, I), listOf(P), currency = "THB")
        assertEquals(listOf("THB"), dash.currencies.map { it.currency })
        assertEquals(totals("12.50", "1.25", "11.25", "10.00", "1.25", "8.75", 3), dash.currencies.single().totals)
    }

    // --- the routes ---

    private fun env(): AuthEnv = AuthEnv(root, NO_BACKOFF, withRecords = true).also { e ->
        e.records!!.branches.add("main", "Main")
        e.records.branches.add("market", "Market")
        e.records.categories.add("misc", LocalizedName("ອື່ນໆ", "อื่นๆ", "Misc"), AppliesTo.BOTH)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.make(
        token: String, item: String, tenders: List<xyz.felismp.shoparchive.shared.TenderDto>, type: EntryType = EntryType.INCOME, branch: String = "main", category: String? = null,
    ): EntryDto = createEntry(token, newEntry(tenders, type, branch, category, item), if (tenders.any { it.method == TenderMethod.ONLINE }) listOf(jpeg()) else emptyList()).also { check(it.status.value == 201) { "${it.status} ${it.bodyAsText()}" } }.parsed(EntryDto.serializer())

    @Test
    fun theRouteNeedsTheNodeAndGoodDatesAndAUserGetsOnlyTheirBranchesBranchLevel() = env().run {
        val plain = login("plain")
        val noy = login("noy", grant = listOf(DASHBOARD_VIEW_NODE))
        val mali = login("mali")
        val boss = login("boss", branches = listOf("main", "market"), grant = listOf(DASHBOARD_VIEW_NODE))
        api {
            openDay(noy)
            openDay(boss, "market")
            val tea = make(mali, "tea", listOf(cash("LAK", "1000")))
            clock.advance(Duration.ofSeconds(10))
            make(noy, "ice", listOf(online("LAK", "400")), EntryType.EXPENSE)
            clock.advance(Duration.ofSeconds(10))
            val gone = make(noy, "gone", listOf(cash("LAK", "9999")))
            assertEquals(HttpStatusCode.OK, deletePath("/api/v1/entries/${gone.date}/${gone.id}", noy).status)
            clock.advance(Duration.ofSeconds(10))
            make(boss, "cake", listOf(cash("LAK", "5000"), cash("THB", "5.00")), branch = "market")
            val q = "?from=${tea.date}&to=${tea.date}"

            assertEquals(ErrorCode.FORBIDDEN, getPath("/api/v1/dashboard$q", plain).errorCode())
            assertEquals(xyz.felismp.shoparchive.shared.ErrorReasons.PERMISSION_MISSING, getPath("/api/v1/dashboard$q", plain).errorReason())
            for (bad in listOf("", "?from=${tea.date}", "?to=${tea.date}", "?from=${tea.date}&to=2020-01-01", "?from=x&to=${tea.date}", "?from=2020-01-01&to=${tea.date}")) {
                assertEquals(HttpStatusCode.BadRequest, getPath("/api/v1/dashboard$bad", noy).status, bad)
            }

            // noy works in main only and has no view.all, yet the numbers count mali's tea: income 1000, expense 400 (the deleted entry and the market are out).
            val mine = getPath("/api/v1/dashboard$q", noy).parsed(DashboardDto.serializer())
            assertEquals(listOf("LAK"), mine.currencies.map { it.currency })
            assertEquals(totals("1000", "400", "600", "1000", "0", "1000", 2), mine.currencies.single().totals)
            assertEquals(listOf("ice", "tea"), mine.recent.map { it.item })
            assertEquals(HttpStatusCode.Forbidden, getPath("/api/v1/dashboard$q&branch=market", noy).status)
            assertEquals(ErrorCode.FORBIDDEN, getPath("/api/v1/dashboard$q&branch=market", noy).errorCode())

            val all = getPath("/api/v1/dashboard$q", boss).parsed(DashboardDto.serializer())
            assertEquals(totals("6000", "400", "5600", "6000", "0", "6000", 3), all.currency("LAK").totals)
            assertEquals(totals("5.00", "0.00", "5.00", "5.00", "0.00", "5.00", 1), all.currency("THB").totals)
            assertEquals(listOf("cake", "ice", "tea"), all.recent.map { it.item })
            val market = getPath("/api/v1/dashboard$q&branch=market", boss).parsed(DashboardDto.serializer())
            assertEquals(totals("5000", "0", "5000", "5000", "0", "5000", 1), market.currency("LAK").totals)
            val thbOnly = getPath("/api/v1/dashboard$q&currency=THB", boss).parsed(DashboardDto.serializer())
            assertEquals(listOf("THB"), thbOnly.currencies.map { it.currency })
            assertEquals(listOf("cake"), thbOnly.recent.map { it.item })
        }
    }

    @Test
    fun recentItemsAreDistinctMostRecentFirstFilteredByCategoryAndOnlyOfWhatTheCallerMayList() = env().run {
        val noy = login("noy")
        val mali = login("mali")
        val boss = login("boss", grant = listOf(ENTRY_VIEW_ALL_NODE))
        api {
            openDay(noy)
            make(noy, "coffee", listOf(cash("LAK", "1")), category = "misc")
            clock.advance(Duration.ofSeconds(10))
            make(noy, "tea", listOf(cash("LAK", "1")))
            clock.advance(Duration.ofSeconds(10))
            make(noy, "coffee", listOf(cash("LAK", "1")), category = "misc")
            clock.advance(Duration.ofSeconds(10))
            make(noy, "ice", listOf(cash("LAK", "1")), EntryType.EXPENSE)
            clock.advance(Duration.ofSeconds(10))
            make(mali, "hers", listOf(cash("LAK", "1")))
            assertEquals(listOf("ice", "coffee", "tea"), getPath("/api/v1/items/recent", noy).parsed(ListSerializer(String.serializer())))
            assertEquals(listOf("coffee"), getPath("/api/v1/items/recent?category=misc", noy).parsed(ListSerializer(String.serializer())))
            assertEquals(listOf("hers", "ice", "coffee", "tea"), getPath("/api/v1/items/recent", boss).parsed(ListSerializer(String.serializer())))
            assertTrue(getPath("/api/v1/items/recent?category=none-such", noy).parsed(ListSerializer(String.serializer())).isEmpty())
        }
    }
}
