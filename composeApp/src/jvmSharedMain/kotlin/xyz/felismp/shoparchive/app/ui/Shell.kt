package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.flow.AppFlow
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.Capabilities
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.resources.*

/** The app after unlock: navigation (bottom bar under 900 dp wide, a left pane from 900), the connection banner and the branch choice over the current screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(s: AppState.Unlocked, flow: AppFlow, ws: Workspace) {
    val destination by ws.destination.collectAsState()
    val config by ws.config.collectAsState()
    val branch by ws.branch.collectAsState()
    val can by ws.capabilities.collectAsState()
    val status by s.connection.collectAsState()
    val today = stringResource(Res.string.nav_today)
    val record = stringResource(Res.string.nav_record)
    val history = stringResource(Res.string.nav_history)
    val reports = stringResource(Res.string.nav_reports)
    val more = stringResource(Res.string.nav_more)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        // Reports sit under More on a narrow screen and in the left pane from 900 dp. Before the config has loaded (or when it failed) only More
        // is offered, so no entry is there that the workspace would ignore; the screen itself says it is loading, or offers a retry.
        val items = listOf(Destination.TODAY to today, Destination.RECORD to record, Destination.HISTORY to history, Destination.REPORTS to reports)
            .filter { (d, _) -> config is ConfigState.Ready && can.allows(d) && (d != Destination.REPORTS || wide) } + (Destination.MORE to more)
        val selected = when {
            destination == Destination.OPEN_DAY -> Destination.TODAY
            destination == Destination.REPORTS && !wide -> Destination.MORE
            destination == Destination.CONSOLE -> Destination.MORE
            else -> destination
        }
        ShopNavigation(items, Destination::icon, selected, ws::go, wide = wide) {
            Column(Modifier.fillMaxSize()) {
                // Only when the server has a newer app for this platform (or a download is going on): never a button without an update.
                ws.updates?.let { UpdatePanel(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                when (status) {
                    ConnectionStatus.CONNECTED -> Unit
                    ConnectionStatus.CONNECTING -> ShopBanner(stringResource(Res.string.status_connecting), Tone.Info, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    ConnectionStatus.OFFLINE -> ShopBanner(stringResource(Res.string.offline_banner), Tone.Error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                val branches = ws.branches
                if (branches.size > 1) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        branches.forEach { b -> ShopChoice(b.displayName, b.key == branch, { ws.selectBranch(b.key) }) }
                    }
                }
                when {
                    destination == Destination.MORE -> MoreScreen(s, flow, ws, can)
                    destination == Destination.CONSOLE -> ConsoleScreen(ws)
                    config is ConfigState.Loading -> ShopPage("") { ShopText(stringResource(Res.string.working), muted = true) }
                    config is ConfigState.Failed -> ShopPage("") {
                        ShopBanner(stringResource(Res.string.cfg_failed), Tone.Error)
                        ShopButton(stringResource(Res.string.retry), ws::retry)
                    }
                    branch == null -> ShopPage("") { ShopBanner(stringResource(Res.string.no_branch), Tone.Info) }
                    destination == Destination.TODAY -> TodayScreen(ws)
                    destination == Destination.OPEN_DAY -> OpenDayScreen(ws)
                    destination == Destination.RECORD -> RecordScreen(ws)
                    destination == Destination.HISTORY -> HistoryScreen(ws)
                    destination == Destination.REPORTS -> ReportsScreen(ws)
                }
            }
        }
    }
}

private fun Destination.icon() = when (this) {
    Destination.TODAY -> ShopIcon.Today
    Destination.OPEN_DAY -> ShopIcon.OpenDay
    Destination.RECORD -> ShopIcon.Record
    Destination.HISTORY -> ShopIcon.History
    Destination.REPORTS -> ShopIcon.Reports
    Destination.CONSOLE -> ShopIcon.Console
    Destination.MORE -> ShopIcon.More
}

@Composable
private fun MoreScreen(s: AppState.Unlocked, flow: AppFlow, ws: Workspace, can: Capabilities) {
    ShopPage(stringResource(Res.string.nav_more)) {
        ShopText(stringResource(Res.string.more_signed_in, s.username), muted = true)
        ShopText(s.server, TextRole.Caption, muted = true)
        can.reportsTabs.firstOrNull()?.let { first ->
            ShopButton(stringResource(Res.string.nav_reports), { ws.openReports(first) }, Modifier.fillMaxWidth(), primary = false, icon = ShopIcon.Reports)
        }
        if (can.console) ShopButton(stringResource(Res.string.nav_console), { ws.go(Destination.CONSOLE) }, Modifier.fillMaxWidth(), primary = false, icon = ShopIcon.Console)
        ShopButton(stringResource(Res.string.nav_settings), flow::openSettings, Modifier.fillMaxWidth(), primary = false, icon = ShopIcon.Settings)
        ShopButton(stringResource(Res.string.lock_now), { flow.lockNow() }, Modifier.fillMaxWidth(), icon = ShopIcon.LockNow)
    }
}
