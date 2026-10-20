import SwiftUI
import ClientCore

/// Reports: the tabs the person may use, then the chosen one. The Close day tab is CloseDayView.
struct ReportsView: View {
    let ws: Workspace
    let can: Capabilities
    @State private var chosen: ReportsTab

    init(ws: Workspace, can: Capabilities) {
        self.ws = ws
        self.can = can
        _chosen = State(initialValue: ws.reportsTab.value)
    }

    private func label(_ tab: ReportsTab) -> String {
        switch tab {
        case .overview: return text("reports_overview")
        case .closeDay: return text("closeday_btn")
        case .export: return text("reports_export")
        case .notifications: return text("reports_notifications")
        }
    }

    var body: some View {
        // Only the tabs the person may use; if the chosen one is not among them (rights changed), the first is shown.
        let tabs = can.reportsTabs
        Group {
            if let tab = tabs.contains(chosen) ? chosen : tabs.first {
                Page {
                    Picker("", selection: Binding(get: { tab }, set: { ws.openReports(tab: $0) })) {
                        ForEach(tabs, id: \.self) { Text(label($0)).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    switch tab {
                    case .overview: OverviewSection(ws: ws)
                    case .closeDay: CloseDayView(ws: ws)
                    case .export: ExportSection(ws: ws)
                    case .notifications: NotificationsSection(ws: ws)
                    }
                }
            }
        }
        .navigationTitle(text("nav_reports"))
        // Reports sits under More, and a tap on the More tab that is already chosen does not reach the workspace: the way back to the menu is here.
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(text("nav_more")) { ws.go(to: .more) }
            }
        }
        .collect(ws.reportsTab, into: $chosen)
    }
}

/// One row of a table: the first cell is text, the others are amounts that line up on the right.
private struct TableRow {
    let cells: [String]
    /// The color of the first `colored` amounts.
    var amounts: Color = .primary
    var colored = 3
}

/// A table whose columns line up from row to row. Every cell stays on one line at one size; a table too wide for the screen slides sideways instead of shrinking.
/// With `wrapsName` the first cell is a name that may be long, so it wraps and the table never slides.
private struct ReportTable: View {
    let header: [String]
    var wrapsName = false
    let rows: [TableRow]

    var body: some View {
        if wrapsName {
            grid
        } else {
            ViewThatFits(in: .horizontal) {
                grid
                ScrollView(.horizontal, showsIndicators: false) { grid }
            }
        }
    }

    private var grid: some View {
        Grid(alignment: Alignment(horizontal: .trailing, vertical: .firstTextBaseline), horizontalSpacing: 8, verticalSpacing: 10) {
            GridRow {
                ForEach(Array(header.enumerated()), id: \.offset) { i, cell in
                    self.cell(cell, column: i, font: .caption, color: .secondary)
                }
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                GridRow {
                    ForEach(Array(row.cells.enumerated()), id: \.offset) { i, cell in
                        self.cell(cell, column: i, font: i == 0 ? .subheadline : .footnote.monospacedDigit(), color: color(row, i))
                    }
                }
            }
        }
    }

    private func cell(_ shown: String, column: Int, font: Font, color: Color) -> some View {
        let wraps = wrapsName && column == 0
        return Text(verbatim: shown)
            .font(font)
            .foregroundColor(color)
            .lineLimit(wraps ? nil : 1)
            .fixedSize(horizontal: !wraps, vertical: wraps)
            .frame(maxWidth: column == 0 ? nil : .infinity, alignment: .trailing)
            .gridColumnAlignment(column == 0 ? .leading : .trailing)
    }

    private func color(_ row: TableRow, _ i: Int) -> Color {
        i >= 1 && i <= row.colored ? row.amounts : .primary
    }
}

/// A titled card of a currency's report.
private struct ReportSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        Card(title: title) { content }
    }
}

// ---- overview ----

