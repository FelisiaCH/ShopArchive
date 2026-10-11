package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.RecordIssue
import xyz.felismp.shoparchive.app.flow.RecordUi
import xyz.felismp.shoparchive.app.flow.SaveBlock
import xyz.felismp.shoparchive.app.flow.SavePhase
import xyz.felismp.shoparchive.app.flow.SlipNotice
import xyz.felismp.shoparchive.app.flow.TenderRow
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.displayAmount
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.app.flow.parseTypedAmount

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(ws: Workspace) {
    val ui by ws.record.ui.collectAsState()
    val can by ws.capabilities.collectAsState()
    val record = ws.record
    val d = ui.draft
    val picker = rememberSlipPicker(record::addSlip)
    val editable = !ui.frozen
    ShopPage(stringResource(Res.string.record_title)) {
        if (ui.block == SaveBlock.NO_DAY) {
            ShopBanner(stringResource(if (can.openDay) Res.string.block_no_day else Res.string.block_no_day_other), Tone.Info)
            if (can.openDay) ShopButton(stringResource(Res.string.openday_btn), { ws.go(Destination.OPEN_DAY) }, primary = false, icon = ShopIcon.OpenDay)
        }
        ShopSegmented(
            listOf(EntryType.INCOME to stringResource(Res.string.type_income), EntryType.EXPENSE to stringResource(Res.string.type_expense)),
            d.type, { if (editable) record.setType(it) },
        )

        if (ui.categories.isNotEmpty()) {
            ShopText(stringResource(Res.string.category_label), TextRole.Title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ui.policy.requireCategory) ShopChoice(stringResource(Res.string.category_none), d.category == null, { if (editable) record.setCategory(null) })
                ui.categories.forEach { c -> ShopChoice(c.name.pick(), d.category == c.key, { if (editable) record.setCategory(c.key) }) }
            }
        }

        ShopTextField(d.item, record::setItem, stringResource(Res.string.item_label), enabled = editable)
        if (ui.suggestions.isNotEmpty() && editable) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ui.suggestions.forEach { ShopChoice(it, false, { record.setItem(it) }) } }
        }
        ShopTextField(d.note, record::setNote, stringResource(Res.string.note_label), kind = FieldKind.Multiline, enabled = editable)

        ShopText(stringResource(Res.string.tenders_title), TextRole.Title)
        d.tenders.forEach { TenderCard(it, ui, ws, editable) }
        ShopButton(stringResource(Res.string.tender_add), record::addTender, primary = false, enabled = editable, icon = ShopIcon.AddPayment)

        if (ui.online || d.slips.isNotEmpty()) {
            ShopText(stringResource(Res.string.slips_title), TextRole.Title)
            ShopText(stringResource(Res.string.slips_count, d.slips.size.toString(), ui.policy.slipMaxCount.toString()), TextRole.Caption, muted = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val camera = picker.takePhoto
                ShopButton(stringResource(if (camera != null) Res.string.slip_gallery else Res.string.slip_file), picker.pickFromLibrary, primary = false, enabled = editable)
                if (camera != null) ShopButton(stringResource(Res.string.slip_camera), camera, primary = false, enabled = editable)
            }
            if (ui.compressing) ShopText(stringResource(Res.string.slip_preparing), muted = true)
            d.slips.forEach { slip ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShopText(slip.name + " · " + (slip.bytes.size / 1024 + 1) + " KB", modifier = Modifier.weight(1f))
                    ShopButton(stringResource(Res.string.slip_remove, slip.name), { record.removeSlip(slip.key) }, primary = false, enabled = editable)
                }
            }
            when (ui.slipNotice) {
                SlipNotice.TOO_MANY -> ShopBanner(stringResource(Res.string.notice_too_many, ui.policy.slipMaxCount.toString()), Tone.Error)
                SlipNotice.TOO_BIG -> ShopBanner(stringResource(Res.string.notice_too_big, ui.policy.slipMaxSizeKb.toString()), Tone.Error)
                SlipNotice.UNREADABLE -> ShopBanner(stringResource(Res.string.notice_unreadable), Tone.Error)
                null -> Unit
            }
        }

        PhaseNotice(ui, ws)
        ui.issues.firstOrNull()?.let { if (ui.phase !is SavePhase.Saved) ShopText(it.text(), TextRole.Caption, muted = true) }
        if (ui.block == SaveBlock.OFFLINE) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
        ShopButton(stringResource(Res.string.record_save), record::save, Modifier.fillMaxWidth(), enabled = ui.canSave, icon = ShopIcon.Save)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TenderCard(row: TenderRow, ui: RecordUi, ws: Workspace, editable: Boolean) {
    val record = ws.record
    val currency = ui.currencies.firstOrNull { it.code == row.currency }
    ShopCard {
        if (ui.currencies.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.currencies.forEach { c -> ShopChoice(c.code, c.code == row.currency, { if (editable) record.setCurrency(row.key, c.code) }) }
            }
        }
        ShopSegmented(
            listOf(TenderMethod.CASH to stringResource(Res.string.method_cash), TenderMethod.ONLINE to stringResource(Res.string.method_online)),
            row.method, { if (editable) record.setMethod(row.key, it) },
        )
        ShopTextField(row.amount, { record.setAmount(row.key, it) }, stringResource(Res.string.amount_label, row.currency), kind = FieldKind.Amount, enabled = editable)
        val minor = currency?.let { parseTypedAmount(row.amount, it.exponent) }
        if (currency != null && minor != null) {
            ShopText(stringResource(Res.string.record_amount_shown, displayAmount(minor, currency.exponent) + " " + currency.code), TextRole.Amount, modifier = Modifier.fillMaxWidth())
        } else if (row.key in ui.badRows) {
            ShopText(stringResource(Res.string.issue_amount_invalid), TextRole.Caption)
        }
        if (ui.draft.tenders.size > 1) ShopButton(stringResource(Res.string.tender_remove), { record.removeTender(row.key) }, primary = false, enabled = editable)
    }
}

@Composable
private fun PhaseNotice(ui: RecordUi, ws: Workspace) {
    when (val phase = ui.phase) {
        SavePhase.Saving -> ShopBanner(stringResource(Res.string.record_saving), Tone.Info)
        is SavePhase.Saved -> ShopBanner(stringResource(Res.string.record_saved), Tone.Success)
        is SavePhase.Unknown -> {
            ShopBanner(stringResource(Res.string.record_unknown), Tone.Info)
            ui.pendingBranch?.let { key -> ShopText(stringResource(Res.string.record_unknown_branch, ws.branches.firstOrNull { it.key == key }?.displayName ?: key), TextRole.Caption) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopButton(stringResource(Res.string.record_check_again), ws.record::checkAgain, enabled = ui.connected)
                ShopButton(stringResource(Res.string.record_discard), ws.record::discardDraft, primary = false)
            }
        }
        is SavePhase.Failed -> ShopBanner(stringResource(Res.string.record_failed, phase.failure.text()), Tone.Error)
        SavePhase.Editing -> Unit
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
