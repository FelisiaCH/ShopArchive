package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.ActionPhase
import xyz.felismp.shoparchive.app.flow.ConfigState
import xyz.felismp.shoparchive.app.flow.DetailUi
import xyz.felismp.shoparchive.app.flow.EditUi
import xyz.felismp.shoparchive.app.flow.EntryDetailState
import xyz.felismp.shoparchive.app.flow.HistoryUi
import xyz.felismp.shoparchive.app.flow.Load
import xyz.felismp.shoparchive.app.flow.RangeProblem
import xyz.felismp.shoparchive.app.flow.RecordIssue
import xyz.felismp.shoparchive.app.flow.SlipNotice
import xyz.felismp.shoparchive.app.flow.TenderRow
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.displayAmount
import xyz.felismp.shoparchive.app.flow.groupThousands
import xyz.felismp.shoparchive.app.flow.parseTypedAmount
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.TenderMethod

@Composable
fun HistoryScreen(ws: Workspace) {
    val detail by ws.history.detail.collectAsState()
    val open = detail
    if (open == null) HistoryList(ws) else EntryDetailScreen(ws, open)
}

@Composable
private fun RangeProblem.text(): String = stringResource(
    when (this) {
        RangeProblem.NOT_A_DATE -> Res.string.range_not_a_date
        RangeProblem.BACKWARDS -> Res.string.range_backwards
        RangeProblem.TOO_LONG -> Res.string.range_too_long
    },
)

