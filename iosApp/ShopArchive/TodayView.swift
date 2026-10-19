import SwiftUI
import ClientCore

/// The day's status, the money and the latest lines of the chosen branch. The slips card opens History, the notes card Notes and the Close day button Reports.
struct TodayView: View {
    let ws: Workspace
    let can: Capabilities
    @State private var ui: TodayUi
    @State private var canWrite: Bool

    init(ws: Workspace, can: Capabilities) {
        self.ws = ws
        self.can = can
        _ui = State(initialValue: ws.today.ui.value)
        _canWrite = State(initialValue: ws.canWrite.value.boolValue)
    }

    var body: some View {
        Page {
            if let data = ui.data {
                if let failure = ui.failure {
                    Banner(text: text("today_failed") + " " + failureText(failure), tone: .error)
                }
                TodayContent(ws: ws, data: data, can: can, canWrite: canWrite, openDay: { ws.go(to: .openDay) }, closeDay: { ws.openReports(tab: .closeDay) }, openHistory: { ws.go(to: .history) }, openNotes: { ws.openNotes(day: data.oldestUnreadNote ?? data.date) })
            } else if let failure = ui.failure {
                Banner(text: text("today_failed") + " " + failureText(failure), tone: .error)
                Button(text("retry")) { ws.today.reload() }
                    .buttonStyle(.tonal)
            } else {
                Text(text("working")).foregroundColor(.secondary)
            }
        }
        .navigationTitle(text("today_title"))
        .collect(ws.today.ui, into: $ui)
        .task {
            for await next in ws.canWrite { canWrite = next.boolValue }
        }
    }
}

private struct TodayContent: View {
    let ws: Workspace
    let data: TodayData
    let can: Capabilities
    let canWrite: Bool
    let openDay: () -> Void
    let closeDay: () -> Void
    let openHistory: () -> Void
    let openNotes: () -> Void

    var body: some View {
        // Business dates come from the server's clock, so a wrong one is worth saying before anything else.
        if let minutes = data.clockOffMinutes {
            Banner(text: text("clock_off", String(minutes.intValue)), tone: .error)
        }
        switch onEnum(of: data.status) {
        case .notOpened:
            Card {
                Text(text("openday_card_title")).font(.headline)
                if can.openDay {
                    Text(text("openday_card_body")).foregroundColor(.secondary)
                    if !canWrite {
                        Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
                    }
                    Button(text("openday_btn"), action: openDay)
                        .buttonStyle(.filledFull)
                        .disabled(!canWrite)
                        .padding(.top, 4)
                } else {
                    Text(text("openday_card_body_other")).foregroundColor(.secondary)
                }
            }
        case .open(let open):
            Chip(text: text("day_open_by", clockTime(open.session.openedAt), open.session.openedBy.name), tone: .success, systemImage: "checkmark.circle.fill")
            if let staleFrom = data.staleFrom {
                Banner(text: text(can.closeDay ? "day_stale" : "day_stale_other", staleFrom), tone: .error)
            }
            if can.closeDay {
                // Primary when the day is stale.
                if data.staleFrom != nil {
                    Button(action: closeDay) { Label(text("closeday_btn"), systemImage: "lock") }
                        .buttonStyle(.filledFull)
                } else {
                    Button(action: closeDay) { Label(text("closeday_btn"), systemImage: "lock") }
                        .buttonStyle(.tonalFull)
                }
            }
        case .closed:
            Chip(text: text("day_closed"), tone: .info, systemImage: "lock.fill")
        }

        counts

        if !data.showStats {
            if let failure = data.statsFailure {
                Banner(text: text("today_mine_failed") + " " + failureText(failure), tone: .error)
            }
            if let mine = data.mine {
                if mine.isEmpty {
                    Card(title: text("today_mine")) { Text(text("today_empty")).foregroundColor(.secondary) }
                } else {
                    RowsCard(title: text("today_mine"), mine, id: \.id) { RecentLine(ws: ws, entry: $0) }
                }
            }
        } else {
            if let failure = data.statsFailure {
                Banner(text: text("today_stats_failed") + " " + failureText(failure), tone: .error)
            }
            ForEach((data.currencies ?? []).filter { $0.totals.count > 0 }, id: \.currency) { CurrencyCard(currency: $0) }
            if data.recent.isEmpty {
                if data.statsFailure == nil {
                    Text(text("today_empty")).foregroundColor(.secondary)
                }
            } else {
                RowsCard(title: text("today_latest"), Array(data.recent.prefix(10)), id: \.id) { RecentLine(ws: ws, entry: $0) }
            }
        }
    }

    /// The slips attached today (with the dashboard; opens History) and the notes for the owner that are unread (with the read node; opens Notes).
    @ViewBuilder
    private var counts: some View {
        let unread = data.unreadNotes
        if data.showStats || unread != nil {
            HStack(alignment: .top, spacing: 12) {
                if data.showStats {
                    let slips = Card {
                        Label(text("today_slips"), systemImage: "paperclip").font(.footnote).foregroundColor(.secondary)
                        Text(String(data.slips)).font(.title.weight(.semibold))
                    }
                    if can.viewEntries {
                        Button(action: openHistory) { slips }
                            .buttonStyle(.plain)
                    } else {
                        slips
                    }
                }
                if let unread {
                    Button(action: openNotes) {
                        Card {
                            Label(text("today_notes"), systemImage: "envelope").font(.footnote).foregroundColor(.secondary)
                            Text(String(unread.intValue)).font(.title.weight(.semibold))
                            Text(text("today_notes_unread")).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct CurrencyCard: View {
    let currency: DashboardCurrency

    var body: some View {
        let code = currency.currency
        let totals = currency.totals
        let online = { (type: EntryType) in currency.byMethod.first { $0.key == "online" && $0.type == type }?.sum }
        Card {
            HStack {
                Text(code).font(.headline)
                Spacer()
                Text(text("today_net")).font(.footnote).foregroundColor(.secondary)
            }
            Amount(shown: "\(signedText(totals.net)) \(code)", color: Theme.color(sign: amountSign(totals.net)), font: .title.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Divider()
            AmountLine(label: text("today_in"), shown: money(totals.income), color: Theme.moneyIn)
            AmountLine(label: text("today_out"), shown: money(totals.expense), color: Theme.moneyOut)
            AmountLine(label: text("today_cash"), shown: money(totals.cashNet), color: .secondary)
            if online(.income) != nil || online(.expense) != nil {
                AmountLine(
                    label: text("today_online"),
                    shown: groupThousands(online(.income) ?? "0") + " / " + groupThousands(online(.expense) ?? "0") + " " + code,
                    color: .secondary
                )
            }
        }
    }

    private func money(_ amount: String) -> String {
        groupThousands(amount) + " " + currency.currency
    }
}

private struct RecentLine: View {
    let ws: Workspace
    let entry: EntryDto

    private var title: String {
        isBlank(entry.item) ? (categoryTitle(ws, entry.category) ?? "—") : entry.item
    }

    var body: some View {
        let sign = entry.type == .income ? "+" : "-"
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title)
                Text(clockTime(entry.createdAt) + " · " + entry.createdBy.name).font(.caption).foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(alignment: .trailing, spacing: 2) {
                ForEach(Array(entry.tenders.enumerated()), id: \.offset) { _, tender in
                    Amount(shown: "\(sign)\(groupThousands(tender.amount)) \(tender.currency)", color: Theme.color(of: entry.type), font: .body.weight(.semibold))
                }
            }
        }
        .rowInset()
    }
}
