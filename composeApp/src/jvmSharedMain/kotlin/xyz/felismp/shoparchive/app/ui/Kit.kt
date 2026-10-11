package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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

/** A button; [icon], when given, goes before the text. */
@Composable
expect fun ShopButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, icon: ShopIcon? = null)

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

/** A scrollable column for one screen inside the shell (the shell has the window background and insets): [title] on top, content up to a readable width (centered on Android, at the left on Windows). */
@Composable
expect fun ShopPage(title: String, content: @Composable ColumnScope.() -> Unit)

/** A bordered block that groups related content. */
@Composable
expect fun ShopCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)

/** A chip that can be on or off, for picking one of a few (category, currency, suggestion). */
@Composable
expect fun ShopChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)

/** The width from which the navigation is a full pane on the left (icons and labels). */
expect val navigationPaneFrom: Dp

/** True when the navigation is a bottom bar under [navigationPaneFrom] (Reports then sits under More); false when it stays a pane on the left, narrowed to icons. */
expect val navigationBottomBar: Boolean

/** The space at each side of the banners and the branch choice above the screens, so they line up with the page. */
expect val shellInset: Dp

/** Full width on Android, where a button fills its column; on Windows a button is as wide as its label. */
expect fun Modifier.fillWidthOnPhone(): Modifier

/** An entry of the navigation that is not one of its destinations (Console, Settings, Lock now). */
class NavAction(val label: String, val icon: ShopIcon, val selected: Boolean = false, val onClick: () -> Unit)

/** Who is signed in, with the action that locks the app. */
class NavAccount(val name: String, val server: String, val lock: NavAction)

/**
 * The app's navigation. [wide]: a pane on the left, each item with [icon] and label. Not [wide]: a bottom bar on Android, a narrow strip of icons
 * on Windows (the label shows when the pointer rests on one). Only Windows shows [footer] and [account], at the foot of the pane apart from the
 * items; Android ignores them. [selected] is null when none of the items is the current page (Settings on Windows). [content] fills the rest.
 */
@Composable
expect fun <T> ShopNavigation(
    items: List<Pair<T, String>>,
    icon: (T) -> ShopIcon,
    selected: T?,
    onSelect: (T) -> Unit,
    wide: Boolean,
    footer: List<NavAction> = emptyList(),
    account: NavAccount? = null,
    content: @Composable () -> Unit,
)

/**
 * A screen that fills the window instead of scrolling, headed by [title]: the log of the console is one. Android centers a column of at most 720 dp;
 * Windows puts it at the top left under the page margin, as [ShopPage] does.
 */
@Composable
expect fun ShopFixedPage(title: String, content: @Composable ColumnScope.() -> Unit)

/** Wraps the app: on Android 17+ asks for local network access first, elsewhere shows [content] straight away. */
@Composable
expect fun LocalNetworkGate(content: @Composable () -> Unit)

/** Runs [action] each time the app comes back to the front (Android); never on desktop. */
@Composable
expect fun OnAppResume(action: () -> Unit)

/** Shows [content] in the language [tag] (`en`, `lo`, `th`), or the system's when null; a change rebuilds [content]. */
@Composable
expect fun AppLanguage(tag: String?, content: @Composable () -> Unit)

/** The label and platform name this device logs in with. */
expect fun thisDevice(context: PlatformContext): ThisDevice
