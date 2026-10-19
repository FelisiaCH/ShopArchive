package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.DayStatus
import xyz.felismp.shoparchive.app.flow.Capabilities
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.ReportsTab
import xyz.felismp.shoparchive.app.flow.TodayData
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.groupThousands
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.DashboardCurrency
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType

@Composable
fun TodayScreen(ws: Workspace) {
    val ui by ws.today.ui.collectAsState()
    val canWrite by ws.canWrite.collectAsState()
    val can by ws.capabilities.collectAsState()
    ShopPage(stringResource(Res.string.today_title)) {
        val data = ui.data
        when {
            data == null && ui.failure != null -> {
                ShopBanner(stringResource(Res.string.today_failed) + " " + ui.failure!!.text(), Tone.Error)
                ShopButton(stringResource(Res.string.retry), ws.today::reload)
            }
            data == null -> ShopText(stringResource(Res.string.working), muted = true)
            else -> {
                ui.failure?.let { ShopBanner(stringResource(Res.string.today_failed) + " " + it.text(), Tone.Error) }
                TodayContent(ws, data, canWrite, can, { ws.go(Destination.OPEN_DAY) }, { ws.openReports(ReportsTab.CLOSE_DAY) }, { ws.go(Destination.HISTORY) }, { ws.openNotes(data.oldestUnreadNote ?: data.date) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayContent(ws: Workspace, data: TodayData, canWrite: Boolean, can: Capabilities, openDay: () -> Unit, closeDay: () -> Unit, openHistory: () -> Unit, openNotes: () -> Unit) {
    when (val status = data.status) {
        DayStatus.NotOpened -> ShopCard {
            ShopText(stringResource(Res.string.openday_card_title), TextRole.Title)
            if (can.openDay) {
                ShopText(stringResource(Res.string.openday_card_body), muted = true)
                if (!canWrite) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
                ShopButton(stringResource(Res.string.openday_btn), openDay, enabled = canWrite, icon = ShopIcon.OpenDay)
            } else {
                ShopText(stringResource(Res.string.openday_card_body_other), muted = true)
            }
        }
        is DayStatus.Open -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                ShopChip(stringResource(Res.string.day_open_by, clockTime(status.session.openedAt), status.session.openedBy.name), Tone.Success)
                if (can.closeDay) ShopButton(stringResource(Res.string.closeday_btn), closeDay, primary = data.staleFrom != null, icon = ShopIcon.CloseDay)
            }
            data.staleFrom?.let { ShopBanner(stringResource(if (can.closeDay) Res.string.day_stale else Res.string.day_stale_other, it), Tone.Error) }
        }
        is DayStatus.Closed -> ShopChip(stringResource(Res.string.day_closed), Tone.Info)
    }
    TodayCounts(data, can, openHistory, openNotes)
    if (!data.showStats) {
        data.statsFailure?.let { ShopBanner(stringResource(Res.string.today_mine_failed) + " " + it.text(), Tone.Error) }
        data.mine?.let { mine ->
            ShopText(stringResource(Res.string.today_mine), TextRole.Title)
            if (mine.isEmpty()) ShopText(stringResource(Res.string.today_empty), muted = true) else ShopList(mine) { RecentLine(ws, it) }
        }
        return
    }
    data.statsFailure?.let { ShopBanner(stringResource(Res.string.today_stats_failed) + " " + it.text(), Tone.Error) }
    data.currencies?.filter { it.totals.count > 0 }?.let { shown -> CardGrid(shown) { CurrencyCard(it) } }
    if (data.recent.isEmpty()) {
        if (data.statsFailure == null) ShopText(stringResource(Res.string.today_empty), muted = true)
    } else {
        ShopText(stringResource(Res.string.today_latest), TextRole.Title)
        ShopList(data.recent.take(10)) { RecentLine(ws, it) }
    }
}

/** The slips attached today (with the dashboard; opens History) and the notes for the owner that are unread (with the read node; opens Notes). */
@Composable
private fun TodayCounts(data: TodayData, can: Capabilities, openHistory: () -> Unit, openNotes: () -> Unit) {
    val unread = data.unreadNotes
    if (!data.showStats && unread == null) return
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (data.showStats) {
            StatTile(stringResource(Res.string.today_slips), data.slips.toString(), Modifier.weight(1f), onClick = if (can.viewEntries) openHistory else null)
        }
        if (unread != null) {
            StatTile(stringResource(Res.string.today_notes), unread.toString(), Modifier.weight(1f), caption = stringResource(Res.string.today_notes_unread), onClick = openNotes)
        }
    }
}

/** One currency of the day: the net total first and large, money in green and out in red, the cash and online figures quieter. */
@Composable
internal fun CurrencyCard(c: DashboardCurrency) {
    val code = c.currency
    fun money(amount: String) = groupThousands(amount) + " " + code
    ShopCard {
        ShopText(code, TextRole.Title)
        AmountRow(stringResource(Res.string.today_in), money(c.totals.income), MoneyFlow.In)
        AmountRow(stringResource(Res.string.today_out), money(c.totals.expense), MoneyFlow.Out)
        ShopDivider(Modifier.padding(vertical = 4.dp))
        AmountRow(stringResource(Res.string.today_net), money(c.totals.net), strong = true)
        AmountRow(stringResource(Res.string.today_cash), money(c.totals.cashNet), muted = true)
        val online = { type: EntryType -> c.byMethod.firstOrNull { it.key == "online" && it.type == type }?.sum }
        if (online(EntryType.INCOME) != null || online(EntryType.EXPENSE) != null) {
            AmountRow(
                stringResource(Res.string.today_online),
                groupThousands(online(EntryType.INCOME) ?: "0") + " / " + groupThousands(online(EntryType.EXPENSE) ?: "0") + " " + code,
                muted = true,
            )
        }
    }
}

@Composable
private fun RecentLine(ws: Workspace, e: EntryDto) {
    val sign = if (e.type == EntryType.INCOME) "+" else "-"
    val flow = if (e.type == EntryType.INCOME) MoneyFlow.In else MoneyFlow.Out
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ShopText(e.item.ifBlank { ws.categoryName(e.category) ?: "—" })
            ShopText(clockTime(e.createdAt) + " · " + e.createdBy.name, TextRole.Caption, muted = true)
        }
        Column(Modifier.weight(1f)) {
            e.tenders.forEach { t -> AmountText(sign + groupThousands(t.amount) + " " + t.currency, flow, Modifier.fillMaxWidth()) }
        }
    }
}
