import SwiftUI
import ClientCore

/// Reports › Close day: the drawer counted per currency against what should be there, then the day closed; or, once closed, what the server worked out. The page title and tabs are Reports'.
struct CloseDayView: View {
    let ws: Workspace
    @State private var ui: CloseUi

    init(ws: Workspace) {
        self.ws = ws
        _ui = State(initialValue: ws.closeDay.ui.value)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let result = ui.result, let close = result.close {
                ClosedResult(ws: ws, date: result.businessDate, close: close)
            } else if let session = ui.session {
                // Another open day (or branch) is another drawer: the flow empties the form, and the typed copy starts again with it.
                CloseForm(ws: ws, ui: ui, session: session).id(session.id)
            } else {
                Text(text("closeday_none")).foregroundColor(.secondary)
                Button(text("go_today")) { ws.go(to: .today) }
                    .buttonStyle(.bordered)
            }
        }
        .collect(ws.closeDay.ui, into: $ui)
    }
}

private struct CloseForm: View {
    let ws: Workspace
    let ui: CloseUi
    let session: SessionDto
    // What is typed is kept here as well (see HistoryList). The flow empties the form only for another day or branch, which is another CloseForm (see CloseDayView).
    @State private var counted: [String: String]
    @State private var note: String

    init(ws: Workspace, ui: CloseUi, session: SessionDto) {
        self.ws = ws
        self.ui = ui
        self.session = session
        _counted = State(initialValue: Dictionary(ui.rows.map { ($0.currency.code, $0.text) }, uniquingKeysWith: { _, last in last }))
        _note = State(initialValue: ui.note)
    }

    private var saving: Bool {
        if case .saving = onEnum(of: ui.phase) { return true }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(text("closeday_hint", session.businessDate)).foregroundColor(.secondary)
            if let failure = ui.previewFailure {
                Banner(text: text("closeday_no_preview") + " " + failureText(failure), tone: .info)
            }
            ForEach(ui.rows, id: \.currency.code) { row in
                CurrencyCount(ws: ws, row: row, enabled: !saving, counted: $counted)
            }
            TextField(text(ui.noteRequired ? "closeday_note_required" : "closeday_note"), text: Binding(get: { note }, set: { note = $0; ws.closeDay.setNote(text: $0) }), axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.roundedBorder)
                .disabled(saving)
            if ui.noteRequired && isBlank(ui.note) {
                Text(text("closeday_note_needed")).font(.caption)
            }
            if case .failed(let p) = onEnum(of: ui.phase) {
                Banner(text: text("closeday_failed") + " " + failureText(p.failure), tone: .error)
            }
            if saving {
                Banner(text: text("working"), tone: .info)
            }
            if ui.blocked == .offline {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
            Button(text("closeday_confirm")) { ws.closeDay.confirm() }
                .buttonStyle(.borderedProminent)
                .disabled(!ui.canClose)
        }
    }
}

private struct CurrencyCount: View {
    let ws: Workspace
    let row: CloseRow
    let enabled: Bool
    @Binding var counted: [String: String]

    private func money(_ minor: Int64) -> String {
        displayAmount(minor: minor, exponent: row.currency.exponent) + " " + row.currency.code
    }

    var body: some View {
        let code = row.currency.code
        Card {
            Text(code).font(.headline)
            TextField(text("closeday_counted", code), text: Binding(
                get: { counted[code] ?? "" },
                set: {
                    counted[code] = $0
                    ws.closeDay.setCounted(code: code, text: $0)
                }
            ))
            .keyboardType(.decimalPad)
            .textFieldStyle(.roundedBorder)
            .disabled(!enabled)
            if row.invalid {
                Text(text("openday_invalid", code)).font(.caption)
            }
            if let expected = row.expected {
                AmountLine(label: text("closeday_expected"), shown: money(expected.int64Value))
            }
            if let variance = row.variance?.int64Value {
                let words = variance == 0 ? text("closeday_matches") : variance < 0 ? text("closeday_short", money(-variance)) : text("closeday_over", money(variance))
                AmountLine(label: text("closeday_variance"), shown: variance == 0 ? words : signedAmount(minor: variance, exponent: row.currency.exponent) + " " + code)
                if variance != 0 {
                    Text(words).font(.caption)
                }
            }
            if row.counted != nil {
                AmountLine(label: text("closeday_keep"), shown: money(row.float))
                AmountLine(label: text("closeday_handover"), shown: money(row.handover?.int64Value ?? 0))
                if row.belowFloor {
                    Banner(text: text("closeday_below_float", money(row.float)), tone: .error)
                }
            }
        }
    }
}

/// What the server worked out when the day closed: the figures are the server's.
private struct ClosedResult: View {
    let ws: Workspace
    let date: String
    let close: SessionClose

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Banner(text: text("closeday_done", date, close.closedBy.name), tone: .success)
            // The server's map has no order that reaches here, so the currencies are in the order of their codes.
            ForEach(close.counted.keys.sorted(), id: \.self) { code in
                let money = { (map: [String: String]) in groupThousands(map[code] ?? "0") + " " + code }
                let variance = close.variance[code] ?? "0"
                let sign = variance.hasPrefix("-") || variance.trimmingCharacters(in: CharacterSet(charactersIn: "0.")).isEmpty ? "" : "+"
                Card {
                    Text(code).font(.headline)
                    AmountLine(label: text("closeday_counted_short"), shown: money(close.counted))
                    AmountLine(label: text("closeday_expected"), shown: money(close.expected))
                    AmountLine(label: text("closeday_variance"), shown: sign + groupThousands(variance) + " " + code)
                    AmountLine(label: text("closeday_handover"), shown: money(close.handover))
                    if close.belowFloor.contains(code) {
                        Banner(text: text("closeday_below_float_done"), tone: .error)
                    }
                }
            }
            if !isBlank(close.note) {
                Text(text("closeday_note_is", close.note)).foregroundColor(.secondary)
            }
            Button(text("go_today")) {
                ws.closeDay.reset()
                ws.go(to: .today)
            }
            .buttonStyle(.borderedProminent)
        }
    }
}
