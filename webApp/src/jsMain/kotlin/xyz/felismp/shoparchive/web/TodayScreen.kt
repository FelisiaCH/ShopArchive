package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.flow.AppFlow
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.OpenDayPhase
import xyz.felismp.shoparchive.app.flow.ReportsTab
import xyz.felismp.shoparchive.app.flow.Workspace

/** After unlock: the connection banner, the branch to work in, the nav bar, and the chosen screen. */
@Composable
fun Shell(t: Translator, s: AppState.Unlocked, flow: AppFlow, ws: Workspace) {
    val config by ws.config.collectAsState()
    val branch by ws.branch.collectAsState()
    val destination by ws.destination.collectAsState()
    val can by ws.capabilities.collectAsState()
    val status by s.connection.collectAsState()
    Div(attrs = { classes("shell") }) {
        Div(attrs = { classes("bar") }) {
            Span(attrs = { classes("muted") }) { Text(s.username) }
            Div(attrs = { classes("row") }) {
                Btn(t.t("nav_settings"), primary = false) { flow.openSettings() }
                Btn(t.t("lock_now"), primary = false) { flow.lockNow() }
            }
        }
        connectionBanner(status)?.let { Banner(t.text(it), error = status == ConnectionStatus.OFFLINE) }
        // `config` is read so the branches below follow it.
        val branches = if (config is ConfigState.Ready) ws.branches else emptyList()
        if (branches.size > 1) {
            Div(attrs = { classes("row") }) {
                Span(attrs = { classes("muted") }) { Text(t.t("branch_label")) }
                branches.forEach { b -> Btn(b.displayName, primary = b.key == branch) { ws.selectBranch(b.key) } }
            }
        }
        when (val body = shellBody(config, branch)) {
            ShellBody.Loading -> Screen("") { Banner(t.t("working")) }
            is ShellBody.Failed -> Screen("") {
                Banner(t.text(body.message), error = true)
                Btn(t.t("retry"), action = ws::retry)
            }
            ShellBody.NoBranch -> Screen("") { Banner(t.t("no_branch")) }
            ShellBody.Ready -> {
                Nav(attrs = { classes("nav") }) {
                    navItems(can).forEach { item ->
                        val current = item.destination == destination
                        Btn(t.t(item.label), primary = current, current = current) { ws.go(item.destination) }
                    }
                }
                when (destination) {
                    Destination.OPEN_DAY -> OpenDayScreen(t, ws)
                    Destination.RECORD -> RecordScreen(t, ws)
                    Destination.HISTORY -> HistoryScreen(t, ws)
                    // Close day is the only Reports tab on the web so far.
                    Destination.REPORTS -> CloseDayScreen(t, ws)
                    Destination.NOTES -> NotesScreen(t, ws)
                    else -> TodayScreen(t, ws)
                }
            }
        }
    }
}

@Composable
fun TodayScreen(t: Translator, ws: Workspace) {
    val ui by ws.today.ui.collectAsState()
    val canWrite by ws.canWrite.collectAsState()
    val can by ws.capabilities.collectAsState()
    val config by ws.config.collectAsState()
    Screen(t.t("today_title")) {
        val data = ui.data
        val failure = ui.failure
        when {
            data == null && failure != null -> {
                Banner(t.text(Message("today_failed", detail = failure.message())), error = true)
                Btn(t.t("retry"), action = ws.today::reload)
            }
            data == null -> Banner(t.t("working"))
            else -> {
                failure?.let { Banner(t.text(Message("today_failed", detail = it.message())), error = true) }
                val categories = (config as? ConfigState.Ready)?.config?.categories.orEmpty()
                val view = todayView(data, can, canWrite) { key -> categories.firstOrNull { it.key == key }?.name?.pick(t.language) ?: key }
                view.banners.forEach { Banner(t.text(it), error = true) }
                if (view.notOpened) H2 { Text(t.t("openday_card_title")) }
                view.body?.let { Note(t.text(it)) }
                if (view.chips.isNotEmpty()) Div(attrs = { classes("row") }) { view.chips.forEach { Span(attrs = { classes("chip") }) { Text(t.text(it)) } } }
                if (view.needsConnection) Note(t.t("needs_connection"))
                view.openButton?.let { Btn(t.t(it.label), enabled = it.enabled, primary = it.primary) { ws.go(Destination.OPEN_DAY) } }
                view.closeButton?.let { Btn(t.t(it.label), enabled = it.enabled, primary = it.primary) { ws.openReports(ReportsTab.CLOSE_DAY) } }
                view.statsBanner?.let { Banner(t.text(it), error = true) }
                view.cards.forEach { c ->
                    Div(attrs = { classes("card") }) {
                        H2 { Text(c.code) }
                        c.rows.forEach { (label, amount) -> Div(attrs = { classes("amount") }) { Span { Text(t.t(label)) }; Span { Text(amount) } } }
                    }
                }
                view.linesTitle?.let { H2 { Text(t.t(it)) } }
                view.lines.forEach { l ->
                    Div(attrs = { classes("line") }) {
                        Div { Div { Text(l.title) }; Div(attrs = { classes("muted") }) { Text(l.caption) } }
                        Div { l.amounts.forEach { Div(attrs = { classes("amount", if (it.income) "in" else "out") }) { Text(it.text) } } }
                    }
                }
                if (view.empty) Note(t.t("today_empty"))
            }
        }
    }
}

/** The change counted in the drawer, one amount per currency; the previous round's kept change is a hint only. */
@Composable
fun OpenDayScreen(t: Translator, ws: Workspace) {
    val ui by ws.openDay.ui.collectAsState()
    val view = openDayView(ui)
    val phase = ui.phase
    // Opened: the day is open now, so Today shows it.
    LaunchedEffect(phase) {
        if (phase is OpenDayPhase.Opened) {
            ws.openDay.reset()
            ws.go(Destination.TODAY)
        }
    }
    val back = { ws.openDay.reset(); ws.go(Destination.TODAY) }
    Screen(t.t("openday_btn")) {
        Note(t.t("openday_hint"))
        view.keptHint?.let { Note(t.text(it)) }
        SubmitForm({ if (view.confirmEnabled) ws.openDay.confirm() }) {
            view.fields.forEach { f ->
                Field(t.t("openday_amount", f.code), f.text, { ws.openDay.setAmount(f.code, it) }, FieldKind.Amount, enabled = !view.saving)
                if (f.invalid) Note(t.t("openday_invalid", f.code)) else f.shown?.let { Note(t.t("record_amount_shown", it)) }
            }
            view.banner?.let { Banner(t.text(it.message), it.error) }
            if (view.needsConnection) Note(t.t("needs_connection"))
            Btn(t.t("openday_btn"), enabled = view.confirmEnabled, submit = true)
        }
        if (view.goToday) Btn(t.t("go_today"), action = back)
        Btn(t.t("cancel"), primary = false, action = back)
    }
}
