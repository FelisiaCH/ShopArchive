package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.ExportPhase
import xyz.felismp.shoparchive.app.flow.RangeProblem
import xyz.felismp.shoparchive.app.flow.ReportRange
import xyz.felismp.shoparchive.app.flow.ReportsTab
import xyz.felismp.shoparchive.app.flow.ReportsUi
import xyz.felismp.shoparchive.app.flow.SaveResult
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.groupThousands
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.DashboardBreakdown
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.DashboardItem
import xyz.felismp.shoparchive.shared.EntryType

@Composable
fun ReportsScreen(ws: Workspace) {
    val chosen by ws.reportsTab.collectAsState()
    val can by ws.capabilities.collectAsState()
    // Only the tabs the person may use; if the chosen one is not among them (rights changed), the first is shown.
    val tabs = can.reportsTabs
    val tab = chosen.takeIf { it in tabs } ?: tabs.firstOrNull() ?: return
    ShopPage(stringResource(Res.string.nav_reports)) {
        ShopSegmented(
            tabs.map { it to stringResource(when (it) {
                ReportsTab.OVERVIEW -> Res.string.reports_overview
                ReportsTab.CLOSE_DAY -> Res.string.closeday_btn
                ReportsTab.EXPORT -> Res.string.reports_export
                ReportsTab.NOTIFICATIONS -> Res.string.reports_notifications
            }) },
            tab, ws::openReports,
        )
        when (tab) {
            ReportsTab.OVERVIEW -> Overview(ws)
            ReportsTab.CLOSE_DAY -> CloseDaySection(ws)
            ReportsTab.EXPORT -> ExportSection(ws)
            ReportsTab.NOTIFICATIONS -> NotificationsSection(ws)
        }
    }
}

@Composable
private fun RangeProblem.text(): String = stringResource(
    when (this) {
        RangeProblem.NOT_A_DATE -> Res.string.range_not_a_date
        RangeProblem.BACKWARDS -> Res.string.range_backwards
        RangeProblem.TOO_LONG -> Res.string.range_too_long
    },
)

// ---- overview ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(ws: Workspace) {
    val ui by ws.reports.ui.collectAsState()
    val config by ws.config.collectAsState()
    val currencies = (config as? ConfigState.Ready)?.config?.currencies.orEmpty()
    val r = ws.reports
    ShopSegmented(
        listOf(
            ReportRange.TODAY to stringResource(Res.string.range_today), ReportRange.WEEK to stringResource(Res.string.range_week),
            ReportRange.MONTH to stringResource(Res.string.range_month), ReportRange.CUSTOM to stringResource(Res.string.range_custom),
        ),
        ui.range, r::selectRange,
    )
    if (ui.range == ReportRange.CUSTOM) {
        ShopDateField(ui.from, r::setFrom, stringResource(Res.string.filter_from))
        ShopDateField(ui.to, r::setTo, stringResource(Res.string.filter_to))
    } else {
        ShopText(ui.from + " – " + ui.to, TextRole.Caption, muted = true)
    }
    ui.rangeProblem?.let { ShopText(it.text(), TextRole.Caption) }
    if (ws.branches.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ws.branches.forEach { b -> ShopChoice(b.displayName, ui.branch == b.key, { r.setBranch(b.key) }) }
    }
    if (currencies.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopChoice(stringResource(Res.string.filter_all), ui.currency == null, { r.setCurrency(null) })
        currencies.forEach { c -> ShopChoice(c.code, ui.currency == c.code, { r.setCurrency(c.code) }) }
    }
    ui.failure?.let {
        ShopBanner(stringResource(Res.string.reports_failed) + " " + it.text(), Tone.Error)
        ShopButton(stringResource(Res.string.retry), r::reload)
    }
    when {
        ui.rangeProblem != null || ui.failure != null -> Unit
        ui.data == null -> ShopText(stringResource(Res.string.working), muted = true)
        ui.shown.isEmpty() -> ShopText(stringResource(Res.string.reports_empty), muted = true)
        else -> {
            if (ui.loading) ShopText(stringResource(Res.string.working), TextRole.Caption, muted = true)
            val exponents = currencies.associate { it.code to it.exponent }
            ui.shown.forEach { CurrencyReport(ws, it, exponents[it.currency] ?: 0) }
        }
    }
}