private struct OverviewSection: View {
    let ws: Workspace
    @State private var ui: ReportsUi
    @State private var config: any ConfigState

    init(ws: Workspace) {
        self.ws = ws
        _ui = State(initialValue: ws.reports.ui.value)
        _config = State(initialValue: ws.config.value)
    }

    var body: some View {
        let r = ws.reports
        let currencies: [CurrencyInfo] = { if case .ready(let c) = onEnum(of: config) { return c.config.currencies } else { return [] } }()
        VStack(alignment: .leading, spacing: 12) {
            Picker("", selection: Binding(get: { ui.range }, set: { r.selectRange(range: $0) })) {
                Text(text("range_today")).tag(ReportRange.today)
                Text(text("range_week")).tag(ReportRange.week)
                Text(text("range_month")).tag(ReportRange.month)
                Text(text("range_custom")).tag(ReportRange.custom)
            }
            .pickerStyle(.segmented)
            if ui.range == .custom {
                CustomRange(r: r, from: ui.from, to: ui.to)
            } else {
                Text(ui.from + " – " + ui.to).font(.caption).foregroundColor(.secondary)
            }
            if let problem = ui.rangeProblem {
                Text(rangeText(problem)).font(.caption).foregroundColor(Theme.moneyOut)
            }
            if ws.branches.count > 1 {
                FlowLayout {
                    ForEach(ws.branches, id: \.key) { b in
                        Choice(name: b.displayName, selected: ui.branch == b.key) { r.setBranch(key: b.key) }
                    }
                }
            }
            if currencies.count > 1 {
                FlowLayout {
                    Choice(name: text("filter_all"), selected: ui.currency == nil) { r.setCurrency(code: nil) }
                    ForEach(currencies, id: \.code) { c in
                        Choice(name: c.code, selected: ui.currency == c.code) { r.setCurrency(code: c.code) }
                    }
                }
            }
            if let failure = ui.failure {
                Banner(text: text("reports_failed") + " " + failureText(failure), tone: .error)
                Button(text("retry")) { r.reload() }
                    .buttonStyle(.tonalFull)
            }
            if ui.rangeProblem != nil || ui.failure != nil {
                EmptyView()
            } else if ui.data == nil {
                Text(text("working")).foregroundColor(.secondary)
            } else if ui.shown.isEmpty {
                Text(text("reports_empty")).foregroundColor(.secondary)
            } else {
                if ui.loading {
                    Text(text("working")).font(.caption).foregroundColor(.secondary)
                }
                ForEach(ui.shown, id: \.currency) { c in
                    CurrencyReport(ws: ws, c: c, exponent: currencies.first { $0.code == c.currency }?.exponent ?? 0)
                }
            }
        }
        .collect(ws.reports.ui, into: $ui)
        .collect(ws.config, into: $config)
    }
}

/// The two dates of a custom range. The picked dates are kept here as well (see HistoryList).
/// Only there while the range is Custom, so each time it starts from the flow's dates, which a quick range changes.
private struct CustomRange: View {
    let r: ReportsState
    @State private var from: String
    @State private var to: String

    init(r: ReportsState, from: String, to: String) {
        self.r = r
        _from = State(initialValue: from)
        _to = State(initialValue: to)
    }

    var body: some View {
        Card {
            DateField(label: text("filter_from"), value: from) { from = $0; r.setFrom(text: $0) }
            Divider()
            DateField(label: text("filter_to"), value: to) { to = $0; r.setTo(text: $0) }
        }
    }
}

private struct CurrencyReport: View {
    let ws: Workspace
    let c: DashboardCurrency
    let exponent: Int32

    private func delta(_ now: String, _ before: String) -> String {
        ws.reports.delta(currency: c.currency, now: now, before: before).map { signedAmount(minor: $0.int64Value, exponent: exponent) } ?? "—"
    }

    /// A row of the totals table: the amounts without the currency, which the title above the table already says.
    private func total(_ label: String, _ now: String, _ before: String, color: Color = .primary) -> TableRow {
        TableRow(cells: [label, groupThousands(now), groupThousands(before), delta(now, before)], amounts: color)
    }

