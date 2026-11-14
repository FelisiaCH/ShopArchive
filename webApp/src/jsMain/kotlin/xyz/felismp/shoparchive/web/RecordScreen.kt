package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Promise
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.events.*
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.RecordState
import xyz.felismp.shoparchive.app.flow.RecordUi
import xyz.felismp.shoparchive.app.flow.SaveBlock
import xyz.felismp.shoparchive.app.flow.SavePhase
import xyz.felismp.shoparchive.app.flow.TenderRow
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.TenderMethod

/** Record: one entry at a time, with the same rules as the apps. Slip photos are picked from the page; there is no QR scan on the web. */
@Composable
fun RecordScreen(t: Translator, ws: Workspace) {
    val ui by ws.record.ui.collectAsState()
    val can by ws.capabilities.collectAsState()
    val record = ws.record
    val d = ui.draft
    val editable = !ui.frozen
    Screen(t.t("record_title")) {
        if (ui.block == SaveBlock.NO_DAY) {
            Banner(t.t(if (can.openDay) "block_no_day" else "block_no_day_other"))
            if (can.openDay) Btn(t.t("openday_btn"), primary = false) { ws.go(Destination.OPEN_DAY) }
        }
        SubmitForm({ if (ui.canSave) record.save() }) {
            Div(attrs = { classes("row") }) {
                Btn(t.t("type_income"), primary = d.type == EntryType.INCOME, enabled = editable, pressed = d.type == EntryType.INCOME) { record.setType(EntryType.INCOME) }
                Btn(t.t("type_expense"), primary = d.type == EntryType.EXPENSE, enabled = editable, pressed = d.type == EntryType.EXPENSE) { record.setType(EntryType.EXPENSE) }
            }
            if (ui.categories.isNotEmpty()) {
                H2 { Text(t.t("category_label")) }
                Div(attrs = { classes("row") }) {
                    if (!ui.policy.requireCategory) Btn(t.t("category_none"), primary = d.category == null, enabled = editable, pressed = d.category == null) { record.setCategory(null) }
                    ui.categories.forEach { c ->
                        Btn(c.name.pick(t.language), primary = d.category == c.key, enabled = editable, pressed = d.category == c.key) { record.setCategory(c.key) }
                    }
                }
            }
            Field(t.t("item_label"), d.item, record::setItem, FieldKind.Plain, enabled = editable)
            if (ui.suggestions.isNotEmpty() && editable) {
                Div(attrs = { classes("row") }) {
                    ui.suggestions.forEach { s -> Btn(s, primary = false, enabled = editable) { record.setItem(s) } }
                }
            }
            Field(t.t("note_label"), d.note, record::setNote, FieldKind.Plain, enabled = editable)

            H2 { Text(t.t("tenders_title")) }
            d.tenders.forEach { row -> TenderCard(t, ui, row, record, editable) }
            Btn(t.t("tender_add"), primary = false, enabled = editable) { record.addTender() }

            if (ui.online || d.slips.isNotEmpty()) Slips(t, ws, ui, record, editable)

            val phase = recordPhaseView(ui, t::text) { ws.branchName(it) }
            phase.banner?.let { Banner(t.text(it), phase.error) }
            phase.note?.let { Note(t.text(it)) }
            if (phase.checkAgain || phase.discard) {
                Div(attrs = { classes("row") }) {
                    if (phase.checkAgain) Btn(t.t("record_check_again"), enabled = phase.checkEnabled) { record.checkAgain() }
                    if (phase.discard) Btn(t.t("record_discard"), primary = false) { record.discardDraft() }
                }
            }
            if (ui.phase !is SavePhase.Saved) ui.issues.firstOrNull()?.let { Note(t.t(recordIssueKey(it))) }
            if (ui.block == SaveBlock.OFFLINE) Note(t.t("needs_connection"))
            Btn(t.t("record_save"), enabled = ui.canSave, submit = true)
        }
    }
}

