package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.ConsoleLine
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.resources.*

/** The console: a line to type, what the server said below it. Up and down bring back earlier lines, Tab completes (a hardware keyboard; the buttons do the rest). */
@Composable
fun ConsoleScreen(ws: Workspace) {
    val c = ws.console
    val ui by c.ui.collectAsState()
    val list = rememberLazyListState()
    // The newest line stays in view.
    LaunchedEffect(ui.lines.size) { if (ui.lines.isNotEmpty()) list.scrollToItem(ui.lines.lastIndex) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopText(stringResource(Res.string.nav_console), TextRole.Title)
            if (ui.lines.isEmpty()) ShopText(stringResource(Res.string.console_hint), muted = true)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(ui.lines) { line -> ConsoleRow(line) }
            }
            if (ui.blocked) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                ShopTextField(
                    ui.input, c::setInput, stringResource(Res.string.console_field),
                    Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> c.previous()
                            Key.DirectionDown -> c.next()
                            Key.Tab -> c.complete()
                            Key.Enter, Key.NumPadEnter -> c.send()
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                )
                ShopButton(stringResource(Res.string.console_run), c::send, enabled = ui.canSend)
            }
        }
    }
}

@Composable
private fun ConsoleRow(line: ConsoleLine) {
    when (line) {
        is ConsoleLine.Typed -> ShopText("> " + line.text, TextRole.Mono)
        is ConsoleLine.Failed -> ShopBanner(line.failure.text(), Tone.Error)
        is ConsoleLine.Out -> ShopText(line.text, TextRole.Mono, muted = true)
    }
}
