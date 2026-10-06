package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.DashboardService
import xyz.felismp.shoparchive.api.EntryQuery
import xyz.felismp.shoparchive.api.EntryService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.net.require
import xyz.felismp.shoparchive.shared.DashboardBreakdown
import xyz.felismp.shoparchive.shared.DashboardCandle
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardDay
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.DashboardItem
import xyz.felismp.shoparchive.shared.DashboardTotals
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.formatAmount
import xyz.felismp.shoparchive.shared.wire
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val TOP_ITEMS = 10
private const val RECENT_ENTRIES = 10
private const val RECENT_ITEMS = 20

/** The entry's tenders in one currency: what that currency of the entry adds to the numbers. */
private class Line(val entry: Entry, val tenders: List<Tender>) {
    val type get() = entry.type
    val minor get() = tenders.sumOf { it.amount.minor }

    /** The change to the running net: income adds, expense takes away. */
    val net get() = if (type == EntryType.INCOME) minor else -minor
}

private fun Collection<Line>.totals(exponent: Int): DashboardTotals {
    var income = 0L
    var expense = 0L
    var cashIn = 0L
    var cashOut = 0L
    for (line in this) for (t in line.tenders) {
        val cash = t.method == TenderMethod.CASH
        if (line.type == EntryType.INCOME) { income += t.amount.minor; if (cash) cashIn += t.amount.minor }
        else { expense += t.amount.minor; if (cash) cashOut += t.amount.minor }
    }
    fun text(minor: Long) = formatAmount(minor, exponent)
    return DashboardTotals(text(income), text(expense), text(income - expense), text(cashIn), text(cashOut), text(cashIn - cashOut), size)
}

/** [entries] by currency, in the order the entries are in; an entry with tenders in two currencies is a line in each. */
private fun lines(entries: List<Entry>, currency: String?): Map<String, List<Line>> =
    entries.flatMap { e -> e.tenders.groupBy { it.currency }.filterKeys { currency == null || it == currency }.map { (c, ts) -> c to Line(e, ts) } }
        .groupBy({ it.first }, { it.second })

private fun items(lines: List<Line>, type: EntryType, exponent: Int) =
    lines.filter { it.type == type }.groupBy { it.entry.item }
        .map { (item, group) -> Triple(item, group.sumOf { it.minor }, group.size) }
        .sortedWith(compareBy({ -it.second }, { it.first }))
        .take(TOP_ITEMS).map { (item, sum, count) -> DashboardItem(item, formatAmount(sum, exponent), count) }

/**
 * The candles of the running net: from 0 at the start of the range, one bar per hour of [zone] when it is a single day, else per business date.
 * [lines] must be in time order. A bar opens where the one before closed; high and low are the peaks of the net after each entry (and the open).
 */
private fun candles(lines: List<Line>, hourly: Boolean, zone: ZoneId, exponent: Int): List<DashboardCandle> {
    fun text(minor: Long) = formatAmount(minor, exponent)
    val buckets = lines.groupBy {
        if (hourly) OffsetDateTime.parse(it.entry.createdAt).atZoneSameInstant(zone).toLocalDateTime().truncatedTo(ChronoUnit.HOURS).toString() else it.entry.date.toString()
    }
    var running = 0L
    return buckets.map { (start, group) ->
        val open = running
        var high = open
        var low = open
        for (line in group) { running += line.net; high = maxOf(high, running); low = minOf(low, running) }
        DashboardCandle(start, text(open), text(high), text(low), text(running), text(group.filter { it.type == EntryType.INCOME }.sumOf { it.minor }))
    }
}

/**
 * The dashboard of [rows] (live entries of the range, already in scope) against [before] (the period just before): per currency, as the spec of `GET /api/v1/dashboard` says.
 * Pure: the same entries give the same numbers.
 */