/** One payment line: its currency and method, the amount typed, and what the server will read from it. */
@Composable
private fun TenderCard(t: Translator, ui: RecordUi, row: TenderRow, record: RecordState, editable: Boolean) {
    Div(attrs = { classes("card", "stack") }) {
        if (ui.currencies.size > 1) {
            Div(attrs = { classes("row") }) {
                ui.currencies.forEach { c -> Btn(c.code, primary = c.code == row.currency, enabled = editable, pressed = c.code == row.currency) { record.setCurrency(row.key, c.code) } }
            }
        }
        Div(attrs = { classes("row") }) {
            Btn(t.t("method_cash"), primary = row.method == TenderMethod.CASH, enabled = editable, pressed = row.method == TenderMethod.CASH) { record.setMethod(row.key, TenderMethod.CASH) }
            Btn(t.t("method_online"), primary = row.method == TenderMethod.ONLINE, enabled = editable, pressed = row.method == TenderMethod.ONLINE) { record.setMethod(row.key, TenderMethod.ONLINE) }
        }
        Field(t.t("amount_label", row.currency), row.amount, { record.setAmount(row.key, it) }, FieldKind.Amount, enabled = editable)
        val shown = tenderShown(row, ui.currencies)
        if (shown != null) Div(attrs = { classes("amount") }) { Text(t.t("record_amount_shown", shown)) }
        else if (row.key in ui.badRows) Note(t.t("issue_amount_invalid"))
        if (ui.draft.tenders.size > 1) Btn(t.t("tender_remove"), primary = false, enabled = editable) { record.removeTender(row.key) }
    }
}

/** The slip photos of the draft: one file picker, a preview each, and what the bank said about them. */
@Composable
private fun Slips(t: Translator, ws: Workspace, ui: RecordUi, record: RecordState, editable: Boolean) {
    val scope = rememberCoroutineScope()
    H2 { Text(t.t("slips_title")) }
    Note(t.t("slips_count", ui.draft.slips.size.toString(), ui.policy.slipMaxCount.toString()))
    Label(attrs = { classes("field") }) {
        Span { Text(t.t("slip_file")) }
        Input(type = InputType.File, attrs = {
            attr("accept", "image/*")
            attr("multiple", "")
            if (!editable) disabled()
            onChange { event ->
                val input = event.nativeEvent.target.unsafeCast<HTMLInputElement>()
                val files = input.files
                if (files != null) {
                    val picked = (0 until files.length).map { files.item(it)!! }
                    input.value = ""
                    scope.launch {
                        for (file in picked) {
                            val buffer: ArrayBuffer = file.asDynamic().arrayBuffer().unsafeCast<Promise<ArrayBuffer>>().await()
                            record.addSlip(file.name, Int8Array(buffer).unsafeCast<ByteArray>())
                        }
                    }
                }
            }
        })
    }
    if (ui.compressing) Note(t.t("slip_preparing"))
    ui.draft.slips.forEachIndexed { i, slip ->
        val label = t.t("detail_slip", (i + 1).toString())
        Div(attrs = { classes("card", "stack") }) {
            Div(attrs = { classes("line") }) {
                Span { Text(label + " · " + (slip.bytes.size / 1024 + 1) + " KB") }
                Btn(t.t("slip_remove", label), primary = false, enabled = editable) { record.removeSlip(slip.key) }
            }
            BlobImage(slip.key, slip.bytes, label)
            slip.uses.firstOrNull()?.let { use ->
                Banner(t.t("slip_used_before", use.date, ws.branchName(use.branch), use.createdBy.name), error = true)
            }
            if (slip.twiceHere) Banner(t.t("slip_attached_twice"), error = true)
            slip.check?.let { check ->
                val banner = slipCheckBanner(check) { t.t("slip_check_to", it) }
                Banner(t.text(banner.message), banner.error)
            }
        }
    }
    ui.slipNotice?.let { Banner(t.text(slipNoticeMessage(it, ui.policy)), error = true) }
}
