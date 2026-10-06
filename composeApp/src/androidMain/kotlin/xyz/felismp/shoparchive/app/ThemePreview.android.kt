package xyz.felismp.shoparchive.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.nav_home
import xyz.felismp.shoparchive.app.resources.nav_reports
import xyz.felismp.shoparchive.app.resources.nav_settings
import xyz.felismp.shoparchive.app.resources.theme_dark
import xyz.felismp.shoparchive.app.resources.theme_light
import xyz.felismp.shoparchive.app.resources.theme_oled
import xyz.felismp.shoparchive.app.resources.theme_system
import xyz.felismp.shoparchive.app.theme.MaterialShopTheme
import xyz.felismp.shoparchive.app.theme.ShopTheme
import xyz.felismp.shoparchive.app.theme.ThemeMode

// Hard-coded sample content (not UI strings): mixed Lao / Thai / Latin / kip / baht / digits.
private const val Specimen = "ລາຍງານ · รายงาน · Oppo a31 · 6,715,000 ₭ · ฿2,000"

@Composable
actual fun ThemePreview() {
    var mode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    var page by rememberSaveable { mutableStateOf(0) }
    MaterialShopTheme(mode) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val items = listOf(
                        stringResource(Res.string.nav_home) to PreviewHomeIcon,
                        stringResource(Res.string.nav_reports) to PreviewChartIcon,
                        stringResource(Res.string.nav_settings) to PreviewSettingsIcon,
                    )
                    items.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = page == index,
                            onClick = { page = index },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            PreviewContent(mode, onMode = { mode = it }, Modifier.padding(padding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PreviewContent(mode: ThemeMode, onMode: (ThemeMode) -> Unit, modifier: Modifier) {
    val space = ShopTheme.spacing
    val money = ShopTheme.colors
    val type = MaterialTheme.typography
    var showIn by rememberSaveable { mutableStateOf(true) }
    var kind by rememberSaveable { mutableStateOf("in") }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(space.x16),
        verticalArrangement = Arrangement.spacedBy(space.x20),
    ) {
        Text("Theme preview", style = type.headlineSmall)
        Segmented(
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
            Text(Specimen, style = type.bodyLarge)
            Text(Specimen, style = type.bodyLarge.copy(fontWeight = FontWeight.Bold))
            Row(horizontalArrangement = Arrangement.spacedBy(space.x20)) {
                Text("+6,715,000 ₭", style = type.headlineSmall, color = money.moneyIn)
                Text("−240,000 ₭", style = type.headlineSmall, color = money.moneyOut)
            }
        }
        Section("Card, buttons, segmented, switch, list") {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(space.x16), verticalArrangement = Arrangement.spacedBy(space.x4)) {
                    Text("ຍອດມື້ນີ້ · ยอดวันนี้ · Today", style = type.titleMedium)
                    Text(
                        "Oppo a31 · TXN-0001 · 10:42",
                        style = type.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(space.x16)) {
                        Amount("+6,715,000 ₭", money.moneyIn, type.titleMedium)
                        Amount("−฿2,000", money.moneyOut, type.titleMedium)
                    }
                }
            }
            // Specimens: they only show the resting / pressed look and do nothing.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(space.x8)) {
                Button(onClick = {}) { Text("ບັນທຶກ · บันทึก · Save") }
                FilledTonalButton(onClick = {}) { Text("ຍົກເລີກ · ยกเลิก · Cancel") }
            }
            Segmented(
                options = listOf("in" to "ລາຍຮັບ · รายรับ · In", "out" to "ລາຍຈ່າຍ · รายจ่าย · Out"),
                selected = kind,
                onSelect = { kind = it },
            )
            Column {
                ListItem(
                    headlineContent = { Text("Oppo a31") },
                    supportingContent = { Text("TXN-0001 · 10:42") },
                    trailingContent = { Amount("+6,715,000 ₭", money.moneyIn, type.titleMedium) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("ເຄສ · เคส · Case") },
                    supportingContent = { Text("TXN-0002 · 11:05") },
                    trailingContent = { Amount("−฿2,000", money.moneyOut, type.titleMedium) },
                )
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.toggleable(showIn, role = Role.Switch) { showIn = it },
                    headlineContent = { Text("Switch") },
                    trailingContent = { Switch(checked = showIn, onCheckedChange = null) },
                )
            }
        }
        Section("Type") {
            for ((label, style) in listOf(
                "headlineSmall" to type.headlineSmall,
                "titleMedium" to type.titleMedium,
                "bodyLarge" to type.bodyLarge,
                "bodyMedium" to type.bodyMedium,
                "labelLarge" to type.labelLarge,
            )) {
                Column {
                    Text(label, style = type.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Specimen, style = style)
                }
            }
        }
    }
}

@Composable
private fun Amount(text: String, color: Color, style: TextStyle) {
    // moneyIn holds 4.5:1 on the main surfaces; bold is for emphasis only.
    Text(text, style = style.copy(fontWeight = FontWeight.Bold), color = color)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x12)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}
