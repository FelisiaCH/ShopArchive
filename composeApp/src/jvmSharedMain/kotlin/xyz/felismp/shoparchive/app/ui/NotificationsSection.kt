package xyz.felismp.shoparchive.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.flow.canResend
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.NotificationState

/** Reports › Messages. States are worded as GRILL Q27 says: a late answer is "trying again", only a give-up is "not sent". */
@Composable
fun NotificationsSection(ws: Workspace) {
    val ui by ws.notifications.ui.collectAsState()
    // Opening the tab asks again: the outbox changes on the server without telling the app.
    LaunchedEffect(Unit) { ws.notifications.reload() }
    ShopText(stringResource(Res.string.notif_hint), muted = true)
    ui.failure?.let { ShopBanner(stringResource(Res.string.notif_load_failed) + " " + it.text(), Tone.Error) }
    ui.actionFailure?.let { ShopBanner(stringResource(Res.string.notif_resend_failed) + " " + it.text(), Tone.Error) }

    if (ui.canSend && ui.closedDays.isNotEmpty()) {
        ShopText(stringResource(Res.string.notif_closed_days), TextRole.Title)
        ui.closedDays.forEach { day ->
            ShopButton(
                stringResource(Res.string.notif_send_day, day.businessDate), { ws.notifications.sendDay(day) }, primary = false,
                enabled = !ui.blocked && day.id !in ui.working && !ui.dayWaiting(day),
            )
        }
    }

    val items = ui.items
    when {
        items == null -> if (ui.failure == null) ShopText(stringResource(Res.string.working), muted = true)
        items.isEmpty() -> ShopText(stringResource(Res.string.notif_empty), muted = true)
        else -> items.forEach { Message(ws, it, ui.canSend && ui.canResend(it), !ui.blocked && it.id !in ui.working) }
    }
    if (ui.blocked) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
    ShopButton(stringResource(Res.string.notif_refresh), ws.notifications::reload, primary = false, enabled = !ui.loading)
}

@Composable
private fun Message(ws: Workspace, n: NotificationDto, canSend: Boolean, enabled: Boolean) {
    val branch = ws.branches.firstOrNull { it.key == n.branch }?.displayName ?: n.branch
    ShopCard {
        ShopText(
            when (n.event) {
                "day.closed" -> stringResource(Res.string.notif_event_day)
                "entry.created" -> stringResource(Res.string.notif_event_entry)
                "device.new" -> stringResource(Res.string.notif_event_device)
                else -> n.event
            } + if (branch.isEmpty()) "" else " · $branch", // device.new of a user with no branch has none
            TextRole.Title,
        )
        ShopText(n.code + " · " + n.user + " · " + n.createdAt.take(16).replace('T', ' '), TextRole.Caption, muted = true)
        ShopChip(
            stringResource(
                when (n.state) {
                    NotificationState.QUEUED -> Res.string.notif_state_queued
                    NotificationState.SENT -> Res.string.notif_state_sent
                    NotificationState.UNKNOWN -> Res.string.notif_state_unknown
                    NotificationState.FAILED -> Res.string.notif_state_failed
                },
            ),
            when (n.state) {
                NotificationState.SENT -> Tone.Success
                NotificationState.FAILED -> Tone.Error
                else -> Tone.Info
            },
        )
        if (n.attempts > 1) ShopText(stringResource(Res.string.notif_attempts, n.attempts), TextRole.Caption, muted = true)
        if (canSend) ShopButton(stringResource(Res.string.notif_resend), { ws.notifications.resend(n.id) }, primary = false, enabled = enabled)
    }
}