    var body: some View {
        let t = c.totals
        let p = c.previous
        let change = t.count - p.count
        let changeShown = (change > 0 ? "+" : "") + String(change)
        let totals: [TableRow] = [
            total(text("today_in"), t.income, p.income, color: Theme.moneyIn),
            total(text("today_out"), t.expense, p.expense, color: Theme.moneyOut),
            total(text("today_net"), t.net, p.net),
            total(text("today_cash"), t.cashNet, p.cashNet),
            TableRow(cells: [text("reports_entries"), String(t.count), String(p.count), changeShown]),
        ]
        VStack(alignment: .leading, spacing: 12) {
            Text(c.currency).font(.title3.weight(.semibold))
            Card {
                ReportTable(header: ["", text("reports_this"), text("reports_before"), text("reports_change")], rows: totals)
            }
            if !c.daily.isEmpty {
                ReportSection(title: text("reports_daily")) {
                    ReportTable(
                        header: [text("reports_date"), text("today_in"), text("today_out"), text("today_net")],
                        rows: c.daily.map { TableRow(cells: [$0.date, groupThousands($0.income), groupThousands($0.expense), groupThousands($0.net)]) }
                    )
                }
            }
            if !c.byCategory.isEmpty {
                ReportSection(title: text("reports_by_category")) {
                    breakdown(c.byCategory) { $0 == "none" ? text("reports_no_category") : (categoryTitle(ws, $0) ?? $0) }
                }
            }
            if !c.byMethod.isEmpty {
                ReportSection(title: text("reports_by_method")) {
                    breakdown(c.byMethod) { text($0 == "online" ? "method_online" : "method_cash") }
                }
            }
            if !c.candles.isEmpty {
                ReportSection(title: text("reports_candles")) {
                    ReportTable(
                        header: [
                            text("reports_time"), text("reports_open"), text("reports_high"),
                            text("reports_low"), text("reports_close"), text("reports_money_in"),
                        ],
                        rows: c.candles.map { k in
                            let start = k.start.replacingOccurrences(of: "T", with: " ")
                            return TableRow(cells: [start, groupThousands(k.open), groupThousands(k.high), groupThousands(k.low), groupThousands(k.close), groupThousands(k.moneyIn)])
                        }
                    )
                }
            }
            if !c.topItems.isEmpty {
                ReportSection(title: text("reports_top_items")) { items(c.topItems) }
            }
            if !c.expenseItems.isEmpty {
                ReportSection(title: text("reports_expense_items")) { items(c.expenseItems) }
            }
        }
    }

    /// The sum of a row in the color of its type, with the sign as in History: green and + came in, red and - went out.
    private func breakdown(_ rows: [DashboardBreakdown], name: @escaping (String) -> String) -> some View {
        ReportTable(
            header: [text("category_label"), text("reports_sum"), text("reports_entries")],
            wrapsName: true,
            rows: rows.map { row in
                let income = row.type == .income
                let sum = (income ? "+" : "-") + groupThousands(row.sum)
                return TableRow(cells: [name(row.key), sum, String(row.count)], amounts: Theme.color(of: row.type), colored: 1)
            }
        )
    }

    private func items(_ rows: [DashboardItem]) -> some View {
        ReportTable(
            header: [text("item_label"), text("reports_sum"), text("reports_entries")],
            wrapsName: true,
            rows: rows.map { TableRow(cells: [isBlank($0.item) ? "—" : $0.item, groupThousands($0.sum), String($0.count)]) }
        )
    }
}

// ---- notifications ----

private struct NotificationsSection: View {
    let ws: Workspace
    @State private var ui: NotificationsUi

    init(ws: Workspace) {
        self.ws = ws
        _ui = State(initialValue: ws.notifications.ui.value)
    }

