package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.CloseBlock
import xyz.felismp.shoparchive.app.flow.CloseRow
import xyz.felismp.shoparchive.app.flow.ClosePhase
import xyz.felismp.shoparchive.app.flow.CloseUi
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.displayAmount
import xyz.felismp.shoparchive.app.flow.groupThousands
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.SessionClose

/** Reports › Close day. The page title and tabs are the Reports screen's. */
@Composable
fun CloseDaySection(ws: Workspace) {
    val ui by ws.closeDay.ui.collectAsState()
    val result = ui.result
    when {
        result?.close != null -> ClosedResult(ws, result.businessDate, result.close!!)
        ui.session == null -> {
            ShopText(stringResource(Res.string.closeday_none), muted = true)
            ShopButton(stringResource(Res.string.go_today), { ws.go(Destination.TODAY) }, primary = false)
        }
        else -> CloseForm(ws, ui)
    }
}

@Composable
private fun CloseForm(ws: Workspace, ui: CloseUi) {
    val session = ui.session ?: return
    val saving = ui.phase is ClosePhase.Saving
    ShopText(stringResource(Res.string.closeday_hint, session.businessDate), muted = true)
    ui.previewFailure?.let { ShopBanner(stringResource(Res.string.closeday_no_preview) + " " + it.text(), Tone.Info) }
    ui.rows.forEach { row -> CurrencyCount(ws, row, !saving) }
    ShopTextField(
        ui.note, ws.closeDay::setNote,
        stringResource(if (ui.noteRequired) Res.string.closeday_note_required else Res.string.closeday_note), kind = FieldKind.Multiline, enabled = !saving,
    )
    if (ui.noteRequired && ui.note.isBlank()) ShopText(stringResource(Res.string.closeday_note_needed), TextRole.Caption)
    (ui.phase as? ClosePhase.Failed)?.let { ShopBanner(stringResource(Res.string.closeday_failed) + " " + it.failure.text(), Tone.Error) }
    if (saving) ShopBanner(stringResource(Res.string.working), Tone.Info)
    if (ui.blocked == CloseBlock.OFFLINE) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
    ShopButton(stringResource(Res.string.closeday_confirm), ws.closeDay::confirm, Modifier.fillMaxWidth(), enabled = ui.canClose, icon = ShopIcon.CloseDay)
}

@Composable
private fun CurrencyCount(ws: Workspace, row: CloseRow, enabled: Boolean) {
    val code = row.currency.code
    val e = row.currency.exponent
    fun money(minor: Long) = displayAmount(minor, e) + " " + code
    ShopCard {
        ShopText(code, TextRole.Title)
        ShopTextField(row.text, { ws.closeDay.setCounted(code, it) }, stringResource(Res.string.closeday_counted, code), kind = FieldKind.Amount, enabled = enabled)
        if (row.invalid) ShopText(stringResource(Res.string.openday_invalid, code), TextRole.Caption)
        if (row.expected != null) Line(stringResource(Res.string.closeday_expected), money(row.expected))
        row.variance?.let { v ->
            val words = when {
                v == 0L -> stringResource(Res.string.closeday_matches)
                v < 0 -> stringResource(Res.string.closeday_short, money(-v))
                else -> stringResource(Res.string.closeday_over, money(v))
            }
            Line(stringResource(Res.string.closeday_variance), if (v == 0L) words else signedAmount(v, e) + " " + code)
            if (v != 0L) ShopText(words, TextRole.Caption)
        }
        if (row.counted != null) {
            Line(stringResource(Res.string.closeday_keep), money(row.float))
            Line(stringResource(Res.string.closeday_handover), money(row.handover ?: 0L))
            if (row.belowFloor) ShopBanner(stringResource(Res.string.closeday_below_float, money(row.float)), Tone.Error)
        }
    }
}

@Composable
private fun Line(label: String, shown: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopText(label, modifier = Modifier.weight(1f))
        ShopText(shown, TextRole.Amount, modifier = Modifier.weight(1f))
    }
}

/** What the server worked out when the day closed: the figures are the server's. */
@Composable
private fun ClosedResult(ws: Workspace, date: String, close: SessionClose) {
    ShopBanner(stringResource(Res.string.closeday_done, date, close.closedBy.name), Tone.Success)
    close.counted.keys.forEach { code ->
        fun money(map: Map<String, String>) = groupThousands(map[code] ?: "0") + " " + code
        val variance = close.variance[code] ?: "0"
        ShopCard {
            ShopText(code, TextRole.Title)
            Line(stringResource(Res.string.closeday_counted_short), money(close.counted))
            Line(stringResource(Res.string.closeday_expected), money(close.expected))
            val sign = if (variance.startsWith("-") || variance.trim('0', '.').isEmpty()) "" else "+"
            Line(stringResource(Res.string.closeday_variance), sign + groupThousands(variance) + " " + code)
            Line(stringResource(Res.string.closeday_handover), money(close.handover))
            if (code in close.belowFloor) ShopBanner(stringResource(Res.string.closeday_below_float_done), Tone.Error)
        }
    }
    if (close.note.isNotBlank()) ShopText(stringResource(Res.string.closeday_note_is, close.note), muted = true)
    ShopButton(stringResource(Res.string.go_today), { ws.closeDay.reset(); ws.go(Destination.TODAY) })
}
