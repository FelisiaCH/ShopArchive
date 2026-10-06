package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
        ShopTextField(ui.from, r::setFrom, stringResource(Res.string.filter_from))
        ShopTextField(ui.to, r::setTo, stringResource(Res.string.filter_to))
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
    fun money(amount: String) = groupThousands(amount) + " " + code
    fun delta(now: String, before: String): String =
        ws.reports.delta(code, now, before)?.let { signedAmount(it, exponent) } ?: "—"
    ShopText(code, TextRole.Title)
    ShopCard {
        TableLine(listOf("", stringResource(Res.string.reports_this), stringResource(Res.string.reports_before), stringResource(Res.string.reports_change)), header = true)
        val t = c.totals
        val p = c.previous
        TableLine(listOf(stringResource(Res.string.today_in), money(t.income), money(p.income), delta(t.income, p.income)))
        TableLine(listOf(stringResource(Res.string.today_out), money(t.expense), money(p.expense), delta(t.expense, p.expense)))
        TableLine(listOf(stringResource(Res.string.today_net), money(t.net), money(p.net), delta(t.net, p.net)))
        TableLine(listOf(stringResource(Res.string.today_cash), money(t.cashNet), money(p.cashNet), delta(t.cashNet, p.cashNet)))
        TableLine(listOf(stringResource(Res.string.reports_entries), t.count.toString(), p.count.toString(), (t.count - p.count).let { (if (it > 0) "+" else "") + it }))
    }
    Section(Res.string.reports_daily, c.daily.isNotEmpty()) {
        TableLine(listOf(stringResource(Res.string.reports_date), stringResource(Res.string.today_in), stringResource(Res.string.today_out), stringResource(Res.string.today_net)), header = true)
        c.daily.forEach { TableLine(listOf(it.date, groupThousands(it.income), groupThousands(it.expense), groupThousands(it.net))) }
    }
    Section(Res.string.reports_by_category, c.byCategory.isNotEmpty()) { Breakdown(c.byCategory) { if (it == "none") stringResource(Res.string.reports_no_category) else ws.categoryName(it) ?: it } }
    Section(Res.string.reports_by_method, c.byMethod.isNotEmpty()) {
        Breakdown(c.byMethod) { stringResource(if (it == "online") Res.string.method_online else Res.string.method_cash) }
    }
    Section(Res.string.reports_candles, c.candles.isNotEmpty()) {
        TableLine(
            listOf(
                stringResource(Res.string.reports_time), stringResource(Res.string.reports_open), stringResource(Res.string.reports_high),
                stringResource(Res.string.reports_low), stringResource(Res.string.reports_close), stringResource(Res.string.reports_money_in),
            ),
            header = true,
        )
        c.candles.forEach {
            TableLine(listOf(it.start.replace('T', ' '), groupThousands(it.open), groupThousands(it.high), groupThousands(it.low), groupThousands(it.close), groupThousands(it.moneyIn)))
        }
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

@Composable
private fun Breakdown(rows: List<DashboardBreakdown>, name: @Composable (String) -> String) {
    TableLine(listOf(stringResource(Res.string.category_label), stringResource(Res.string.reports_sum), stringResource(Res.string.reports_entries)), header = true)
    rows.forEach {
        val sign = if (it.type == EntryType.INCOME) "+" else "-"
        TableLine(listOf(name(it.key) + " " + sign, groupThousands(it.sum), it.count.toString()))
    }
}

@Composable
private fun Items(rows: List<DashboardItem>) {
    TableLine(listOf(stringResource(Res.string.item_label), stringResource(Res.string.reports_sum), stringResource(Res.string.reports_entries)), header = true)
    rows.forEach { TableLine(listOf(it.item.ifBlank { "—" }, groupThousands(it.sum), it.count.toString())) }
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
    ShopTextField(ui.from, x::setFrom, stringResource(Res.string.filter_from))
    ShopTextField(ui.to, x::setTo, stringResource(Res.string.filter_to))
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
    ShopButton(stringResource(Res.string.export_make), x::export, Modifier.fillMaxWidth(), enabled = ui.canExport)
}
