package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import xyz.felismp.shoparchive.app.flow.NotesUi
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.shared.NoteDto

/** Notes: the day notes staff leave for the owner, one day at a time, with who read them. */
@Composable
fun NotesScreen(t: Translator, ws: Workspace) {
    val ui by ws.notes.ui.collectAsState()
    Screen(t.t("notes_title")) {
        Note(t.t("notes_sub"))
        Div(attrs = { classes("card", "stack") }) {
            H2 { Text(ui.date) }
            Div(attrs = { classes("row") }) {
                Btn(t.t("notes_prev_day"), primary = false) { ws.notes.previousDay() }
                Btn(t.t("notes_next_day"), primary = false, enabled = !ui.isToday) { ws.notes.nextDay() }
            }
        }
        ui.failure?.let { failure ->
            Banner(t.text(Message("notes_load_failed", detail = failure.message())), error = true)
            Btn(t.t("retry"), action = ws.notes::reload)
        }
        ui.markFailure?.let { Banner(t.text(Message("notes_mark_failed", detail = it.message())), error = true) }
        NoteList(t, ws, ui)
        if (ui.canCreate) Composer(t, ws, ui)
    }
}

@Composable
private fun NoteList(t: Translator, ws: Workspace, ui: NotesUi) {
    val notes = ui.notes
    Div(attrs = { classes("card", "stack") }) {
        H2 { Text(t.t("notes_list_h")) }
        if (notes == null) {
            if (ui.failure == null) Note(t.t("working"))
        } else {
            val count = t.plural("notes_count", notes.size, notes.size.toString())
            val unread = ui.unreadCount
            Note(if (unread > 0) count + " · " + t.t("notes_unread_n", unread.toString()) else count)
            if (ui.canRead && unread > 0) {
                Btn(t.t("notes_mark_all"), primary = false, enabled = ui.connected && ui.marking.isEmpty()) { ws.notes.markAllRead() }
            }
            if (notes.isEmpty()) {
                H2 { Text(t.t("notes_empty_h")) }
                Note(t.t("notes_empty_p"))
            } else {
                notes.forEach { note -> NoteItem(t, ws, ui, note) }
            }
        }
    }
}

/** One note: who wrote it and when, its words, and what the reader or the writer sees under it. */
@Composable
private fun NoteItem(t: Translator, ws: Workspace, ui: NotesUi, note: NoteDto) {
    Div(attrs = { classes("stack") }) {
        Note(note.createdBy.name + " · " + clockTime(note.createdAt))
        if (ui.unread(note)) Span(attrs = { classes("chip") }) { Text(t.t("notes_unread")) }
        P { Text(note.text) }
        when (val foot = noteFoot(ui, note)) {
            is NoteFoot.ReadAt -> Note(t.t("notes_read_at", foot.time))
            is NoteFoot.MarkRead -> Btn(t.t("notes_mark_read"), primary = false, enabled = foot.enabled) { ws.notes.markRead(foot.id) }
            is NoteFoot.Seen -> Note(t.t("notes_seen_owner", foot.time))
            NoteFoot.NotSeen -> Note(t.t("notes_not_seen"))
            NoteFoot.None -> Unit
        }
    }
}

@Composable
private fun Composer(t: Translator, ws: Workspace, ui: NotesUi) {
    Div(attrs = { classes("card", "stack") }) {
        Field(t.t("notes_composer"), ui.draft, ws.notes::setDraft, FieldKind.Multiline, enabled = !ui.posting)
        ui.postFailure?.let { failure ->
            Banner(t.text(Message("notes_post_failed_h", detail = failure.message())), error = true)
            Note(t.t("notes_post_failed_p"))
        }
        if (!ui.connected) Note(t.t("needs_connection"))
        Btn(
            t.t(if (ui.posting) "notes_posting" else if (ui.postFailure != null) "retry" else "notes_post"),
            enabled = ui.canPost,
        ) { ws.notes.post() }
        Note(t.t("notes_foot"))
    }
}
