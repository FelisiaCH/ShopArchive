package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.events.*
import xyz.felismp.shoparchive.app.flow.ActionPhase
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.DetailUi
import xyz.felismp.shoparchive.app.flow.EntryDetailState
import xyz.felismp.shoparchive.app.flow.Load
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.groupThousands
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType

/** History: the entries of a date range, and one entry with its slips and the changes it allows. */
@Composable
fun HistoryScreen(t: Translator, ws: Workspace) {
    val detail by ws.history.detail.collectAsState()
    val open = detail
    if (open == null) HistoryList(t, ws) else EntryDetailScreen(t, ws, open)
}

@Composable
private fun HistoryList(t: Translator, ws: Workspace) {
    val ui by ws.history.ui.collectAsState()
    val config by ws.config.collectAsState()
    val categories = (config as? ConfigState.Ready)?.config?.categories.orEmpty()
    Screen(t.t("nav_history")) {
        Div(attrs = { classes("card", "stack") }) {
            H2 { Text(t.t("filter_range")) }
            Field(t.t("filter_from"), ui.from, ws.history::setFrom, FieldKind.Plain)
            Field(t.t("filter_to"), ui.to, ws.history::setTo, FieldKind.Plain)
            ui.rangeProblem?.let { Note(t.t(rangeProblemKey(it))) }
        }
        ui.failure?.let { failure ->
            Banner(t.t("history_failed") + " " + t.text(failure.message()), error = true)
            Btn(t.t("retry"), action = ws.history::reload)
        }
        val entries = ui.entries
        when {
            ui.rangeProblem != null -> Unit
            entries == null -> if (ui.failure == null) Banner(t.t("working"))
            entries.isEmpty() && ui.failure == null -> Note(t.t("history_empty"))
            else -> {
                if (ui.loading) Banner(t.t("working"))
                historyGroups(entries).forEach { group ->
                    H2 { Text(group.date) }
                    group.entries.forEach { entry -> HistoryRow(t, ws, entry, categories) }
                }
            }
        }
    }
}

/** One entry of the list; the whole row opens it. */
@Composable
private fun HistoryRow(t: Translator, ws: Workspace, e: EntryDto, categories: List<CategoryDto>) {
    val sign = if (e.type == EntryType.INCOME) "+" else "-"
    Button(attrs = {
        classes("line", "clickable")
        type(ButtonType.Button)
        onClick { ws.history.open(e) }
    }) {
        Span(attrs = { classes("stack") }) {
            Span { Text(historyRowTitle(e, categories, t.language)) }
            Span(attrs = { classes("muted") }) { Text(clockTime(e.createdAt) + " · " + e.createdBy.name) }
            if (e.deleted) Span(attrs = { classes("chip", "error") }) { Text(t.t("history_deleted")) }
        }
        Span(attrs = { classes("stack") }) {
            e.tenders.forEach { tender ->
                Span(attrs = { classes("amount", if (e.type == EntryType.INCOME) "in" else "out") }) {
                    Text(sign + groupThousands(tender.amount) + " " + tender.currency)
                }
            }
        }
    }
}

@Composable
private fun EntryDetailScreen(t: Translator, ws: Workspace, state: EntryDetailState) {
    val ui by state.ui.collectAsState()
    val config by ws.config.collectAsState()
    val categories = (config as? ConfigState.Ready)?.config?.categories.orEmpty()
    val e = ui.entry
    Screen(e.item.ifBlank { categoryName(categories, e.category, t.language) ?: t.t("nav_history") }) {
        Btn(t.t("back"), primary = false, action = ws.history::closeDetail)
        EntryFacts(t, ws, e, categories)
        Actions(t, state, ui)
        if (ui.slips.isNotEmpty()) {
            H2 { Text(t.t("slips_title")) }
            ui.slips.entries.sortedBy { it.key }.forEach { (n, load) -> SlipImage(t, n, load) }
        }
    }
}

/** The entry's own facts: what it is, where, its amounts and how they were paid. */
@Composable
private fun EntryFacts(t: Translator, ws: Workspace, e: EntryDto, categories: List<CategoryDto>) {
    val income = e.type == EntryType.INCOME
    val sign = if (income) "+" else "-"
    Div(attrs = { classes("card", "stack") }) {
        if (e.deleted) Span(attrs = { classes("chip", "error") }) { Text(t.t("history_deleted")) }
        P(attrs = { classes("title") }) { Text(t.t(if (income) "type_income" else "type_expense")) }
        if (e.item.isNotBlank()) P { Text(e.item) }
        val category = categoryName(categories, e.category, t.language)
        P { Text(e.date + " · " + ws.branchName(e.branch) + (category?.let { " · $it" } ?: "")) }
        if (e.note.isNotBlank()) Note(e.note)
        Note(t.t("detail_created", clockTime(e.createdAt), e.createdBy.name))
        if (e.updatedAt != e.createdAt) Note(t.t("detail_updated", clockTime(e.updatedAt)))
        e.tenders.forEach { tender ->
            Div(attrs = { classes("amount", if (income) "in" else "out") }) {
                Text(sign + groupThousands(tender.amount) + " " + tender.currency)
            }
            Note(t.t(methodKey(tender.method)))
        }
    }
}

/** Delete and move, each with its own question; edit is not offered on the web yet. */
@Composable
private fun Actions(t: Translator, state: EntryDetailState, ui: DetailUi) {
    val busy = ui.action is ActionPhase.Working
    if (!ui.entry.deleted && (ui.canEdit || ui.canDelete)) {
        Div(attrs = { classes("row") }) {
            if (ui.canEdit) Btn(t.t("detail_move"), primary = false, enabled = !busy) { state.openMove() }
            if (ui.canDelete) Btn(t.t("detail_delete"), primary = false, enabled = !busy) { state.askDelete() }
        }
        if (!ui.canWrite) Note(t.t("needs_connection"))
    }
    if (ui.confirmDelete) {
        Div(attrs = { classes("card", "stack") }) {
            H2 { Text(t.t("detail_delete_ask")) }
            Note(t.t("detail_delete_body"))
            Div(attrs = { classes("row") }) {
                Btn(t.t("detail_delete_yes"), enabled = !busy && ui.canWrite) { state.confirmDelete() }
                Btn(t.t("cancel"), primary = false) { state.cancelDelete() }
            }
        }
    }
    val moveText = ui.moveText
    if (moveText != null) {
        Div(attrs = { classes("card", "stack") }) {
            H2 { Text(t.t("detail_move_to")) }
            Field(t.t("detail_move_date"), moveText, state::setMoveDate, FieldKind.Plain)
            if (!ui.moveValid) Note(t.t("range_not_a_date"))
            Div(attrs = { classes("row") }) {
                Btn(t.t("detail_move_yes"), enabled = !busy && ui.moveValid && ui.canWrite) { state.confirmMove() }
                Btn(t.t("cancel"), primary = false) { state.cancelMove() }
            }
        }
    }
    when (val action = ui.action) {
        ActionPhase.Working -> Banner(t.t("working"))
        is ActionPhase.Failed -> Banner(t.text(notDoneMessage(action.failure)), error = true)
        ActionPhase.Idle -> Unit
    }
}

@Composable
private fun SlipImage(t: Translator, n: Int, load: Load<ByteArray>) {
    val label = t.t("detail_slip", n.toString())
    Div(attrs = { classes("card", "stack") }) {
        Note(label)
        when (load) {
            Load.Loading -> Note(t.t("working"))
            is Load.Failed -> Banner(t.text(load.failure.message()), error = true)
            is Load.Ready -> BlobImage(load.value, load.value, label)
        }
    }
}
