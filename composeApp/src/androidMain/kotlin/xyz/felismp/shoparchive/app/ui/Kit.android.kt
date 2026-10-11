package xyz.felismp.shoparchive.app.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
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
import xyz.felismp.shoparchive.app.client.PlatformContext
import xyz.felismp.shoparchive.app.flow.ThisDevice
import xyz.felismp.shoparchive.app.resources.Res
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

@Composable
actual fun ShopSkin(content: @Composable () -> Unit) = MaterialShopTheme(ThemeMode.System, content)

@Composable
actual fun ScreenFrame(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState()).padding(ShopTheme.spacing.x16),
                verticalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x12),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
            }
        }
    }
}

@Composable
actual fun ShopText(text: String, role: TextRole, muted: Boolean, modifier: Modifier) {
    val type = MaterialTheme.typography
    val style = when (role) {
        TextRole.Title -> type.titleLarge
        TextRole.Body -> type.bodyLarge
        TextRole.Caption -> type.bodySmall
        TextRole.Mono -> type.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        TextRole.Amount -> type.bodyLarge.copy(textAlign = TextAlign.End, fontFeatureSettings = "tnum")
    }
    Text(text, modifier, style = style, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}

@Composable
actual fun ShopButton(text: String, onClick: () -> Unit, modifier: Modifier, primary: Boolean, enabled: Boolean, icon: ShopIcon?) {
    val m = modifier.heightIn(min = MinTarget)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon.material(), contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(text)
    }
    if (primary) Button(onClick, m, enabled, content = content) else FilledTonalButton(onClick, m, enabled, content = content)
}

@Composable
actual fun ShopTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier, kind: FieldKind, enabled: Boolean) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth().heightIn(min = MinTarget), enabled = enabled,
        label = { Text(label) },
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

@Composable
private fun toneColors(tone: Tone): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val c = ShopTheme.colors
    return when (tone) {
        Tone.Info -> c.surfaceSelected to c.accent
        Tone.Success -> c.surfaceSelected to c.moneyIn
        Tone.Error -> c.warnBg to c.moneyOut
    }
}

@Composable
actual fun ShopBanner(text: String, tone: Tone, modifier: Modifier) {
    val (fill, bar) = toneColors(tone)
    Surface(modifier.fillMaxWidth(), color = fill, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(bar))
            Text(text, Modifier.padding(ShopTheme.spacing.x12), style = MaterialTheme.typography.bodyMedium, color = ShopTheme.colors.text)
        }
    }
}

@Composable
actual fun ShopChip(text: String, tone: Tone, modifier: Modifier) {
    val (fill, accent) = toneColors(tone)
    Surface(modifier, color = fill, shape = CircleShape) {
        Text(text, Modifier.padding(horizontal = ShopTheme.spacing.x12, vertical = ShopTheme.spacing.x6), style = MaterialTheme.typography.labelLarge, color = accent)
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
            verticalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x12),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

@Composable
actual fun ShopCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = ShopTheme.colors.surfaceAlt,
        border = androidx.compose.foundation.BorderStroke(1.dp, ShopTheme.colors.border),
    ) {
        Column(Modifier.padding(ShopTheme.spacing.x16), verticalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x8), content = content)
    }
}

@Composable
actual fun ShopChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    androidx.compose.material3.FilterChip(selected, onClick, label = { Text(text, style = MaterialTheme.typography.labelLarge) }, modifier = modifier)
}

@Composable
actual fun <T> ShopNavigation(items: List<Pair<T, String>>, icon: (T) -> ShopIcon, selected: T, onSelect: (T) -> Unit, wide: Boolean, content: @Composable () -> Unit) {
    val colors = ShopTheme.colors
    // As in the Material navigation components: the icon over the label in the bottom bar, beside it in the pane.
    @Composable
    fun Item(value: T, label: String, modifier: Modifier) {
        val on = value == selected
        val tint = if (on) colors.text else colors.textMuted
        val pill = if (on) colors.surfaceSelected else androidx.compose.ui.graphics.Color.Transparent
        Box(
            modifier.heightIn(min = 56.dp).selectable(on, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(value) }.padding(ShopTheme.spacing.x4),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            if (wide) {
                Surface(shape = CircleShape, color = pill) {
                    Row(
                        Modifier.padding(horizontal = ShopTheme.spacing.x16, vertical = ShopTheme.spacing.x12),
                        horizontalArrangement = Arrangement.spacedBy(ShopTheme.spacing.x12),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(icon(value).material(), contentDescription = null, Modifier.size(24.dp), tint = tint)
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = tint)
                    }
                }
            } else {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, color = pill) {
                        Icon(icon(value).material(), contentDescription = null, Modifier.padding(horizontal = 20.dp, vertical = ShopTheme.spacing.x4).size(24.dp), tint = tint)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = tint)
                }
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (wide) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.width(200.dp).fillMaxHeight().background(colors.sidebar).padding(ShopTheme.spacing.x8)) {
                    items.forEach { (value, label) -> Item(value, label, Modifier.fillMaxWidth()) }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                Row(Modifier.fillMaxWidth().background(colors.sidebar)) {
                    items.forEach { (value, label) -> Item(value, label, Modifier.weight(1f)) }
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
