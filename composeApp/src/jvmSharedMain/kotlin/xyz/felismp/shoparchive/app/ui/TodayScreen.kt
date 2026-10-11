package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
                TodayContent(data, canWrite, can, { ws.go(Destination.OPEN_DAY) }, { ws.openReports(ReportsTab.CLOSE_DAY) })
            }
        }
    }
}

@Composable
private fun TodayContent(data: TodayData, canWrite: Boolean, can: Capabilities, openDay: () -> Unit, closeDay: () -> Unit) {
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
            ShopChip(stringResource(Res.string.day_open_by, clockTime(status.session.openedAt), status.session.openedBy.name), Tone.Success)
            data.staleFrom?.let { ShopBanner(stringResource(if (can.closeDay) Res.string.day_stale else Res.string.day_stale_other, it), Tone.Error) }
            if (can.closeDay) ShopButton(stringResource(Res.string.closeday_btn), closeDay, primary = data.staleFrom != null, icon = ShopIcon.CloseDay)
        }
        is DayStatus.Closed -> ShopChip(stringResource(Res.string.day_closed), Tone.Info)
    }
    if (!data.showStats) return
    data.statsFailure?.let { ShopBanner(stringResource(Res.string.today_stats_failed) + " " + it.text(), Tone.Error) }
    data.currencies?.filter { it.totals.count > 0 }?.forEach { CurrencyCard(it) }
    if (data.recent.isEmpty()) {
        if (data.statsFailure == null) ShopText(stringResource(Res.string.today_empty), muted = true)
    } else {
        ShopText(stringResource(Res.string.today_latest), TextRole.Title)
        ShopCard { data.recent.take(10).forEach { RecentLine(it) } }
    }
}

@Composable
private fun AmountLine(label: String, shown: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ShopText(label, modifier = Modifier.weight(1f))
        ShopText(shown, TextRole.Amount, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CurrencyCard(c: DashboardCurrency) {
    val code = c.currency
    fun money(amount: String) = groupThousands(amount) + " " + code
    ShopCard {
        ShopText(code, TextRole.Title)
        AmountLine(stringResource(Res.string.today_in), money(c.totals.income))
        AmountLine(stringResource(Res.string.today_out), money(c.totals.expense))
        AmountLine(stringResource(Res.string.today_net), money(c.totals.net))
        AmountLine(stringResource(Res.string.today_cash), money(c.totals.cashNet))
        val online = { type: EntryType -> c.byMethod.firstOrNull { it.key == "online" && it.type == type }?.sum }
        if (online(EntryType.INCOME) != null || online(EntryType.EXPENSE) != null) {
            AmountLine(
                stringResource(Res.string.today_online),
                groupThousands(online(EntryType.INCOME) ?: "0") + " / " + groupThousands(online(EntryType.EXPENSE) ?: "0") + " " + code,
            )
        }
    }
}

@Composable
private fun RecentLine(e: EntryDto) {
    val sign = if (e.type == EntryType.INCOME) "+" else "-"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ShopText(e.item.ifBlank { e.category ?: "—" })
            ShopText(clockTime(e.createdAt) + " · " + e.createdBy.name, TextRole.Caption, muted = true)
        }
        Column(Modifier.weight(1f)) {
            e.tenders.forEach { t -> ShopText(sign + groupThousands(t.amount) + " " + t.currency, TextRole.Amount, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