internal fun dashboardOf(
    rows: List<Entry>, before: List<Entry>, from: LocalDate, to: LocalDate, currency: String?, zone: ZoneId, exponents: Map<String, Int>, recent: List<EntryDto>,
): DashboardDto {
    // Amounts are positive, so no sum below can be larger than all of them added up: checking that one per currency covers every sum here.
    for (list in listOf(rows, before)) list.flatMap { it.tenders }.groupBy { it.currency }.forEach { (code, tenders) ->
        try {
            tenders.fold(0L) { sum, t -> Math.addExact(sum, t.amount.minor) }
        } catch (e: ArithmeticException) {
            throw badRequest("The $code amounts of this range are too large to add up. Choose a shorter range.")
        }
    }
    val ordered = rows.sortedWith(compareBy({ it.date }, { OffsetDateTime.parse(it.createdAt).toInstant() }, { it.id }))
    val previous = lines(before, currency)
    val currencies = lines(ordered, currency).toSortedMap().map { (code, list) ->
        val exponent = exponents[code] ?: 0
        fun text(minor: Long) = formatAmount(minor, exponent)
        DashboardCurrency(
            currency = code,
            totals = list.totals(exponent),
            previous = previous[code].orEmpty().totals(exponent),
            daily = list.groupBy { it.entry.date }.toSortedMap().map { (date, day) ->
                val t = day.totals(exponent)
                DashboardDay(date.toString(), t.income, t.expense, t.net)
            },
            byCategory = list.groupBy { (it.entry.category ?: "none") to it.type }.toSortedMap(compareBy({ it.first }, { it.second }))
                .map { (key, group) -> DashboardBreakdown(key.first, key.second, text(group.sumOf { it.minor }), group.size) },
            // An entry counts once in a method even if it has two tenders of it.
            byMethod = list.flatMap { l -> l.tenders.groupBy { it.method }.map { (m, ts) -> Triple(m.wire(), l.type, ts.sumOf { it.amount.minor }) } }
                .groupBy { it.first to it.second }.toSortedMap(compareBy({ it.first }, { it.second }))
                .map { (key, group) -> DashboardBreakdown(key.first, key.second, text(group.sumOf { it.third }), group.size) },
            candles = candles(list, from == to, zone, exponent),
            topItems = items(list, EntryType.INCOME, exponent),
            expenseItems = items(list, EntryType.EXPENSE, exponent),
        )
    }
    return DashboardDto(from.toString(), to.toString(), currencies, recent)
}

/** `GET /api/v1/dashboard` and the recent items: worked out per request from the entries the [RecordStore] holds, nothing kept in between. */
internal class DefaultDashboardService(
    private val config: ConfigService,
    private val store: RecordStore,
    private val access: Access,
    private val services: ServiceRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : DashboardService {
    override fun dashboard(principal: Principal, from: String?, to: String?, branch: String?, currency: String?): DashboardDto {
        access.require(principal, DASHBOARD_VIEW_NODE)
        if (from == null || to == null) throw badRequest("from and to are needed, like 2026-09-27.")
        val (first, last) = dateRange(from, to)
        branch?.let { access.requireBranch(principal, it) }
        val inScope = HashMap<String, Boolean>()
        // Branch-level on purpose: the own-or-all view of entries does not narrow the numbers.
        val visible = store.all().filter { !it.deleted && (branch == null || it.branch == branch) && inScope.getOrPut(it.branch) { access.canBranch(principal, it.branch) } }
        val days = ChronoUnit.DAYS.between(first, last) + 1
        val rows = visible.filter { it.date in first..last }
        val before = visible.filter { it.date in first.minusDays(days)..first.minusDays(1) }
        val recent = rows.filter { currency == null || it.tenders.any { t -> t.currency == currency } }
            .sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { OffsetDateTime.parse(it.createdAt).toInstant() }.thenByDescending { it.id })
            .take(RECENT_ENTRIES).map { it.toDto() }
        return dashboardOf(rows, before, first, last, currency, config.timezone, config.currencies.associate { it.code to it.exponent }, recent)
    }

    override fun recentItems(principal: Principal, category: String?): List<String> {
        val today = businessToday(clock, config)
        // The widest range a list answers for, ending today.
        val query = EntryQuery(today.minusDays(365).toString(), today.toString(), null, null, null, category, includeDeleted = false)
        return services.require<EntryService>().list(principal, query).asReversed().map { it.item }.distinct().take(RECENT_ITEMS)
    }
}
