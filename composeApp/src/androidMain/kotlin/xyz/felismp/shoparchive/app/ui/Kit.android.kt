package xyz.felismp.shoparchive.app.ui

import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import xyz.felismp.shoparchive.app.client.PlatformContext
import xyz.felismp.shoparchive.app.flow.ThisDevice
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.cancel
import xyz.felismp.shoparchive.app.resources.reauth_confirm
import xyz.felismp.shoparchive.app.resources.perm_allow
import xyz.felismp.shoparchive.app.resources.perm_body
import xyz.felismp.shoparchive.app.resources.perm_denied_body
import xyz.felismp.shoparchive.app.resources.perm_denied_title
import xyz.felismp.shoparchive.app.resources.perm_retry
import xyz.felismp.shoparchive.app.resources.perm_settings
import xyz.felismp.shoparchive.app.resources.perm_title
import xyz.felismp.shoparchive.app.theme.MaterialShopTheme
import xyz.felismp.shoparchive.app.theme.ShopTheme
import xyz.felismp.shoparchive.app.theme.ThemeMode

private val MinTarget = 48.dp

/** The title at the top of a page: the top app bar of the design, without a fixed height so Lao is never clipped. */
@Composable
private fun PageTitle(title: String) {
    Text(
        title, Modifier.padding(vertical = ShopTheme.spacing.x8),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium, lineHeight = 36.sp),
    )
}

@Composable
actual fun ShopSkin(content: @Composable () -> Unit) = MaterialShopTheme(ThemeMode.System, content)

@Composable
actual fun ScreenFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState()).padding(ShopTheme.spacing.x16),
                verticalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x16),
            ) {
                PageTitle(title)
                content()
            }
        }
    }
}

@Composable
actual fun ShopText(text: String, role: TextRole, muted: Boolean, modifier: Modifier, color: Color) {
    val type = MaterialTheme.typography
    val style = when (role) {
        TextRole.Title -> type.titleMedium.copy(fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        TextRole.Body -> type.bodyLarge
        TextRole.Caption -> type.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp)
        TextRole.Mono -> type.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        TextRole.Amount -> type.bodyLarge.copy(textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
        TextRole.Display -> type.headlineSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 36.sp, fontFeatureSettings = "tnum")
    }
    val c = ShopTheme.colors
    val default = if (!muted) c.text else if (role == TextRole.Caption) c.textMuted else c.textSecondary
    Text(text, modifier, style = style, color = color.takeOrElse { default })
}

@Composable
actual fun ShopButton(text: String, onClick: () -> Unit, modifier: Modifier, primary: Boolean, enabled: Boolean, icon: ShopIcon?) {
    val m = modifier.heightIn(min = MinTarget)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon.material(), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(text, textAlign = TextAlign.Center)
    }
    if (primary) Button(onClick, m, enabled, content = content)
    else OutlinedButton(onClick, m, enabled, border = BorderStroke(1.dp, if (enabled) ShopTheme.colors.iconSubtle else ShopTheme.colors.border), content = content)
}

@Composable
actual fun ShopTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier, kind: FieldKind, enabled: Boolean) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth().heightIn(min = MinTarget), enabled = enabled,
        label = { Text(label) },
        textStyle = if (kind == FieldKind.Amount) MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum") else LocalTextStyle.current,
        colors = fieldColors(),
        singleLine = kind != FieldKind.Multiline,
        minLines = if (kind == FieldKind.Multiline) 3 else 1,
        visualTransformation = if (kind == FieldKind.Password || kind == FieldKind.Pin) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (kind) {
                FieldKind.Password -> KeyboardType.Password
                FieldKind.Pin -> KeyboardType.NumberPassword
                FieldKind.Amount -> KeyboardType.Decimal
                else -> KeyboardType.Text
            },
        ),
    )
}

/** The field's edge is the 3:1 outline until it is focused, then the accent at 2 dp, as in the design. */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ShopTheme.colors.accent, unfocusedBorderColor = ShopTheme.colors.iconSubtle,
    focusedLabelColor = ShopTheme.colors.text, unfocusedLabelColor = ShopTheme.colors.textSecondary,
)

