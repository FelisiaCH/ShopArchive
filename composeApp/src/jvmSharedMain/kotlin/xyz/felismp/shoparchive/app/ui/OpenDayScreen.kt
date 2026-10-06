package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.OpenDayBlock
import xyz.felismp.shoparchive.app.flow.OpenDayPhase
import xyz.felismp.shoparchive.app.flow.ReportsTab
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.displayAmount
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.app.flow.parseTypedAmount

@Composable
fun OpenDayScreen(ws: Workspace) {
    val ui by ws.openDay.ui.collectAsState()
    val phase = ui.phase
    val can by ws.capabilities.collectAsState()
    // Opened: the day is open now, so Today shows it.
    LaunchedEffect(phase) {
        if (phase is OpenDayPhase.Opened) {
            ws.openDay.reset()
            ws.go(Destination.TODAY)
        }
    }
    val saving = phase is OpenDayPhase.Saving
    ShopPage(stringResource(Res.string.openday_btn)) {
        ShopText(stringResource(Res.string.openday_hint), muted = true)
        ui.currencies.forEach { currency ->
            val text = ui.amounts[currency.code].orEmpty()
            ShopTextField(
                text, { ws.openDay.setAmount(currency.code, it) },
                stringResource(Res.string.openday_amount, currency.code), kind = FieldKind.Amount, enabled = !saving,
            )
            val minor = parseTypedAmount(text, currency.exponent, allowZero = true)
            when {
                currency.code in ui.invalid || (text.isNotEmpty() && minor == null) ->
                    ShopText(stringResource(Res.string.openday_invalid, currency.code), TextRole.Caption)
                minor != null && text.isNotEmpty() ->
                    ShopText(stringResource(Res.string.record_amount_shown, displayAmount(minor, currency.exponent) + " " + currency.code), TextRole.Caption, muted = true)
            }
        }
        when (phase) {
            OpenDayPhase.Saving -> ShopBanner(stringResource(Res.string.working), Tone.Info)
            is OpenDayPhase.AlreadyOpen -> {
                ShopBanner(stringResource(Res.string.openday_already, phase.by.name), Tone.Info)
                ShopButton(stringResource(Res.string.go_today), { ws.openDay.reset(); ws.go(Destination.TODAY) })
            }
            is OpenDayPhase.PreviousOpen -> {
                ShopBanner(stringResource(Res.string.openday_previous, phase.session.businessDate, phase.session.openedBy.name), Tone.Error)
                if (can.closeDay) ShopButton(stringResource(Res.string.closeday_btn), { ws.openDay.reset(); ws.openReports(ReportsTab.CLOSE_DAY) })
            }
            is OpenDayPhase.Failed -> ShopBanner(phase.failure.text(), Tone.Error)
            else -> Unit
        }
        if (ui.blocked == OpenDayBlock.OFFLINE) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopButton(stringResource(Res.string.openday_btn), ws.openDay::confirm, enabled = ui.blocked == null && !saving)
            ShopButton(stringResource(Res.string.cancel), { ws.openDay.reset(); ws.go(Destination.TODAY) }, primary = false)
        }
    }
}
