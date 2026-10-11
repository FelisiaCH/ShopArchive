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

/**
 * The app after unlock: navigation (see [navigationPaneFrom]), the connection banner and the branch choice over the current screen.
 * With [settings] (Windows only) the page is Settings, shown selected in the pane and without the update panel and the branch choice, which it does not use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(s: AppState.Unlocked, flow: AppFlow, ws: Workspace, settings: AppState.Settings? = null) {
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
    val console = stringResource(Res.string.nav_console)
    val settingsLabel = stringResource(Res.string.nav_settings)
    val lockNow = stringResource(Res.string.lock_now)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= navigationPaneFrom
        // Leaving Settings by a destination of the pane: Settings closes first, so the page the pane names is the one shown.
        fun open(to: Destination) {
            if (settings != null) flow.closeSettings()
            ws.go(to)
        }
        // Reports sit under More when the navigation is a bottom bar and in the pane or strip on the left otherwise. Before the config has loaded (or when it failed) only More
        // is offered, so no entry is there that the workspace would ignore; the screen itself says it is loading, or offers a retry.
        val folded = navigationBottomBar && !wide
        val items = listOf(Destination.TODAY to today, Destination.RECORD to record, Destination.HISTORY to history, Destination.REPORTS to reports)
            .filter { (d, _) -> config is ConfigState.Ready && can.allows(d) && (d != Destination.REPORTS || !folded) }
            .let { if (navigationBottomBar) it + (Destination.MORE to more) else it }
        // Windows has no More: the console, Settings and Lock now sit at the foot of the pane.
        val footer = if (navigationBottomBar) emptyList() else listOfNotNull(
            if (can.console) NavAction(console, ShopIcon.Console, settings == null && destination == Destination.CONSOLE) { open(Destination.CONSOLE) } else null,
            NavAction(settingsLabel, ShopIcon.Settings, settings != null) { if (settings == null) flow.openSettings() },
        )
        val account = if (navigationBottomBar) null else NavAccount(s.username, s.server, NavAction(lockNow, ShopIcon.LockNow) { flow.lockNow() })
        val selected = when {
            settings != null -> null
            destination == Destination.OPEN_DAY -> Destination.TODAY
            destination == Destination.REPORTS && folded -> Destination.MORE
            destination == Destination.CONSOLE -> Destination.MORE
            else -> destination
        }
        ShopNavigation(items, Destination::icon, selected, ::open, wide = wide, footer = footer, account = account) {
            Column(Modifier.fillMaxSize()) {
                // Only when the server has a newer app for this platform (or a download is going on): never a button without an update.
                if (settings == null) ws.updates?.let { UpdatePanel(it, modifier = Modifier.padding(horizontal = shellInset, vertical = 4.dp)) }
                when (status) {
                    ConnectionStatus.CONNECTED -> Unit
                    ConnectionStatus.CONNECTING -> ShopBanner(stringResource(Res.string.status_connecting), Tone.Info, Modifier.padding(horizontal = shellInset, vertical = 8.dp))
                    ConnectionStatus.OFFLINE -> ShopBanner(stringResource(Res.string.offline_banner), Tone.Error, Modifier.padding(horizontal = shellInset, vertical = 8.dp))
                }
                val branches = ws.branches
                if (settings == null && branches.size > 1) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = shellInset, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        branches.forEach { b -> ShopChoice(b.displayName, b.key == branch, { ws.selectBranch(b.key) }) }
                    }
                }
                when {
                    settings != null -> SettingsPage(settings, flow)
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
            ShopButton(stringResource(Res.string.nav_reports), { ws.openReports(first) }, Modifier.fillWidthOnPhone(), primary = false, icon = ShopIcon.Reports)
        }
        if (can.console) ShopButton(stringResource(Res.string.nav_console), { ws.go(Destination.CONSOLE) }, Modifier.fillWidthOnPhone(), primary = false, icon = ShopIcon.Console)
        ShopButton(stringResource(Res.string.nav_settings), flow::openSettings, Modifier.fillWidthOnPhone(), primary = false, icon = ShopIcon.Settings)
        ShopButton(stringResource(Res.string.lock_now), { flow.lockNow() }, Modifier.fillWidthOnPhone(), icon = ShopIcon.LockNow)
    }
}
