import SwiftUI
import ClientCore

/// The console: a line to type, what the server said above it. Compose also binds the arrow keys and Tab of a hardware keyboard to earlier lines and completion; here Return runs the line.
struct ConsoleView: View {
    let ws: Workspace
    @State private var ui: ConsoleUi
    // What is typed is kept here as well (see HistoryList). The flow changes the field itself (a run empties it, earlier lines and completion fill it): see the onChange below.
    @State private var input: String

    init(ws: Workspace) {
        self.ws = ws
        let ui = ws.console.ui.value
        _ui = State(initialValue: ui)
        _input = State(initialValue: ui.input)
    }

    var body: some View {
        let c = ws.console
        VStack(alignment: .leading, spacing: 12) {
            if ui.lines.isEmpty {
                Text(text("console_hint")).foregroundColor(.secondary)
            }
            // The newest line stays in view.
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(ui.lines.enumerated()), id: \.offset) { _, line in ConsoleRow(line: line) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onAppear { scrollToEnd(proxy) }
                // The last line, not the count: at the most lines kept, the oldest go as new ones come and the count stays.
                .onChange(of: ui.lines.last.map { ObjectIdentifier($0 as AnyObject) }) { _ in scrollToEnd(proxy) }
            }
            if ui.blocked {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
            HStack(spacing: 8) {
                TextField(text("console_field"), text: Binding(get: { input }, set: { input = $0; c.setInput(text: $0) }))
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.send)
                    .onSubmit { c.send() }
                Button(text("console_run")) { c.send() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!ui.canSend)
            }
        }
        .padding()
        .navigationTitle(text("nav_console"))
        .navigationBarTitleDisplayMode(.inline)
        // The console sits under More (see ReportsView).
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(text("nav_more")) { ws.go(to: .more) }
            }
        }
        .collect(c.ui, into: $ui)
        // The flow's input is read as it is now, not as the (late) UI value: it is what was last typed here unless the flow itself changed it.
        .onChange(of: ui.input) { _ in
            let now = c.ui.value.input
            if now != input { input = now }
        }
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        if !ui.lines.isEmpty { proxy.scrollTo(ui.lines.count - 1, anchor: .bottom) }
    }
}

private struct ConsoleRow: View {
    let line: any ConsoleLine

    var body: some View {
        switch onEnum(of: line) {
        case .typed(let l):
            Text(verbatim: "> \(l.text)").font(.system(.body, design: .monospaced)).frame(maxWidth: .infinity, alignment: .leading)
        case .failed(let l):
            Banner(text: failureText(l.failure), tone: .error)
        case .out(let l):
            Text(l.text).font(.system(.body, design: .monospaced)).foregroundColor(.secondary).frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
