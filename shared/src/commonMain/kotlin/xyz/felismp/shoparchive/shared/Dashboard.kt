package xyz.felismp.shoparchive.shared

import kotlinx.serialization.Serializable

// --- `GET /api/v1/dashboard`: every amount is a decimal string with the currency's decimals ---

/** The money of a period in one currency; [net] is [income] minus [expense], [cashNet] the same for cash tenders only. [count] is the number of entries. */
@Serializable
data class DashboardTotals(
    val income: String,
    val expense: String,
    val net: String,
    val cashIn: String,
    val cashOut: String,
    val cashNet: String,
    val count: Int,
)

@Serializable
data class DashboardDay(val date: String, val income: String, val expense: String, val net: String)

/** A sum and the number of entries behind it; [key] is a category key ("none" without one) or a tender method. */
@Serializable
data class DashboardBreakdown(val key: String, val type: EntryType, val sum: String, val count: Int)

@Serializable
data class DashboardItem(val item: String, val sum: String, val count: Int)

/** One bar of the running net: [start] is `yyyy-MM-dd'T'HH:mm` for an hour or `yyyy-MM-dd` for a day; [moneyIn] is the income inside it. */
@Serializable
data class DashboardCandle(val start: String, val open: String, val high: String, val low: String, val close: String, val moneyIn: String)

@Serializable
data class DashboardCurrency(
    val currency: String,
    val totals: DashboardTotals,
    /** The same totals for the period just before, as long as this one. */
    val previous: DashboardTotals,
    val daily: List<DashboardDay>,
    val byCategory: List<DashboardBreakdown>,
    val byMethod: List<DashboardBreakdown>,
    val candles: List<DashboardCandle>,
    val topItems: List<DashboardItem>,
    val expenseItems: List<DashboardItem>,
)

@Serializable
data class DashboardDto(val from: String, val to: String, val currencies: List<DashboardCurrency>, val recent: List<EntryDto>)
