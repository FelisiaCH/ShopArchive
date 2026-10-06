package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.client.pairLinkIn
import xyz.felismp.shoparchive.app.flow.ConsoleLine
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.resources.*
import kotlin.math.ceil
import kotlin.math.floor

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
        is ConsoleLine.Out -> {
            val link = pairLinkIn(line.text)
            if (link == null) {
                ShopText(line.text, TextRole.Mono, muted = true)
            } else {
                // The link itself is long and meant for the QR and the copy button, not for reading.
                val rest = line.text.replace(link, "").trim().trimEnd(':').trim()
                if (rest.isNotEmpty()) ShopText(rest, TextRole.Mono, muted = true)
                PairingQr(link)
            }
        }
    }
}

/** The pairing [link] as a QR code, with the button that copies it (to paste into a message). */
@Composable
private fun PairingQr(link: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopText(stringResource(Res.string.console_pair_scan), TextRole.Caption, muted = true)
        QrCode(link, Modifier.size(220.dp))
        ShopButton(stringResource(Res.string.console_copy_link), { clipboard.setText(AnnotatedString(link)) }, primary = false)
    }
}

/** Light margin around the code, in modules. */
private const val QUIET_MODULES = 4

/** [text] as the modules of a QR code, the dark ones set. Level L: it is read off a screen, so it needs no repair, and a smaller code scans easier. */
private fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 0),
)

/** [qrMatrix], or null when [text] is too long for a QR code: a line a server prints may be any length, and a failed draw must not close the app. */
internal fun qrMatrixOrNull(text: String): BitMatrix? = runCatching { qrMatrix(text) }.getOrNull()

/** Black modules on white, whatever the theme: a code needs a light margin to be read. */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { qrMatrixOrNull(text) } ?: return
    Canvas(modifier.background(Color.White)) {
        val cell = size.width / (matrix.width + 2 * QUIET_MODULES)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (!matrix[x, y]) continue
            // Whole pixels, so no seam shows between two neighbouring modules.
            val left = floor((x + QUIET_MODULES) * cell)
            val top = floor((y + QUIET_MODULES) * cell)
            drawRect(Color.Black, Offset(left, top), Size(ceil((x + QUIET_MODULES + 1) * cell) - left, ceil((y + QUIET_MODULES + 1) * cell) - top))
        }
    }
}
