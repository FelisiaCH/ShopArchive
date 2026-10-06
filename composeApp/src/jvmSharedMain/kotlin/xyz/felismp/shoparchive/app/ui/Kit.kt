package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.felismp.shoparchive.app.flow.ThisDevice
import xyz.felismp.shoparchive.app.client.PlatformContext

/*
 * The small UI kit the screens are written against: Material 3 on Android, the Fluent controls on Windows.
 * Only what these screens use.
 */

/** [Amount] is for money: right-aligned (give it the width) and in figures that line up in a column. */
enum class TextRole { Title, Body, Caption, Mono, Amount }

enum class FieldKind { Text, Multiline, Password, Pin, Amount }

enum class Tone { Info, Success, Error }

/** The platform skin, following the system light / dark setting. */
@Composable
expect fun ShopSkin(content: @Composable () -> Unit)

/** Full-window background with a centered, scrollable column of at most about 480 dp, headed by [title]. */
@Composable
expect fun ScreenFrame(title: String, content: @Composable ColumnScope.() -> Unit)

@Composable
expect fun ShopText(text: String, role: TextRole = TextRole.Body, muted: Boolean = false, modifier: Modifier = Modifier)

@Composable
expect fun ShopButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true)

@Composable
expect fun ShopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    kind: FieldKind = FieldKind.Text,
    enabled: Boolean = true,
)

/** Inline message: the InfoBar on Windows, a tinted banner on Android. */
@Composable
expect fun ShopBanner(text: String, tone: Tone, modifier: Modifier = Modifier)

/** Small status pill. */
@Composable
expect fun ShopChip(text: String, tone: Tone, modifier: Modifier = Modifier)

/** Segmented control (Android) / selector bar (Windows). */
@Composable
expect fun <T> ShopSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier)

/** A scrollable column for one screen inside the shell (the shell has the window background and insets): [title] on top, content centered up to a readable width. */
@Composable
expect fun ShopPage(title: String, content: @Composable ColumnScope.() -> Unit)

/** A bordered block that groups related content. */
@Composable
expect fun ShopCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)

/** A chip that can be on or off, for picking one of a few (category, currency, suggestion). */
@Composable
expect fun ShopChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)

/** The app's navigation: a bottom bar when not [wide], a pane on the left when [wide]. [content] fills the rest. */
@Composable
expect fun <T> ShopNavigation(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, wide: Boolean, content: @Composable () -> Unit)

/** Wraps the app: on Android 17+ asks for local network access first, elsewhere shows [content] straight away. */
@Composable
expect fun LocalNetworkGate(content: @Composable () -> Unit)

/** Runs [action] each time the app comes back to the front (Android); never on desktop. */
@Composable
expect fun OnAppResume(action: () -> Unit)

/** Shows [content] in the language [tag] (`en`, `lo`, `th`), or the system's when null; a change rebuilds [content]. */
@Composable
expect fun AppLanguage(tag: String?, content: @Composable () -> Unit)

/** The label and platform name this device enrolls with by default. */
expect fun thisDevice(context: PlatformContext): ThisDevice
