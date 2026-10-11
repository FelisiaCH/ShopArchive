package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.felismp.shoparchive.app.client.PlatformContext
import xyz.felismp.shoparchive.app.fluent.FluentButton
import xyz.felismp.shoparchive.app.fluent.FluentCard
import xyz.felismp.shoparchive.app.fluent.FluentNavigationItem
import xyz.felismp.shoparchive.app.fluent.FluentChip
import xyz.felismp.shoparchive.app.fluent.FluentInfoBar
import xyz.felismp.shoparchive.app.fluent.FluentSelectorBar
import xyz.felismp.shoparchive.app.fluent.FluentText
import xyz.felismp.shoparchive.app.fluent.FluentTextBox
import xyz.felismp.shoparchive.app.flow.ThisDevice
import xyz.felismp.shoparchive.app.theme.FluentTheme
import xyz.felismp.shoparchive.app.theme.ThemeMode
import java.util.Locale

private val PAGE_MARGIN = 36.dp
private val PAGE_MAX_WIDTH = 1000.dp
private val FIELD_MAX_WIDTH = 520.dp

@Composable
actual fun ShopSkin(content: @Composable () -> Unit) = FluentTheme(ThemeMode.System, content)

@Composable
actual fun ScreenFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(FluentTheme.colors.layer), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FluentText(title, style = FluentTheme.typography.subtitle)
            content()
        }
    }
}

@Composable
actual fun ShopText(text: String, role: TextRole, muted: Boolean, modifier: Modifier) {
    val t = FluentTheme.typography
    val style = when (role) {
        TextRole.Title -> t.bodyStrong
        TextRole.Body -> t.body
        TextRole.Caption -> t.caption
        TextRole.Mono -> t.body.copy(fontFamily = FontFamily.Monospace)
        // Prompt has no tabular figures, so money is set in the system's monospace (Consolas on Windows) where every digit is as wide as the next.
        TextRole.Amount -> t.body.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
    val c = FluentTheme.colors.shop
    FluentText(text, modifier, style, color = if (muted) c.textSecondary else c.text)
}

@Composable
actual fun ShopButton(text: String, onClick: () -> Unit, modifier: Modifier, primary: Boolean, enabled: Boolean, icon: ShopIcon?) {
    // A disabled Fluent button simply ignores the click and looks dimmed.
    FluentButton(text, { if (enabled) onClick() }, if (enabled) modifier else modifier.alpha(0.5f), accent = primary, icon = icon?.fluent())
}

@Composable
actual fun ShopTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier, kind: FieldKind, enabled: Boolean) {
    FluentTextBox(
        value, onValueChange, label, modifier.widthIn(max = FIELD_MAX_WIDTH),
        singleLine = kind != FieldKind.Multiline,
        password = kind == FieldKind.Password || kind == FieldKind.Pin,
        enabled = enabled,
    )
}

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = FluentTheme.colors
    return when (tone) {
        Tone.Info -> c.card to c.shop.accent
        Tone.Success -> c.card to c.shop.moneyIn
        Tone.Error -> c.shop.warnBg to c.shop.moneyOut
    }
}

@Composable
actual fun ShopBanner(text: String, tone: Tone, modifier: Modifier) {
    val (fill, stripe) = toneColors(tone)
    FluentInfoBar(text, stripe, fill, modifier)
}

@Composable
actual fun ShopChip(text: String, tone: Tone, modifier: Modifier) {
    val (fill, accent) = toneColors(tone)
    FluentChip(text, accent, fill, modifier)
}

@Composable
actual fun <T> ShopSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier) =
    FluentSelectorBar(options, selected, onSelect, modifier)

@Composable
actual fun ShopPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    // As in a WinUI page: the title at the top left under the page margin, the content at most 1000 dp wide; the wheel scrolls anywhere in the window.
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.widthIn(max = PAGE_MAX_WIDTH + PAGE_MARGIN * 2).fillMaxWidth().padding(horizontal = PAGE_MARGIN, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title.isNotEmpty()) FluentText(title, style = FluentTheme.typography.title)
            content()
        }
    }
}

@Composable
actual fun ShopCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) = FluentCard(modifier, content)

@Composable
actual fun ShopChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) =
    FluentButton(text, onClick, modifier, accent = selected)

actual val navigationPaneFrom = 1008.dp // where WinUI's NavigationView goes from compact to expanded

actual val navigationBottomBar = false

actual val shellInset = PAGE_MARGIN

actual fun Modifier.fillWidthOnPhone(): Modifier = this

@Composable
actual fun ShopFixedPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FluentText(title, style = FluentTheme.typography.title)
        content()
    }
}

@Composable
actual fun <T> ShopNavigation(
    items: List<Pair<T, String>>, icon: (T) -> ShopIcon, selected: T?, onSelect: (T) -> Unit, wide: Boolean,
    footer: List<NavAction>, account: NavAccount?, content: @Composable () -> Unit,
) {
    // The pane on the left is the 280 dp one with labels when [wide], else the 48 dp strip of icons; never a bottom bar. It sits on the window
    // background, the page on a layer of its own: Fluent layer color, a 1 dp stroke along the top and left, the top left corner rounded by 8.
    val colors = FluentTheme.colors
    Row(Modifier.fillMaxSize().background(colors.backdrop)) {
        Column(Modifier.width(if (wide) 280.dp else 48.dp).fillMaxHeight().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEach { (value, label) -> FluentNavigationItem(label, icon(value).fluent(), value == selected, compact = !wide) { onSelect(value) } }
            Spacer(Modifier.weight(1f))
            footer.forEach { FluentNavigationItem(it.label, it.icon.fluent(), it.selected, compact = !wide, onClick = it.onClick) }
            if (account != null) {
                Box(Modifier.padding(horizontal = 4.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(colors.cardStroke))
                if (wide) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                        FluentText(account.name, style = FluentTheme.typography.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FluentText(account.server, style = FluentTheme.typography.caption, color = colors.shop.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val lock = account.lock
                FluentNavigationItem(lock.label, lock.icon.fluent(), false, compact = !wide, tooltip = "${account.name} · ${account.server} · ${lock.label}", onClick = lock.onClick)
            }
        }
        val corner = 8.dp
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(topStart = corner)).background(colors.layer).drawBehind {
                val r = corner.toPx()
                val stroke = 1.dp.toPx()
                val o = stroke / 2
                val edge = Path().apply {
                    moveTo(o, size.height)
                    lineTo(o, r)
                    arcTo(Rect(o, o, 2 * r - o, 2 * r - o), 180f, 90f, false)
                    lineTo(size.width, o)
                }
                drawPath(edge, colors.cardStroke, style = Stroke(stroke))
            },
        ) { content() }
    }
}

@Composable
actual fun LocalNetworkGate(content: @Composable () -> Unit) = content()

@Composable
actual fun OnAppResume(action: () -> Unit) = Unit // idle time alone locks the desktop app

private val systemLocale: Locale = Locale.getDefault()

@Composable
actual fun AppLanguage(tag: String?, content: @Composable () -> Unit) {
    Locale.setDefault(if (tag == null) systemLocale else Locale.forLanguageTag(tag))
    key(tag) { content() }
}

actual fun thisDevice(context: PlatformContext): ThisDevice {
    val os = System.getProperty("os.name").orEmpty()
    val name = System.getenv("COMPUTERNAME")
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "Computer"
    return ThisDevice(name, if (os.startsWith("Windows", ignoreCase = true)) "windows" else os.lowercase().ifBlank { "desktop" })
}