/** A read-only field that opens Material's calendar on a tap. The calendar speaks UTC midnights, so the day is converted in UTC both ways and no time zone moves it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun ShopDateField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value, {}, Modifier.fillMaxWidth().heightIn(min = MinTarget), readOnly = true, singleLine = true, label = { Text(label) }, colors = fieldColors(),
            trailingIcon = { Icon(ShopIcon.Today.material(), contentDescription = null, Modifier.size(20.dp)) },
        )
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = runCatching { LocalDate.parse(value.trim()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { onValueChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text(stringResource(Res.string.reauth_confirm)) }
            },
            dismissButton = { TextButton({ open = false }) { Text(stringResource(Res.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** Fill and ink of a message: a green tint for success, red for an error, the neutral ones otherwise. */
@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = ShopTheme.colors
    return when (tone) {
        Tone.Info -> c.surfaceSelected to c.accent
        Tone.Success -> c.moneyIn.copy(alpha = 0.12f).compositeOver(c.surface) to c.moneyIn
        Tone.Error -> c.warnBg to c.moneyOut
    }
}

@Composable
actual fun ShopBanner(text: String, tone: Tone, modifier: Modifier) {
    val (fill, bar) = toneColors(tone)
    Surface(modifier.fillMaxWidth(), color = fill, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, bar.copy(alpha = 0.25f))) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(bar))
            Text(text, Modifier.padding(horizontal = ShopTheme.spacing.x16, vertical = ShopTheme.spacing.x12), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp), color = ShopTheme.colors.text)
        }
    }
}

@Composable
actual fun ShopChip(text: String, tone: Tone, modifier: Modifier) {
    val (fill, accent) = toneColors(tone)
    Surface(modifier, color = if (tone == Tone.Info) ShopTheme.colors.border else fill, shape = CircleShape) {
        Text(
            text, Modifier.padding(horizontal = ShopTheme.spacing.x12, vertical = ShopTheme.spacing.x4),
            style = MaterialTheme.typography.labelLarge.copy(lineHeight = 22.sp), color = if (tone == Tone.Info) ShopTheme.colors.textSecondary else accent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun <T> ShopSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().heightIn(min = MinTarget)) {
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

@Composable
actual fun ShopPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(ShopTheme.spacing.x16),
            verticalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x16),
        ) {
            if (title.isNotEmpty()) PageTitle(title)
            content()
        }
    }
}

@Composable
actual fun ShopCard(modifier: Modifier, padded: Boolean, onClick: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val c = ShopTheme.colors
    val body: @Composable () -> Unit = {
        Column(
            if (padded) Modifier.padding(ShopTheme.spacing.x16) else Modifier,
            verticalArrangement = Arrangement.spacedBy(if (padded) ShopTheme.spacing.x8 else 0.dp), content = content,
        )
    }
    val shape = MaterialTheme.shapes.medium
    if (onClick == null) Surface(modifier.fillMaxWidth(), shape = shape, color = c.surface, border = BorderStroke(1.dp, c.border), content = body)
    else Surface(onClick, modifier.fillMaxWidth(), shape = shape, color = c.surface, border = BorderStroke(1.dp, c.border), content = body)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun ShopChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = ShopTheme.colors
    FilterChip(
        selected, onClick, label = { Text(text, style = MaterialTheme.typography.labelLarge.copy(lineHeight = 22.sp)) }, modifier = modifier,
        leadingIcon = if (selected) ({ SegmentedButtonDefaults.ActiveIcon() }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = c.border, selectedLabelColor = c.text, selectedLeadingIconColor = c.text,
            labelColor = c.text, containerColor = c.surface,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = c.iconSubtle, selectedBorderColor = Color.Transparent),
    )
}

actual val navigationPaneFrom = 900.dp

actual val navigationBottomBar = true

