import SwiftUI
import ClientCore

/// Notes: the day notes staff leave for the owner, one day at a time, with who read them. The page title and the way back to More are here, as for Reports.
struct NotesView: View {
    let ws: Workspace
    @State private var ui: NotesUi

    init(ws: Workspace) {
        self.ws = ws
        _ui = State(initialValue: ws.notes.ui.value)
    }

    var body: some View {
        Page {
            Text(text("notes_sub")).foregroundColor(.secondary)
            Card {
                Text(ui.date).font(.headline)
                HStack(spacing: 8) {
                    Button(text("notes_prev_day")) { ws.notes.previousDay() }
                        .buttonStyle(.bordered)
                    Button(text("notes_next_day")) { ws.notes.nextDay() }
                        .buttonStyle(.bordered)
                        .disabled(ui.isToday)
                }
            }
            if let failure = ui.failure {
                Banner(text: text("notes_load_failed") + " " + failureText(failure), tone: .error)
                Button(text("retry")) { ws.notes.reload() }
                    .buttonStyle(.borderedProminent)
            }
            if let failure = ui.markFailure {
                Banner(text: text("notes_mark_failed") + " " + failureText(failure), tone: .error)
            }
            NoteList(ws: ws, ui: ui)
            if ui.canCreate {
                Composer(ws: ws, ui: ui)
            }
        }
        .navigationTitle(text("notes_title"))
        // Notes sits under More (see ReportsView).
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(text("nav_more")) { ws.go(to: .more) }
            }
        }
        .collect(ws.notes.ui, into: $ui)
    }
}

private struct NoteList: View {
    let ws: Workspace
    let ui: NotesUi

    /// `3 notes`, and `3 notes · 2 unread` when some are.
    private func summary(_ notes: [NoteDto]) -> String {
        let count = text("notes_count", Int32(notes.count))
        if ui.unreadCount > 0 { return "\(count) · \(text("notes_unread_n", ui.unreadCount))" }
        return count
    }

    var body: some View {
        Card {
            Text(text("notes_list_h")).font(.headline)
            if let notes = ui.notes {
                Text(summary(notes)).font(.caption).foregroundColor(.secondary)
                if ui.canRead && ui.unreadCount > 0 {
                    Button(text("notes_mark_all")) { ws.notes.markAllRead() }
                        .buttonStyle(.bordered)
                        .disabled(!ui.connected || !ui.marking.isEmpty)
                }
                if notes.isEmpty {
                    Text(text("notes_empty_h")).font(.headline)
                    Text(text("notes_empty_p")).foregroundColor(.secondary)
                } else {
                    ForEach(notes, id: \.id) { NoteRow(ws: ws, ui: ui, note: $0) }
                }
            } else if ui.failure == nil {
                Text(text("working")).foregroundColor(.secondary)
            }
        }
    }
}

private struct NoteRow: View {
    let ws: Workspace
    let ui: NotesUi
    let note: NoteDto

    var body: some View {
        let own = note.createdBy.id == ui.userId
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: "\(note.createdBy.name) · \(clockTime(note.createdAt))").font(.caption).foregroundColor(.secondary)
            if ui.unread(note: note) {
                Chip(text: text("notes_unread"), tone: .info)
            }
            Text(note.text)
            if ui.canRead && !own {
                // The owner: when they read it, or the button that says they did.
                if let at = ui.readAt(note: note) {
                    Text(text("notes_read_at", clockTime(at))).font(.caption).foregroundColor(.secondary)
                } else {
                    Button(text("notes_mark_read")) { ws.notes.markRead(id: note.id) }
                        .buttonStyle(.bordered)
                        .disabled(!ui.connected || ui.marking.contains(note.id))
                }
            } else if own && !ui.canRead {
                // The writer: whether the owner has seen it.
                if let seen = note.reads.first(where: { $0.by.id != ui.userId }) {
                    Text(text("notes_seen_owner", clockTime(seen.at))).font(.caption).foregroundColor(.secondary)
                } else {
                    Text(text("notes_not_seen")).font(.caption).foregroundColor(.secondary)
                }
            }
        }
        .padding(.top, 8)
    }
}

private struct Composer: View {
    let ws: Workspace
    let ui: NotesUi
    // What is typed is kept here as well (see HistoryList). The flow empties the draft once the note is posted, so the typed copy follows it: see the onChange below.
    @State private var draft: String

    init(ws: Workspace, ui: NotesUi) {
        self.ws = ws
        self.ui = ui
        _draft = State(initialValue: ui.draft)
    }

    private var postLabel: String {
        if ui.posting { return text("notes_posting") }
        return ui.postFailure != nil ? text("retry") : text("notes_post")
    }

    var body: some View {
        Card {
            TextField(text("notes_composer"), text: Binding(get: { draft }, set: { draft = $0; ws.notes.setDraft(text: $0) }), axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.roundedBorder)
                .disabled(ui.posting)
            if let failure = ui.postFailure {
                Banner(text: text("notes_post_failed_h") + " " + failureText(failure), tone: .error)
                Text(text("notes_post_failed_p")).font(.caption).foregroundColor(.secondary)
            }
            if !ui.connected {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
            Button(postLabel) { ws.notes.post() }
                .buttonStyle(.borderedProminent)
                .disabled(!ui.canPost)
            Text(text("notes_foot")).font(.caption).foregroundColor(.secondary)
        }
        // The flow's draft is read as it is now, not as the (late) UI value: it is what was last typed here unless the flow itself changed it.
        .onChange(of: ui.draft) { _ in
            let now = ws.notes.ui.value.draft
            if now != draft { draft = now }
        }
    }
}
