package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.NotesUi
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.NoteDto

/** Notes: the day notes staff leave for the owner, one day at a time, with who read them. */
@Composable
fun NotesScreen(ws: Workspace) {
    val ui by ws.notes.ui.collectAsState()
    ShopPage(stringResource(Res.string.notes_title)) {
        ShopText(stringResource(Res.string.notes_sub), muted = true)
        ShopCard {
            ShopText(ui.date, TextRole.Title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShopButton(stringResource(Res.string.notes_prev_day), ws.notes::previousDay, primary = false)
                ShopButton(stringResource(Res.string.notes_next_day), ws.notes::nextDay, primary = false, enabled = !ui.isToday)
            }
        }
        ui.failure?.let {
            ShopBanner(stringResource(Res.string.notes_load_failed) + " " + it.text(), Tone.Error)
            ShopButton(stringResource(Res.string.retry), ws.notes::reload)
        }
        ui.markFailure?.let { ShopBanner(stringResource(Res.string.notes_mark_failed) + " " + it.text(), Tone.Error) }
        NoteList(ws, ui)
        if (ui.canCreate) Composer(ws, ui)
    }
}

@Composable
private fun NoteList(ws: Workspace, ui: NotesUi) {
    val notes = ui.notes
    ShopCard {
        ShopText(stringResource(Res.string.notes_list_h), TextRole.Title)
        if (notes == null) {
            if (ui.failure == null) ShopText(stringResource(Res.string.working), muted = true)
            return@ShopCard
        }
        val count = pluralStringResource(Res.plurals.notes_count, notes.size, notes.size)
        val unread = ui.unreadCount
        ShopText(if (unread > 0) count + " · " + stringResource(Res.string.notes_unread_n, unread) else count, TextRole.Caption, muted = true)
        if (ui.canRead && unread > 0) {
            ShopButton(stringResource(Res.string.notes_mark_all), ws.notes::markAllRead, primary = false, enabled = ui.connected && ui.marking.isEmpty())
        }
        if (notes.isEmpty()) {
            ShopText(stringResource(Res.string.notes_empty_h), TextRole.Title)
            ShopText(stringResource(Res.string.notes_empty_p), muted = true)
        } else {
            notes.forEachIndexed { i, note ->
                if (i > 0) ShopDivider()
                Note(ws, ui, note)
            }
        }
    }
}

@Composable
private fun Note(ws: Workspace, ui: NotesUi, note: NoteDto) {
    ShopText(note.createdBy.name + " · " + clockTime(note.createdAt), TextRole.Caption, muted = true)
    if (ui.unread(note)) ShopChip(stringResource(Res.string.notes_unread), Tone.Info)
    ShopText(note.text)
    val own = note.createdBy.id == ui.userId
    when {
        // The owner: when they read it, or the button that says they did.
        ui.canRead && !own -> ui.readAt(note)?.let { ShopText(stringResource(Res.string.notes_read_at, clockTime(it)), TextRole.Caption, muted = true) }
            ?: ShopButton(stringResource(Res.string.notes_mark_read), { ws.notes.markRead(note.id) }, primary = false, enabled = ui.connected && note.id !in ui.marking)
        // The writer: whether the owner has seen it.
        own && !ui.canRead -> {
            val seen = note.reads.firstOrNull { it.by.id != ui.userId }
            ShopText(
                if (seen != null) stringResource(Res.string.notes_seen_owner, clockTime(seen.at)) else stringResource(Res.string.notes_not_seen),
                TextRole.Caption, muted = true,
            )
        }
    }
}

@Composable
private fun Composer(ws: Workspace, ui: NotesUi) {
    ShopCard {
        ShopTextField(ui.draft, ws.notes::setDraft, stringResource(Res.string.notes_composer), kind = FieldKind.Multiline, enabled = !ui.posting)
        ui.postFailure?.let {
            ShopBanner(stringResource(Res.string.notes_post_failed_h) + " " + it.text(), Tone.Error)
            ShopText(stringResource(Res.string.notes_post_failed_p), TextRole.Caption, muted = true)
        }
        if (!ui.connected) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
        ShopButton(
            stringResource(if (ui.posting) Res.string.notes_posting else if (ui.postFailure != null) Res.string.retry else Res.string.notes_post),
            ws.notes::post, enabled = ui.canPost,
        )
        ShopText(stringResource(Res.string.notes_foot), TextRole.Caption, muted = true)
    }
}
