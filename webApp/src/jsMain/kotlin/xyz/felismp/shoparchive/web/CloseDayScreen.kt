package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.Workspace

/** Close day: the money in the drawer per currency, compared with what the server expects, then the day is closed. */
@Composable
fun CloseDayScreen(t: Translator, ws: Workspace) {
    val ui by ws.closeDay.ui.collectAsState()
    val result = ui.result
    val close = result?.close
    val goToday = { ws.closeDay.reset(); ws.go(Destination.TODAY) }
    when {
        result != null && close != null -> {
            val view = closeResultView(result.businessDate, close)
            Screen(t.t("closeday_btn")) {
                Banner(t.text(view.done))
                view.cards.forEach { card ->
                    Div(attrs = { classes("card", "stack") }) {
                        H2 { Text(card.code) }
                        AmountLine(t.t("closeday_counted_short"), card.counted)
                        AmountLine(t.t("closeday_expected"), card.expected)
                        AmountLine(t.t("closeday_variance"), card.variance)
                        AmountLine(t.t("closeday_handover"), card.handover)
                        if (card.belowFloor) Banner(t.t("closeday_below_float_done"), error = true)
                    }
                }
                view.note?.let { Note(t.text(it)) }
                Btn(t.t("go_today")) { goToday() }
            }
        }
        ui.session == null -> Screen(t.t("closeday_btn")) {
            Note(t.t("closeday_none"))
            Btn(t.t("go_today"), primary = false) { goToday() }
        }
        else -> {
            val view = closeDayView(ui)
            Screen(t.t("closeday_btn")) {
                Note(t.text(view.hint))
                view.previewBanner?.let { Banner(t.text(it)) }
                SubmitForm({ if (view.confirmEnabled) ws.closeDay.confirm() }) {
                    view.rows.forEach { row ->
                        Div(attrs = { classes("card", "stack") }) {
                            H2 { Text(row.code) }
                            Field(t.t("closeday_counted", row.code), row.text, { ws.closeDay.setCounted(row.code, it) }, FieldKind.Amount, enabled = !view.saving)
                            if (row.invalid) Note(t.t("openday_invalid", row.code))
                            row.expected?.let { AmountLine(t.t("closeday_expected"), it) }
                            row.variance?.let { variance ->
                                AmountLine(t.t("closeday_variance"), variance.text ?: t.text(variance.words))
                                if (variance.text != null) Note(t.text(variance.words))
                            }
                            row.keep?.let { AmountLine(t.t("closeday_keep"), it) }
                            row.handover?.let { AmountLine(t.t("closeday_handover"), it) }
                            if (row.belowFloor) {
                                val keep = row.keep
                                Banner(t.t("closeday_below_float", keep.orEmpty()), error = true)
                            }
                        }
                    }
                    Field(t.t(view.noteLabel), ui.note, ws.closeDay::setNote, FieldKind.Multiline, enabled = !view.saving)
                    if (view.noteNeeded) Note(t.t("closeday_note_needed"))
                    view.banner?.let { Banner(t.text(it.message), it.error) }
                    if (view.needsConnection) Note(t.t("needs_connection"))
                    Btn(t.t("closeday_confirm"), enabled = view.confirmEnabled, submit = true)
                }
            }
        }
    }
}

@Composable
private fun AmountLine(label: String, value: String) {
    Div(attrs = { classes("amount") }) {
        Span { Text(label) }
        Span { Text(value) }
    }
}