actual val shellInset = 16.dp

actual fun Modifier.fillWidthOnPhone(): Modifier = fillMaxWidth()

@Composable
actual fun ShopFixedPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopText(title, TextRole.Title)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun <T> ShopNavigation(
    items: List<Pair<T, String>>, icon: (T) -> ShopIcon, selected: T?, onSelect: (T) -> Unit, wide: Boolean,
    footer: List<NavAction>, account: NavAccount?, badge: (T) -> Int?, content: @Composable () -> Unit,
) {
    val colors = ShopTheme.colors
    // The wide pane: the icon beside the label, the current one on a pill.
    @Composable
    fun PaneItem(value: T, label: String) {
        val on = value == selected
        val tint = if (on) colors.text else colors.textSecondary
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(on, role = Role.Tab) { onSelect(value) }.padding(ShopTheme.spacing.x4),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = if (on) colors.border else Color.Transparent) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ShopTheme.spacing.x16, vertical = ShopTheme.spacing.x12),
                    horizontalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x12),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(icon(value).material(), contentDescription = null, Modifier.size(24.dp), tint = tint)
                    Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1, color = tint)
                }
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (wide) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.width(200.dp).fillMaxHeight().background(colors.sidebar).padding(ShopTheme.spacing.x8)) {
                    items.forEach { (value, label) -> PaneItem(value, label) }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                // As in Material apps the bar gives way to the keyboard: lifted above it, it would cover the form's own buttons.
                if (!WindowInsets.isImeVisible) NavigationBar(
                    Modifier.drawBehind { drawLine(colors.border, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) },
                    containerColor = colors.sidebar, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    items.forEach { (value, label) ->
                        NavigationBarItem(
                            selected = value == selected, onClick = { onSelect(value) },
                            icon = { Icon(icon(value).material(), contentDescription = null, Modifier.size(24.dp)) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium.copy(lineHeight = 18.sp), maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.text, selectedTextColor = colors.text, indicatorColor = colors.border,
                                unselectedIconColor = colors.textSecondary, unselectedTextColor = colors.textSecondary,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
@Composable
actual fun OnAppResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
actual fun OnBackPress(enabled: Boolean, action: () -> Unit) = BackHandler(enabled, action)

private var systemLocale: Locale? = null

@Suppress("DEPRECATION")
@Composable
actual fun AppLanguage(tag: String?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val system = systemLocale ?: Locale.getDefault().also { systemLocale = it } // read before the first change
    val locale = if (tag == null) system else Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val updated = Configuration(configuration).apply { setLocale(locale) }
    context.resources.updateConfiguration(updated, context.resources.displayMetrics)
    CompositionLocalProvider(LocalConfiguration provides updated) { key(tag) { content() } }
}

private const val FIRST_SDK_WITH_LOCAL_NETWORK = 37

@Composable
actual fun LocalNetworkGate(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT < FIRST_SDK_WITH_LOCAL_NETWORK) {
        content()
        return
    }
    val context = LocalContext.current
    fun isGranted() = context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(isGranted()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    // Coming back from the system settings: look again.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = isGranted() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (granted) {
        content()
    } else if (!denied) {
        ScreenFrame(stringResource(Res.string.perm_title)) {
            ShopText(stringResource(Res.string.perm_body))
            ShopButton(stringResource(Res.string.perm_allow), { launcher.launch(LOCAL_NETWORK) }, Modifier.fillMaxWidth())
        }
    } else {
        ScreenFrame(stringResource(Res.string.perm_denied_title)) {
            ShopText(stringResource(Res.string.perm_denied_body))
            ShopButton(stringResource(Res.string.perm_retry), { launcher.launch(LOCAL_NETWORK) }, Modifier.fillMaxWidth())
            ShopButton(
                stringResource(Res.string.perm_settings),
                {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                },
                Modifier.fillMaxWidth(), primary = false,
            )
        }
    }
}

actual fun thisDevice(context: PlatformContext) = ThisDevice(Build.MODEL, "android")
