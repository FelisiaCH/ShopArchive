package xyz.felismp.shoparchive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.fluent.FluentButton
import xyz.felismp.shoparchive.app.fluent.FluentCard
import xyz.felismp.shoparchive.app.fluent.FluentNavigationItem
import xyz.felismp.shoparchive.app.fluent.FluentSelectorBar
import xyz.felismp.shoparchive.app.fluent.FluentText
import xyz.felismp.shoparchive.app.fluent.FluentToggleSwitch
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.nav_home
import xyz.felismp.shoparchive.app.resources.nav_reports
import xyz.felismp.shoparchive.app.resources.nav_settings
import xyz.felismp.shoparchive.app.resources.theme_dark
import xyz.felismp.shoparchive.app.resources.theme_light
import xyz.felismp.shoparchive.app.resources.theme_oled
import xyz.felismp.shoparchive.app.resources.theme_system
import xyz.felismp.shoparchive.app.theme.FluentTheme
import xyz.felismp.shoparchive.app.theme.ThemeMode

// Hard-coded sample content (not UI strings): mixed Lao / Thai / Latin / kip / baht / digits.
private const val Specimen = "ລາຍງານ · รายงาน · Oppo a31 · 6,715,000 ₭ · ฿2,000"

@Composable
actual fun ThemePreview() {
    var mode by remember { mutableStateOf(ThemeMode.System) }
    var page by remember { mutableStateOf(0) }
    FluentTheme(mode) {
        val c = FluentTheme.colors
        Row(Modifier.fillMaxSize().background(c.backdrop)) {
            Column(Modifier.width(240.dp).fillMaxHeight().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val items = listOf(
                    stringResource(Res.string.nav_home) to PreviewHomeIcon,
                    stringResource(Res.string.nav_reports) to PreviewChartIcon,
                    stringResource(Res.string.nav_settings) to PreviewSettingsIcon,
                )
                items.forEachIndexed { index, (label, icon) ->
                    FluentNavigationItem(label, icon, selected = page == index, onClick = { page = index })
                }
            }
            val layerShape = RoundedCornerShape(topStart = 8.dp)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(layerShape).background(c.layer)
                    .border(1.dp, c.cardStroke, layerShape),
                contentAlignment = Alignment.TopCenter,
            ) {
                PreviewContent(mode, onMode = { mode = it })
            }
        }
    }
}

@Composable
private fun PreviewContent(mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
    val t = FluentTheme.typography
    val c = FluentTheme.colors
    var showIn by remember { mutableStateOf(true) }
    var type by remember { mutableStateOf("in") }
    Column(
        Modifier.verticalScroll(rememberScrollState()).widthIn(max = 760.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        FluentText("Theme preview", style = t.title)
        FluentSelectorBar(
            options = listOf(
                ThemeMode.Light to stringResource(Res.string.theme_light),
                ThemeMode.Dark to stringResource(Res.string.theme_dark),
                ThemeMode.Oled to stringResource(Res.string.theme_oled),
                ThemeMode.System to stringResource(Res.string.theme_system),
            ),
            selected = mode,
            onSelect = onMode,
        )
        Section("Specimen") {
            FluentText(Specimen, style = t.body)
            FluentText(Specimen, style = t.bodyStrong)
            FluentText(Specimen, style = t.subtitle)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                FluentText("+6,715,000 ₭", style = t.title, color = c.shop.moneyIn)
                FluentText("−240,000 ₭", style = t.title, color = c.shop.moneyOut)
            }
        }
        Section("Card, buttons, switch, selector bar") {
            FluentCard {
                FluentText("ຍອດມື້ນີ້ · ยอดวันนี้ · Today", style = t.bodyStrong)
                FluentText("Oppo a31 · TXN-0001 · 10:42", style = t.caption, color = c.shop.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FluentText("+6,715,000 ₭", style = t.bodyStrong, color = c.shop.moneyIn)
                    FluentText("−฿2,000", style = t.bodyStrong, color = c.shop.moneyOut)
                }
            }
            // Specimens: they only show the resting / hover / pressed look and do nothing.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FluentButton("ບັນທຶກ · บันทึก · Save", onClick = {}, accent = true)
                FluentButton("ຍົກເລີກ · ยกเลิก · Cancel", onClick = {})
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FluentToggleSwitch(showIn, onCheckedChange = { showIn = it })
                FluentText(if (showIn) "On" else "Off", style = t.body)
            }
            FluentSelectorBar(
                options = listOf("in" to "ລາຍຮັບ · รายรับ · In", "out" to "ລາຍຈ່າຍ · รายจ่าย · Out"),
                selected = type,
                onSelect = { type = it },
            )
        }
        Section("Type ramp") {
            for ((label, style) in listOf(
                "caption 12/16 · 400" to t.caption,
                "body 14/20 · 400" to t.body,
                "bodyStrong 14/20 · 600" to t.bodyStrong,
                "subtitle 20/28 · 600" to t.subtitle,
                "title 28/36 · 600" to t.title,
            )) {
                RampRow(label, style)
            }
        }
    }
}

@Composable
private fun RampRow(label: String, style: TextStyle) {
    Column {
        FluentText(label, style = FluentTheme.typography.caption, color = FluentTheme.colors.shop.textMuted)
        FluentText(Specimen, style = style)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FluentText(title, style = FluentTheme.typography.subtitle)
        content()
    }
}