@Composable
private fun methodText(method: TenderMethod) = stringResource(if (method == TenderMethod.CASH) Res.string.method_cash else Res.string.method_online)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryList(ws: Workspace) {
    val ui by ws.history.ui.collectAsState()
    val config by ws.config.collectAsState()
    val ready = (config as? ConfigState.Ready)?.config
    ShopPage(stringResource(Res.string.nav_history)) {
        Filters(ws, ui, ready?.currencies.orEmpty().map { it.code }, ready?.categories.orEmpty().filter { !it.archived })
        val entries = ui.entries
        ui.failure?.let {
            ShopBanner(stringResource(Res.string.history_failed) + " " + it.text(), Tone.Error)
            ShopButton(stringResource(Res.string.retry), ws.history::reload)
        }
        when {
            ui.rangeProblem != null -> Unit
            entries == null && ui.failure == null -> ShopText(stringResource(Res.string.working), muted = true)
            entries != null && entries.isEmpty() && ui.failure == null -> ShopText(stringResource(Res.string.history_empty), muted = true)
            entries != null -> {
                if (ui.loading) ShopText(stringResource(Res.string.working), TextRole.Caption, muted = true)
                entries.forEach { EntryRow(ws, it) { ws.history.open(it) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(ws: Workspace, ui: HistoryUi, currencies: List<String>, categories: List<xyz.felismp.shoparchive.shared.CategoryDto>) {
    val h = ws.history
    ShopCard {
        ShopText(stringResource(Res.string.filter_range), TextRole.Title)
        ShopTextField(ui.from, h::setFrom, stringResource(Res.string.filter_from))
        ShopTextField(ui.to, h::setTo, stringResource(Res.string.filter_to))
        ui.rangeProblem?.let { ShopText(it.text(), TextRole.Caption) }
        if (ws.branches.size > 1) {
            ShopText(stringResource(Res.string.branch_label), TextRole.Caption, muted = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopChoice(stringResource(Res.string.filter_all), ui.branch == null, { h.setBranch(null) })
                ws.branches.forEach { b -> ShopChoice(b.displayName, ui.branch == b.key, { h.setBranch(b.key) }) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopChoice(stringResource(Res.string.filter_all), ui.type == null, { h.setType(null) })
            ShopChoice(stringResource(Res.string.type_income), ui.type == EntryType.INCOME, { h.setType(EntryType.INCOME) })
            ShopChoice(stringResource(Res.string.type_expense), ui.type == EntryType.EXPENSE, { h.setType(EntryType.EXPENSE) })
        }
        if (currencies.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopChoice(stringResource(Res.string.filter_all), ui.currency == null, { h.setCurrency(null) })
            currencies.forEach { c -> ShopChoice(c, ui.currency == c, { h.setCurrency(c) }) }
        }
        if (categories.isNotEmpty()) {
            ShopText(stringResource(Res.string.category_label), TextRole.Caption, muted = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopChoice(stringResource(Res.string.filter_all), ui.category == null, { h.setCategory(null) })
                categories.forEach { c -> ShopChoice(c.name.pick(), ui.category == c.key, { h.setCategory(c.key) }) }
            }
        }
        ShopChoice(stringResource(Res.string.filter_deleted), ui.includeDeleted, { h.setIncludeDeleted(!ui.includeDeleted) })
    }
}

@Composable
private fun EntryRow(ws: Workspace, e: EntryDto, onClick: () -> Unit) {
    val sign = if (e.type == EntryType.INCOME) "+" else "-"
    ShopCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ShopText(e.item.ifBlank { ws.categoryName(e.category) ?: "—" })
                ShopText(e.date + " " + clockTime(e.createdAt) + " · " + e.createdBy.name, TextRole.Caption, muted = true)
                if (ws.branches.size > 1) ShopText(ws.branchName(e.branch), TextRole.Caption, muted = true)
                if (e.slips.isNotEmpty()) ShopText(stringResource(Res.string.history_slips, e.slips.size.toString()), TextRole.Caption, muted = true)
                if (e.deleted) ShopChip(stringResource(Res.string.history_deleted), Tone.Error)
            }
            Column(Modifier.weight(1f)) {
                e.tenders.forEach { t ->
                    ShopText(sign + groupThousands(t.amount) + " " + t.currency + " · " + methodText(t.method), TextRole.Amount, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ---- one entry ----

@Composable
private fun EntryDetailScreen(ws: Workspace, state: EntryDetailState) {
    val ui by state.ui.collectAsState()
    val e = ui.entry
    ShopPage(e.item.ifBlank { ws.categoryName(e.category) ?: stringResource(Res.string.nav_history) }) {
        ShopButton(stringResource(Res.string.back), ws.history::closeDetail, primary = false)
        val edit = ui.edit
        if (edit != null) {
            EditForm(ws, state, ui, edit)
            return@ShopPage
        }
        EntryFacts(ws, e)
        Actions(state, ui)
        ShopText(stringResource(Res.string.slips_title), TextRole.Title)
        if (e.slips.isEmpty()) ShopText(stringResource(Res.string.detail_no_slips), muted = true)
        ui.slips.toSortedMap().forEach { (n, load) -> SlipImage(n, load) }
        ShopText(stringResource(Res.string.detail_history), TextRole.Title)
        when (val h = ui.history) {
            Load.Loading -> ShopText(stringResource(Res.string.working), muted = true)
            is Load.Failed -> ShopBanner(h.failure.text(), Tone.Error)
            is Load.Ready -> h.value.forEach { HistoryLine(it) }
        }
    }
}

@Composable
private fun EntryFacts(ws: Workspace, e: EntryDto) {
    ShopCard {
        if (e.deleted) ShopChip(stringResource(Res.string.history_deleted), Tone.Error)
        ShopText(stringResource(if (e.type == EntryType.INCOME) Res.string.type_income else Res.string.type_expense), TextRole.Title)
        ShopText(e.date + " · " + ws.branchName(e.branch) + (ws.categoryName(e.category)?.let { " · $it" } ?: ""))
        if (e.note.isNotBlank()) ShopText(e.note, muted = true)
        ShopText(stringResource(Res.string.detail_created, clockTime(e.createdAt), e.createdBy.name), TextRole.Caption, muted = true)
        if (e.updatedAt != e.createdAt) ShopText(stringResource(Res.string.detail_updated, clockTime(e.updatedAt)), TextRole.Caption, muted = true)
        val sign = if (e.type == EntryType.INCOME) "+" else "-"
        e.tenders.forEach { t ->
            ShopText(sign + groupThousands(t.amount) + " " + t.currency + " · " + methodText(t.method), TextRole.Amount, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(state: EntryDetailState, ui: DetailUi) {
    val busy = ui.action is ActionPhase.Working
    if (!ui.entry.deleted && (ui.canEdit || ui.canDelete)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.canEdit) {
                ShopButton(stringResource(Res.string.detail_edit), state::startEdit, primary = false, enabled = !busy)
                ShopButton(stringResource(Res.string.detail_move), state::openMove, primary = false, enabled = !busy)
            }
            if (ui.canDelete) ShopButton(stringResource(Res.string.detail_delete), state::askDelete, primary = false, enabled = !busy)
        }
        if (!ui.canWrite) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
    }
    if (ui.confirmDelete) ShopCard {
        ShopText(stringResource(Res.string.detail_delete_ask), TextRole.Title)
        ShopText(stringResource(Res.string.detail_delete_body), muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopButton(stringResource(Res.string.detail_delete_yes), state::confirmDelete, enabled = !busy && ui.canWrite)
            ShopButton(stringResource(Res.string.cancel), state::cancelDelete, primary = false)
        }
    }
    ui.moveText?.let { text ->
        ShopCard {
            ShopText(stringResource(Res.string.detail_move_to), TextRole.Title)
            ShopTextField(text, state::setMoveDate, stringResource(Res.string.detail_move_date))
            if (!ui.moveValid) ShopText(stringResource(Res.string.range_not_a_date), TextRole.Caption)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopButton(stringResource(Res.string.detail_move_yes), state::confirmMove, enabled = !busy && ui.moveValid && ui.canWrite)
                ShopButton(stringResource(Res.string.cancel), state::cancelMove, primary = false)
            }
        }
    }
    when (val a = ui.action) {
        ActionPhase.Working -> ShopBanner(stringResource(Res.string.working), Tone.Info)
        is ActionPhase.Failed -> ShopBanner(stringResource(Res.string.detail_failed) + " " + a.failure.text(), Tone.Error)
        ActionPhase.Idle -> Unit
    }
}

@Composable
private fun SlipImage(n: Int, load: Load<ByteArray>) {
    ShopCard {
        ShopText(stringResource(Res.string.detail_slip, n.toString()), TextRole.Caption, muted = true)
        when (load) {
            Load.Loading -> ShopText(stringResource(Res.string.working), muted = true)
            is Load.Failed -> ShopBanner(load.failure.text(), Tone.Error)
            is Load.Ready -> {
                val image = remember(load.value) { decodeImage(load.value) }
                if (image == null) ShopText(stringResource(Res.string.notice_unreadable), muted = true)
                else Image(image, stringResource(Res.string.detail_slip, n.toString()), Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
            }
        }
    }
}

@Composable
private fun HistoryLine(item: HistoryItem) {
    ShopCard {
        ShopText(item.action + " · " + item.at.replace('T', ' ').take(16) + " · " + item.by.name, TextRole.Caption, muted = true)
        item.changes.forEach { c -> ShopText(c.field + ": " + c.from.ifEmpty { "—" } + " → " + c.to.ifEmpty { "—" }, TextRole.Caption) }
    }
}

// ---- edit ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditForm(ws: Workspace, state: EntryDetailState, ui: DetailUi, edit: EditUi) {
    val f = edit.form
    val busy = ui.action is ActionPhase.Working
    val picker = rememberSlipPicker(state::addSlip)
    ShopSegmented(
        listOf(EntryType.INCOME to stringResource(Res.string.type_income), EntryType.EXPENSE to stringResource(Res.string.type_expense)),
        f.type, { if (!busy) state.setType(it) },
    )
    if (edit.categories.isNotEmpty()) {
        ShopText(stringResource(Res.string.category_label), TextRole.Title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!edit.policy.requireCategory) ShopChoice(stringResource(Res.string.category_none), f.category == null, { if (!busy) state.setCategory(null) })
            edit.categories.forEach { c -> ShopChoice(c.name.pick(), f.category == c.key, { if (!busy) state.setCategory(c.key) }) }
        }
    }
    ShopTextField(f.item, state::setItem, stringResource(Res.string.item_label), enabled = !busy)
    ShopTextField(f.note, state::setNote, stringResource(Res.string.note_label), kind = FieldKind.Multiline, enabled = !busy)
    ShopText(stringResource(Res.string.tenders_title), TextRole.Title)
    f.tenders.forEach { EditTender(it, state, edit, busy) }
    ShopButton(stringResource(Res.string.tender_add), state::addTender, primary = false, enabled = !busy)

    if (edit.online || f.keep.isNotEmpty() || f.added.isNotEmpty()) {
        ShopText(stringResource(Res.string.slips_title), TextRole.Title)
        ShopText(stringResource(Res.string.slips_count, (f.keep.size + f.added.size).toString(), edit.policy.slipMaxCount.toString()), TextRole.Caption, muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val camera = picker.takePhoto
            ShopButton(stringResource(if (camera != null) Res.string.slip_gallery else Res.string.slip_file), picker.pickFromLibrary, primary = false, enabled = !busy)
            if (camera != null) ShopButton(stringResource(Res.string.slip_camera), camera, primary = false, enabled = !busy)
        }
        if (edit.compressing) ShopText(stringResource(Res.string.slip_preparing), muted = true)
        f.keep.forEach { slip ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShopText(slip.file, modifier = Modifier.weight(1f))
                ShopButton(stringResource(Res.string.slip_remove, slip.file), { state.removeStoredSlip(slip.file) }, primary = false, enabled = !busy)
            }
        }
        f.added.forEach { slip ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShopText(slip.name + " · " + (slip.bytes.size / 1024 + 1) + " KB", modifier = Modifier.weight(1f))
                ShopButton(stringResource(Res.string.slip_remove, slip.name), { state.removeNewSlip(slip.key) }, primary = false, enabled = !busy)
            }
        }
        when (edit.notice) {
            SlipNotice.TOO_MANY -> ShopBanner(stringResource(Res.string.notice_too_many, edit.policy.slipMaxCount.toString()), Tone.Error)
            SlipNotice.TOO_BIG -> ShopBanner(stringResource(Res.string.notice_too_big, edit.policy.slipMaxSizeKb.toString()), Tone.Error)
            SlipNotice.UNREADABLE -> ShopBanner(stringResource(Res.string.notice_unreadable), Tone.Error)
            null -> Unit
        }
    }
    if (edit.stale) {
        ShopBanner(stringResource(Res.string.detail_stale), Tone.Error)
        EntryFacts(ws, ui.entry)
    }
    edit.issues.firstOrNull()?.let { ShopText(it.text(), TextRole.Caption, muted = true) }
    when (val a = ui.action) {
        ActionPhase.Working -> ShopBanner(stringResource(Res.string.working), Tone.Info)
        is ActionPhase.Failed -> ShopBanner(stringResource(Res.string.detail_failed) + " " + a.failure.text(), Tone.Error)
        ActionPhase.Idle -> Unit
    }
    if (!ui.canWrite) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopButton(stringResource(Res.string.record_save), state::saveEdit, enabled = edit.issues.isEmpty() && !edit.compressing && !edit.stale && !busy && ui.canWrite)
        ShopButton(stringResource(Res.string.cancel), state::cancelEdit, primary = false, enabled = !busy)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditTender(row: TenderRow, state: EntryDetailState, edit: EditUi, busy: Boolean) {
    val currency = edit.currencies.firstOrNull { it.code == row.currency }
    ShopCard {
        if (edit.currencies.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            edit.currencies.forEach { c -> ShopChoice(c.code, c.code == row.currency, { if (!busy) state.setCurrency(row.key, c.code) }) }
        }
        ShopSegmented(
            listOf(TenderMethod.CASH to stringResource(Res.string.method_cash), TenderMethod.ONLINE to stringResource(Res.string.method_online)),
            row.method, { if (!busy) state.setMethod(row.key, it) },
        )
        ShopTextField(row.amount, { state.setAmount(row.key, it) }, stringResource(Res.string.amount_label, row.currency), kind = FieldKind.Amount, enabled = !busy)
        val minor = currency?.let { parseTypedAmount(row.amount, it.exponent) }
        if (currency != null && minor != null) {
            ShopText(stringResource(Res.string.record_amount_shown, displayAmount(minor, currency.exponent) + " " + currency.code), TextRole.Amount, modifier = Modifier.fillMaxWidth())
        } else if (row.key in edit.badRows) {
            ShopText(stringResource(Res.string.issue_amount_invalid), TextRole.Caption)
        }
        if (edit.form.tenders.size > 1) ShopButton(stringResource(Res.string.tender_remove), { state.removeTender(row.key) }, primary = false, enabled = !busy)
    }
}

@Composable
private fun RecordIssue.text(): String = stringResource(
    when (this) {
        RecordIssue.AMOUNT_MISSING -> Res.string.issue_amount_missing
        RecordIssue.AMOUNT_INVALID -> Res.string.issue_amount_invalid
        RecordIssue.SLIP_NEEDED -> Res.string.issue_slip_needed
        RecordIssue.SLIP_NOT_ALLOWED -> Res.string.issue_slip_not_allowed
        RecordIssue.TOO_MANY_SLIPS -> Res.string.issue_too_many_slips
        RecordIssue.CATEGORY_NEEDED -> Res.string.issue_category_needed
    },
)
