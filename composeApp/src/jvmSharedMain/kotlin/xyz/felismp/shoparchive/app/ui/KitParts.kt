package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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

/** One row of a [ShopTable]: a name, then amounts; the first [colored] amounts take the color of [flow]. */
internal class TableRow(val cells: List<String>, val flow: MoneyFlow = MoneyFlow.Neutral, val colored: Int = cells.size - 1)

/**
 * A table whose columns line up from row to row: every amount column is at least as wide as its widest cell and every amount stays on one line, the room left over is shared by the amount columns.
 * A table wider than the space slides sideways instead of shrinking; with [wrapsName] the name may wrap onto several lines and the table never slides.
 */
@Composable
internal fun ShopTable(header: List<String>, rows: List<TableRow>, wrapsName: Boolean = false) {
    if (wrapsName) TableGrid(header, rows, true, Modifier.fillMaxWidth())
    else BoxWithConstraints(Modifier.fillMaxWidth()) {
        TableGrid(header, rows, false, Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth))
    }
}

/** The header, a hairline under it and the rows, laid out together so the columns agree; a row sits on the first baseline of its cells. */
@Composable
private fun TableGrid(header: List<String>, rows: List<TableRow>, wrapsName: Boolean, modifier: Modifier) {
    val cols = header.size
    Layout(
        {
            header.forEach { ShopText(it, TextRole.Caption, muted = true) }
            ShopDivider()
            rows.forEach { row ->
                row.cells.forEachIndexed { i, cell ->
                    if (i == 0) ShopText(cell) else ShopText(cell, TextRole.Amount, color = if (i <= row.colored) row.flow.color() else Color.Unspecified)
                }
            }
        },
        modifier,
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val divider = measurables[cols]
        val cells = measurables.filterIndexed { i, _ -> i != cols }
        val widths = IntArray(cols) { c -> cells.filterIndexed { i, _ -> i % cols == c }.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) } }
        val amounts = (1 until cols).sumOf { widths[it] } + gap * (cols - 1)
        val total = if (wrapsName) constraints.maxWidth else maxOf(widths[0] + amounts, constraints.minWidth)
        widths[0] = minOf(widths[0], total - amounts).coerceAtLeast(0)
        // The room left over goes to the amount columns, so on a wide page the figures spread out instead of running together at the right.
        val extra = (total - widths[0] - amounts).coerceAtLeast(0)
        for (c in 1 until cols) widths[c] += extra / (cols - 1) + if (c == cols - 1) extra % (cols - 1) else 0
        val placeables = cells.mapIndexed { i, m -> m.measure(Constraints(maxWidth = if (wrapsName && i % cols == 0) widths[0] else Constraints.Infinity)) }
        val lines = placeables.chunked(cols)
        val above = lines.map { line -> line.maxOf { it[FirstBaseline].coerceAtLeast(0) } }
        val dividerPlaceable = divider.measure(Constraints.fixedWidth(total))
        var y = 0
        var dividerY = 0
        val tops = lines.mapIndexed { r, line ->
            val top = y
            y += above[r] + line.maxOf { it.height - it[FirstBaseline].coerceAtLeast(0) }
            if (r == 0) { y += gap; dividerY = y; y += dividerPlaceable.height }
            y += gap
            top
        }
        layout(total, (y - gap).coerceAtLeast(0)) {
            var x = 0
            val starts = widths.map { w -> x.also { x += w + gap } }
            lines.forEachIndexed { r, line ->
                line.forEachIndexed { c, p ->
                    val px = if (c == 0) starts[0] else starts[c] + widths[c] - p.width
                    p.place(px, tops[r] + above[r] - p[FirstBaseline].coerceAtLeast(0))
                }
            }
            dividerPlaceable.place(0, dividerY)
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
