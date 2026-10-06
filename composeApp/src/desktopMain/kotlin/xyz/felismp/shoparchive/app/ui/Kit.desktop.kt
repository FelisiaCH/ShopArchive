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
actual fun ShopButton(text: String, onClick: () -> Unit, modifier: Modifier, primary: Boolean, enabled: Boolean) {
    // A disabled Fluent button simply ignores the click and looks dimmed.
    FluentButton(text, { if (enabled) onClick() }, if (enabled) modifier else modifier.alpha(0.5f), accent = primary)
}

@Composable
actual fun ShopTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier, kind: FieldKind, enabled: Boolean) {
    FluentTextBox(
        value, onValueChange, label, modifier,
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FluentText(title, style = FluentTheme.typography.subtitle)
            content()
        }
    }
}

@Composable
actual fun ShopCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) = FluentCard(modifier, content)

@Composable
actual fun ShopChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) =
    FluentButton(text, onClick, modifier, accent = selected)

@Composable
actual fun <T> ShopNavigation(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, wide: Boolean, content: @Composable () -> Unit) {
    val layer = FluentTheme.colors.layer
    if (wide) {
        Row(Modifier.fillMaxSize().background(layer)) {
            Column(Modifier.width(220.dp).fillMaxHeight().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items.forEach { (value, label) -> FluentNavigationItem(label, null, value == selected) { onSelect(value) } }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize().background(layer)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            FluentSelectorBar(items, selected, onSelect, Modifier.fillMaxWidth().padding(8.dp))
        }
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