    var body: some View {
        let n = ws.notifications
        VStack(alignment: .leading, spacing: 12) {
            Text(text("notif_hint")).foregroundColor(.secondary)
            if let failure = ui.failure {
                Banner(text: text("notif_load_failed") + " " + failureText(failure), tone: .error)
            }
            if let failure = ui.actionFailure {
                Banner(text: text("notif_resend_failed") + " " + failureText(failure), tone: .error)
            }
            if ui.canSend && !ui.closedDays.isEmpty {
                SectionHeader(text("notif_closed_days"))
                ForEach(ui.closedDays, id: \.id) { day in
                    let enabled = !ui.blocked && !ui.working.contains(day.id) && !ui.dayWaiting(session: day)
                    Button(text("notif_send_day", day.businessDate)) { n.sendDay(session: day) }
                        .buttonStyle(.tonalFull)
                        .disabled(!enabled)
                }
            }
            if let items = ui.items {
                if items.isEmpty {
                    Text(text("notif_empty")).foregroundColor(.secondary)
                } else {
                    ForEach(items, id: \.id) { item in
                        Message(ws: ws, n: item, canSend: ui.canSend && ui.canResend(n: item), enabled: !ui.blocked && !ui.working.contains(item.id))
                    }
                }
            } else if ui.failure == nil {
                Text(text("working")).foregroundColor(.secondary)
            }
            if ui.blocked {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
            Button(text("notif_refresh")) { n.reload() }
                .buttonStyle(.tonalFull)
                .disabled(ui.loading)
        }
        .collect(n.ui, into: $ui)
        // Opening the tab asks again: the outbox changes on the server without telling the app.
        .task { n.reload() }
    }
}

private struct Message: View {
    let ws: Workspace
    let n: NotificationDto
    let canSend: Bool
    let enabled: Bool

    private var event: String {
        switch n.event {
        case "day.closed": return text("notif_event_day")
        case "entry.created": return text("notif_event_entry")
        case "device.new": return text("notif_event_device")
        case "note.created": return text("notif_event_note")
        default: return n.event
        }
    }

    private var stateWords: String {
        switch n.state {
        case .queued: return text("notif_state_queued")
        case .sent: return text("notif_state_sent")
        case .unknown: return text("notif_state_unknown")
        case .failed: return text("notif_state_failed")
        }
    }

    private var stateTone: Tone {
        switch n.state {
        case .sent: return .success
        case .failed: return .error
        default: return .info
        }
    }

    var body: some View {
        let branch = ws.branches.first { $0.key == n.branch }?.displayName ?? n.branch
        // device.new of a user with no branch has none.
        let title = branch.isEmpty ? event : "\(event) · \(branch)"
        let when = String(n.createdAt.prefix(16)).replacingOccurrences(of: "T", with: " ")
        Card {
            Text(title).font(.headline)
            Text(verbatim: "\(n.code) · \(n.user) · \(when)").font(.caption).foregroundColor(.secondary)
            Chip(text: stateWords, tone: stateTone)
            if n.attempts > 1 {
                Text(text("notif_attempts", n.attempts)).font(.caption).foregroundColor(.secondary)
            }
            // Why it is not sent (the channel's own words) and when it is tried again; nothing once it is sent.
            if n.state != .sent {
                if let reason = n.lastError {
                    Text(verbatim: reason).font(.caption).foregroundColor(.secondary)
                }
                if let next = n.nextAttemptAt {
                    Text(verbatim: text("notif_retry_at", localTime(next))).font(.caption).foregroundColor(.secondary)
                }
            }
            if canSend {
                Button(text("notif_resend")) { ws.notifications.resend(id: n.id) }
                    .buttonStyle(.tonalFull)
                    .disabled(!enabled)
            }
        }
    }
}

// ---- export ----

/// The file the share sheet is showing.
private struct SharedFile: Identifiable {
    let id = UUID()
    let url: URL
}

/// The system share sheet; `finished` hears how it went, as Compose's file saver does.
private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    let finished: (SaveResult) -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.completionWithItemsHandler = { _, completed, _, error in
            finished(error != nil ? .failed : completed ? .saved : .cancelled)
        }
        return sheet
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

private struct ExportSection: View {
    let ws: Workspace
    @State private var ui: ExportUi
    // The picked dates are kept here as well (see CustomRange). Only the person changes these dates, so they never need to follow the flow.
    @State private var from: String
    @State private var to: String
    @State private var shared: SharedFile?