@Composable
private fun CurrencyReport(ws: Workspace, c: DashboardCurrency, exponent: Int) {
    val code = c.currency
    fun delta(now: String, before: String): String =
        ws.reports.delta(code, now, before)?.let { signedAmount(it, exponent) } ?: "—"
    val t = c.totals
    val p = c.previous
    fun total(label: String, now: String, before: String, flow: MoneyFlow = MoneyFlow.Neutral) =
        TableRow(listOf(label, groupThousands(now), groupThousands(before), delta(now, before)), flow)
    ShopText(code, TextRole.Title)
    ShopCard {
        ShopTable(
            listOf("", stringResource(Res.string.reports_this), stringResource(Res.string.reports_before), stringResource(Res.string.reports_change)),
            listOf(
                total(stringResource(Res.string.today_in), t.income, p.income, MoneyFlow.In),
                total(stringResource(Res.string.today_out), t.expense, p.expense, MoneyFlow.Out),
                total(stringResource(Res.string.today_net), t.net, p.net),
                total(stringResource(Res.string.today_cash), t.cashNet, p.cashNet),
                TableRow(listOf(stringResource(Res.string.reports_entries), t.count.toString(), p.count.toString(), (t.count - p.count).let { (if (it > 0) "+" else "") + it })),
            ),
        )
    }
    Section(Res.string.reports_daily, c.daily.isNotEmpty()) {
        ShopTable(
            listOf(stringResource(Res.string.reports_date), stringResource(Res.string.today_in), stringResource(Res.string.today_out), stringResource(Res.string.today_net)),
            c.daily.map { TableRow(listOf(it.date, groupThousands(it.income), groupThousands(it.expense), groupThousands(it.net))) },
        )
    }
    Section(Res.string.reports_by_category, c.byCategory.isNotEmpty()) { Breakdown(c.byCategory) { if (it == "none") stringResource(Res.string.reports_no_category) else ws.categoryName(it) ?: it } }
    Section(Res.string.reports_by_method, c.byMethod.isNotEmpty()) {
        Breakdown(c.byMethod) { stringResource(if (it == "online") Res.string.method_online else Res.string.method_cash) }
    }
    Section(Res.string.reports_candles, c.candles.isNotEmpty()) {
        ShopTable(
            listOf(
                stringResource(Res.string.reports_time), stringResource(Res.string.reports_open), stringResource(Res.string.reports_high),
                stringResource(Res.string.reports_low), stringResource(Res.string.reports_close), stringResource(Res.string.reports_money_in),
            ),
            c.candles.map {
                TableRow(listOf(it.start.replace('T', ' '), groupThousands(it.open), groupThousands(it.high), groupThousands(it.low), groupThousands(it.close), groupThousands(it.moneyIn)))
            },
        )
    }
    Section(Res.string.reports_top_items, c.topItems.isNotEmpty()) { Items(c.topItems) }
    Section(Res.string.reports_expense_items, c.expenseItems.isNotEmpty()) { Items(c.expenseItems) }
}

@Composable
private fun Section(title: org.jetbrains.compose.resources.StringResource, show: Boolean, rows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (!show) return
    ShopText(stringResource(title), TextRole.Title)
    ShopCard(content = rows)
}

/** The sum of a row in the color of its type, with the sign as in History: green and + came in, red and - went out. */
@Composable
private fun Breakdown(rows: List<DashboardBreakdown>, name: @Composable (String) -> String) {
    ShopTable(
        listOf(stringResource(Res.string.category_label), stringResource(Res.string.reports_sum), stringResource(Res.string.reports_entries)),
        rows.map {
            val income = it.type == EntryType.INCOME
            TableRow(listOf(name(it.key), (if (income) "+" else "-") + groupThousands(it.sum), it.count.toString()), if (income) MoneyFlow.In else MoneyFlow.Out, colored = 1)
        },
        wrapsName = true,
    )
}

@Composable
private fun Items(rows: List<DashboardItem>) {
    ShopTable(
        listOf(stringResource(Res.string.item_label), stringResource(Res.string.reports_sum), stringResource(Res.string.reports_entries)),
        rows.map { TableRow(listOf(it.item.ifBlank { "—" }, groupThousands(it.sum), it.count.toString())) },
        wrapsName = true,
    )
}

// ---- export ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportSection(ws: Workspace) {
    val ui by ws.export.ui.collectAsState()
    val x = ws.export
    val saver = rememberFileSaver(x::delivered)
    val phase = ui.phase
    // The file is ready: ask where to keep it.
    LaunchedEffect(phase) {
        if (phase is ExportPhase.Ready) saver.save(phase.file.fileName, if (phase.file.fileName.endsWith(".xlsx")) XLSX_MIME else CSV_MIME, phase.file.bytes)
    }
    ShopText(stringResource(Res.string.export_hint), muted = true)
    ShopDateField(ui.from, x::setFrom, stringResource(Res.string.filter_from))
    ShopDateField(ui.to, x::setTo, stringResource(Res.string.filter_to))
    ui.rangeProblem?.let { ShopText(it.text(), TextRole.Caption) }
    if (ws.branches.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ws.branches.forEach { b -> ShopChoice(b.displayName, ui.branch == b.key, { x.setBranch(b.key) }) }
    }
    ShopSegmented(listOf("csv" to "CSV", "xlsx" to "XLSX"), ui.format, x::setFormat)
    when (phase) {
        ExportPhase.Working -> ShopBanner(stringResource(Res.string.export_working), Tone.Info)
        is ExportPhase.Failed -> ShopBanner(stringResource(Res.string.export_failed) + " " + phase.failure.text(), Tone.Error)
        is ExportPhase.Ready -> {
            when (ui.saved) {
                SaveResult.SAVED -> ShopBanner(stringResource(Res.string.export_saved, phase.file.fileName), Tone.Success)
                SaveResult.FAILED -> ShopBanner(stringResource(Res.string.export_write_failed), Tone.Error)
                SaveResult.CANCELLED, null -> ShopBanner(stringResource(Res.string.export_ready, phase.file.fileName), Tone.Info)
            }
            ShopButton(stringResource(Res.string.export_save), { saver.save(phase.file.fileName, if (phase.file.fileName.endsWith(".xlsx")) XLSX_MIME else CSV_MIME, phase.file.bytes) }, primary = false)
        }
        ExportPhase.Idle -> Unit
    }
    if (ui.blocked) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
    ShopButton(stringResource(Res.string.export_make), x::export, Modifier.fillWidthOnPhone(), enabled = ui.canExport)
}
