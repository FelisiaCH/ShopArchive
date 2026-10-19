package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import xyz.felismp.shoparchive.app.theme.ShopTheme

/*
 * Layout pieces the screens share, built only from the kit, so both platforms get them in their own skin.
 */

/** Money in is green, money out is red, anything else keeps the text color. */
enum class MoneyFlow { In, Out, Neutral }

@Composable
internal fun MoneyFlow.color(): Color = when (this) {
    MoneyFlow.In -> ShopTheme.colors.moneyIn
    MoneyFlow.Out -> ShopTheme.colors.moneyOut
    MoneyFlow.Neutral -> Color.Unspecified
}

/** A sum, bold and lined up on the right, green or red when it is money in or out. */
@Composable
internal fun AmountText(text: String, flow: MoneyFlow = MoneyFlow.Neutral, modifier: Modifier = Modifier) =
    ShopText(text, TextRole.Amount, modifier = modifier, color = flow.color())

/** A hairline between rows. */
@Composable
internal fun ShopDivider(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth().height(1.dp).background(ShopTheme.colors.border))
}

/** A label on the left and its [amount] on the right; [strong] sets the amount as a figure that stands out, [muted] makes the whole line recede. */
@Composable
internal fun AmountRow(label: String, amount: String, flow: MoneyFlow = MoneyFlow.Neutral, strong: Boolean = false, muted: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ShopText(label, muted = muted, modifier = Modifier.weight(1f))
        // A share of the row, not all of it: a long figure at a large font size wraps instead of pushing the label out.
        androidx.compose.foundation.layout.Box(Modifier.weight(1.5f), contentAlignment = Alignment.CenterEnd) {
            if (strong) ShopText(amount, TextRole.Display, color = flow.color())
            else ShopText(amount, TextRole.Amount, muted = muted, color = flow.color())
        }
    }
}

/** A count with its label above it, in a card; the whole card is one target when [onClick] is given. */
@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null, onClick: (() -> Unit)? = null) {
    ShopCard(modifier.fillMaxHeight(), onClick = onClick) {
        ShopText(label, TextRole.Caption, muted = true)
        ShopText(value, TextRole.Display)
        if (caption != null) ShopText(caption, TextRole.Caption, muted = true)
    }
}

/** A card of rows, one hairline between each pair; a row is a target when [onClick] is given. */
@Composable
internal fun <T> ShopList(items: List<T>, onClick: ((T) -> Unit)? = null, row: @Composable (T) -> Unit) {
    ShopCard(padded = false) {
        items.forEachIndexed { i, item ->
            if (i > 0) ShopDivider()
            Column(
                Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick(item) } else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
            ) { row(item) }
        }
    }
}

/** [items] side by side in two columns when the page is wide enough (Windows), else one under another. */
@Composable
internal fun <T> CardGrid(items: List<T>, card: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 720.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { Column(Modifier.weight(1f).fillMaxHeight()) { card(it) } }
                        if (pair.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { items.forEach { card(it) } }
        }
    }
}