    init(ws: Workspace) {
        self.ws = ws
        let ui = ws.export.ui.value
        _ui = State(initialValue: ui)
        _from = State(initialValue: ui.from)
        _to = State(initialValue: ui.to)
    }

    private var ready: ExportFile? {
        if case .ready(let p) = onEnum(of: ui.phase) { return p.file }
        return nil
    }

    var body: some View {
        let x = ws.export
        VStack(alignment: .leading, spacing: 12) {
            Text(text("export_hint")).foregroundColor(.secondary)
            Card {
                DateField(label: text("filter_from"), value: from) { from = $0; x.setFrom(text: $0) }
                Divider()
                DateField(label: text("filter_to"), value: to) { to = $0; x.setTo(text: $0) }
                if let problem = ui.rangeProblem {
                    Text(rangeText(problem)).font(.caption).foregroundColor(Theme.moneyOut)
                }
            }
            if ws.branches.count > 1 {
                FlowLayout {
                    ForEach(ws.branches, id: \.key) { b in
                        Choice(name: b.displayName, selected: ui.branch == b.key) { x.setBranch(key: b.key) }
                    }
                }
            }
            Picker("", selection: Binding(get: { ui.format }, set: { x.setFormat(format: $0) })) {
                Text("CSV").tag("csv")
                Text("XLSX").tag("xlsx")
            }
            .pickerStyle(.segmented)
            phase
            if ui.blocked {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
            Button(text("export_make")) { x.export() }
                .buttonStyle(.filledFull)
                .disabled(!ui.canExport)
        }
        .collect(x.ui, into: $ui)
        // The file is ready: offer to keep it.
        .task(id: ready.map { ObjectIdentifier($0) }) {
            if let file = ready { await share(file) }
        }
        .sheet(item: $shared) { file in
            ShareSheet(url: file.url) { ws.export.delivered(result: $0) }
        }
    }

    @ViewBuilder
    private var phase: some View {
        switch onEnum(of: ui.phase) {
        case .working:
            Banner(text: text("export_working"), tone: .info)
        case .failed(let p):
            Banner(text: text("export_failed") + " " + failureText(p.failure), tone: .error)
        case .ready(let p):
            switch ui.saved {
            case .saved: Banner(text: text("export_saved", p.file.fileName), tone: .success)
            case .failed: Banner(text: text("export_write_failed"), tone: .error)
            case .cancelled, .none: Banner(text: text("export_ready", p.file.fileName), tone: .info)
            }
            Button(text("export_save")) { Task { await share(p.file) } }
                .buttonStyle(.tonalFull)
        case .idle:
            EmptyView()
        }
    }

    /// Writes the file to a temporary one, off the main thread (the bytes go over one by one), and shows the share sheet for it; a file that cannot be written is reported as such.
    private func share(_ file: ExportFile) async {
        let name = file.fileName
        let url = await Task.detached { () -> URL? in
            var data = Data(count: Int(file.bytes.size))
            for i in 0..<data.count { data[i] = UInt8(bitPattern: file.bytes.get(index: Int32(i))) }
            let url = FileManager.default.temporaryDirectory.appendingPathComponent((name as NSString).lastPathComponent)
            do {
                try data.write(to: url)
                return url
            } catch {
                return nil
            }
        }.value
        if let url {
            shared = SharedFile(url: url)
        } else {
            ws.export.delivered(result: .failed)
        }
    }
}
