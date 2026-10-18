import SwiftUI
import ClientCore

/// The change counted in the drawer, one amount per currency (empty means 0). A day that was never closed has a way on to Close day.
struct OpenDayView: View {
    let ws: Workspace
    let can: Capabilities
    @State private var ui: OpenDayUi
    // What is typed is kept here as well: a field bound to the flow alone loses characters while the flow's answer is on its way back.
    @State private var typed: [String: String]

    init(ws: Workspace, can: Capabilities) {
        self.ws = ws
        self.can = can
        _ui = State(initialValue: ws.openDay.ui.value)
        _typed = State(initialValue: ws.openDay.ui.value.amounts)
    }

    private var saving: Bool {
        if case .saving = onEnum(of: ui.phase) { return true }
        return false
    }

    private var opened: Bool {
        if case .opened = onEnum(of: ui.phase) { return true }
        return false
    }

    var body: some View {
        Page {
            Text(text("openday_hint")).foregroundColor(.secondary)
            ForEach(ui.currencies, id: \.code) { currency in
                field(currency)
            }
            phase
            if ui.blocked == .offline {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
            HStack(spacing: 8) {
                Button(text("openday_btn")) { ws.openDay.confirm() }
                    .buttonStyle(.borderedProminent)
                    .disabled(ui.blocked != nil || saving)
                Button(text("cancel")) { leave() }
                    .buttonStyle(.bordered)
            }
        }
        .navigationTitle(text("openday_btn"))
        .collect(ws.openDay.ui, into: $ui)
        // Opened: the day is open now, so Today shows it.
        .task(id: opened) {
            if opened { leave() }
        }
        // Reset by the flow (another branch is another drawer): the fields are empty again.
        .onChange(of: ui.amounts) { amounts in
            if amounts.isEmpty { typed = [:] }
        }
    }

    @ViewBuilder
    private func field(_ currency: CurrencyInfo) -> some View {
        let code = currency.code
        let value = typed[code] ?? ""
        TextField(text("openday_amount", code), text: Binding(
            get: { typed[code] ?? "" },
            set: {
                typed[code] = $0
                ws.openDay.setAmount(code: code, text: $0)
            }
        ))
        .keyboardType(.decimalPad)
        .textFieldStyle(.roundedBorder)
        .disabled(saving)
        let minor = MoneyTextKt.parseTypedAmount(text: value, exponent: currency.exponent, allowZero: true)
        if ui.invalid.contains(code) || (!value.isEmpty && minor == nil) {
            Text(text("openday_invalid", code)).font(.caption).foregroundColor(.red)
        } else if let minor, !value.isEmpty {
            Text(text("record_amount_shown", displayAmount(minor: minor.int64Value, exponent: currency.exponent) + " " + code))
                .font(.caption).foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private var phase: some View {
        switch onEnum(of: ui.phase) {
        case .saving:
            Banner(text: text("working"), tone: .info)
        case .alreadyOpen(let p):
            Banner(text: text("openday_already", p.by.name), tone: .info)
            Button(text("go_today")) { leave() }
                .buttonStyle(.borderedProminent)
        case .previousOpen(let p):
            Banner(text: text("openday_previous", p.session.businessDate, p.session.openedBy.name), tone: .error)
            if can.closeDay {
                Button(text("closeday_btn")) {
                    ws.openDay.reset()
                    ws.openReports(tab: .closeDay)
                }
                .buttonStyle(.borderedProminent)
            }
        case .failed(let p):
            Banner(text: failureText(p.failure), tone: .error)
        case .idle, .opened:
            EmptyView()
        }
    }

    private func leave() {
        ws.openDay.reset()
        ws.go(to: .today)
    }
}
