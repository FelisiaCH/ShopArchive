package xyz.felismp.shoparchive.app.fluent

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import xyz.felismp.shoparchive.app.theme.FluentShapes
import xyz.felismp.shoparchive.app.theme.FluentTheme
import xyz.felismp.shoparchive.app.theme.LocalShopFonts
import xyz.felismp.shoparchive.app.theme.shopAnnotatedString

/** Themed text; Lao, Thai and kip runs use the bundled Noto fonts, the rest the style's own family. */
@Composable
fun FluentText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = FluentTheme.typography.body,
    color: Color = FluentTheme.colors.shop.text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val fonts = LocalShopFonts.current
    val annotated = remember(text, fonts) { shopAnnotatedString(text, fonts) }
    BasicText(annotated, modifier, style.copy(color = color), maxLines = maxLines, overflow = overflow)
}

/** Card: card fill on the layer, 1px stroke, radius 8. Without [padded] the content runs to the edges; with [onClick] the card is one target and shows hover and press. */
@Composable
fun FluentCard(modifier: Modifier = Modifier, padded: Boolean = true, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = FluentTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused = focusRingVisible(source)
    val fill = when {
        onClick != null && pressed -> c.pressedOverlay.compositeOver(c.card)
        onClick != null && hovered -> c.hoverOverlay.compositeOver(c.card)
        else -> c.card
    }
    Column(
        modifier.fillMaxWidth().clip(FluentShapes.card).background(fill)
            .border(if (focused) 2.dp else 1.dp, if (focused) c.shop.text else c.cardStroke, FluentShapes.card)
            .then(if (onClick != null) Modifier.hoverable(source).clickable(source, indication = null, role = Role.Button, onClick = onClick) else Modifier)
            .then(if (padded) Modifier.padding(16.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(if (padded) 8.dp else 0.dp),
        content = content,
    )
}

/** True when the control has focus and the user got there with the keyboard (no ring after a mouse click). */
@Composable
private fun focusRingVisible(source: MutableInteractionSource): Boolean {
    val focused by source.collectIsFocusedAsState()
    return focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
}

/** Keyboard focus ring: 2px in the text color, drawn in place of the control's own stroke. */
private fun Modifier.controlBorder(focused: Boolean, stroke: Color, textColor: Color, shape: androidx.compose.ui.graphics.Shape) =
    if (focused) border(2.dp, textColor, shape) else border(1.dp, stroke, shape)

/** Standard (subtle) button, or the accent button when [accent] is set. Height 36, radius 4. [icon] is 16 and goes before the text. */
@Composable
fun FluentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false, icon: ImageVector? = null) {
    val c = FluentTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused = focusRingVisible(source)
    val base = if (accent) c.shop.accent else c.controlFill
    val overlay = when {
        accent -> c.shop.onAccent.copy(alpha = if (pressed) 0.18f else 0.10f)
        pressed -> c.pressedOverlay
        else -> c.hoverOverlay
    }
    val fill = if (hovered || pressed) overlay.compositeOver(base) else base
    Box(
        modifier.heightIn(min = 36.dp).clip(FluentShapes.control).background(fill)
            .controlBorder(focused, if (accent) Color.Transparent else c.controlStroke, c.shop.text, FluentShapes.control)
            .hoverable(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val content = if (accent) c.shop.onAccent else c.shop.text
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Image(rememberVectorPainter(icon), contentDescription = null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(content))
            FluentText(text, style = FluentTheme.typography.body, color = content)
        }
    }
}

/** ToggleSwitch: 40x20 track, 12px thumb; on = accent fill, off = outline only. */
@Composable
fun FluentToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = FluentTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val focused = focusRingVisible(source)
    val thumb by animateDpAsState(if (hovered) 14.dp else 12.dp)
    val thumbOffset by animateDpAsState(if (checked) 24.dp else 4.dp)
    val track = RoundedCornerShape(10.dp)
    Box(
        modifier.size(width = 40.dp, height = 20.dp).clip(track)
            .background(if (checked) c.shop.accent else Color.Transparent)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) c.shop.text else if (checked) Color.Transparent else c.toggleOffStroke,
                track,
            )
            .hoverable(source)
            .toggleable(checked, source, indication = null, role = Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = thumbOffset - (thumb - 12.dp) / 2).size(thumb).clip(CircleShape)
                .background(if (checked) c.shop.onAccent else c.shop.textSecondary),
        )
    }
}

/** Segmented selector: the options in a recessed track, the selected one raised on a card with its stroke and in the strong weight. [icon] is 16 and goes before the text. */
@Composable
fun <T> FluentSelectorBar(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
) {
    val c = FluentTheme.colors
    Row(modifier.clip(FluentShapes.card).background(c.shop.surfaceSelected).border(1.dp, c.cardStroke, FluentShapes.card).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((value, label) in options) {
            val isSelected = value == selected
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val focused = focusRingVisible(source)
            val content = if (isSelected) c.shop.text else c.shop.textSecondary
            Row(
                Modifier.clip(FluentShapes.control)
                    .background(if (isSelected) c.controlFill else if (hovered) c.hoverOverlay else Color.Transparent)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.shop.text else if (isSelected) c.cardStroke else Color.Transparent, FluentShapes.control)
                    .hoverable(source)
                    .selectable(isSelected, source, indication = null, role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon(value)?.let { Image(rememberVectorPainter(it), contentDescription = null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(content)) }
                FluentText(label, style = if (isSelected) FluentTheme.typography.bodyStrong else FluentTheme.typography.body, color = content)
            }
        }
    }
}

/**
 * Left navigation pane item: 40 high, radius 4, a 3x16 accent bar at the left edge and a stronger fill when selected. [compact] shows the icon alone,
 * centered (the 48 wide strip), with [tooltip] shown when the pointer rests on it. [badge] is a count in a pill at the right (a dot in the strip).
 */
@Composable
fun FluentNavigationItem(label: String, icon: ImageVector?, selected: Boolean, compact: Boolean = false, tooltip: String = label, badge: Int? = null, onClick: () -> Unit) {
    if (compact) FluentTooltip(tooltip) { NavigationItemBox(label, icon, selected, true, badge, onClick) }
    else NavigationItemBox(label, icon, selected, false, badge, onClick)
}

/** A small label that shows to the right of [content] when the pointer rests on it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FluentTooltip(text: String, content: @Composable () -> Unit) {
    val c = FluentTheme.colors
    TooltipArea(
        tooltip = {
            FluentText(
                text, Modifier.clip(FluentShapes.control).background(c.card).border(1.dp, c.cardStroke, FluentShapes.control).padding(horizontal = 8.dp, vertical = 4.dp),
                style = FluentTheme.typography.caption,
            )
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.ComponentRect(Alignment.CenterEnd, Alignment.CenterEnd, DpOffset(8.dp, 0.dp)),
        content = content,
    )
}

@Composable
private fun NavigationItemBox(label: String, icon: ImageVector?, selected: Boolean, compact: Boolean, badge: Int?, onClick: () -> Unit) {
    val c = FluentTheme.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused = focusRingVisible(source)
    val fill = when {
        pressed -> c.pressedOverlay
        selected -> c.shop.text.copy(alpha = 0.08f)
        hovered -> c.hoverOverlay
        else -> Color.Transparent
    }
    val ink = if (selected) c.shop.text else c.shop.textSecondary
    Box(
        Modifier.padding(horizontal = 4.dp).fillMaxWidth().heightIn(min = 40.dp).clip(FluentShapes.control).background(fill)
            .then(if (focused) Modifier.border(2.dp, c.shop.text, FluentShapes.control) else Modifier)
            .hoverable(source)
            .selectable(selected, source, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart,
    ) {
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).size(width = 3.dp, height = 16.dp).clip(RoundedCornerShape(1.5.dp))
                    .background(c.shop.accent),
            )
        }
        if (compact) {
            if (icon != null) Image(rememberVectorPainter(icon), contentDescription = label, Modifier.size(18.dp), colorFilter = ColorFilter.tint(ink))
            if (badge != null && badge > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp).size(8.dp).clip(CircleShape).background(c.shop.accent))
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) Image(rememberVectorPainter(icon), contentDescription = null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(ink))
                FluentText(label, Modifier.weight(1f), style = if (selected) FluentTheme.typography.bodyStrong else FluentTheme.typography.body, color = ink)
                if (badge != null && badge > 0) {
                    Box(Modifier.defaultMinSize(minWidth = 22.dp).clip(CircleShape).background(c.shop.accent).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        FluentText(badge.toString(), style = FluentTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold), color = c.shop.onAccent)
                    }
                }
            }
        }
    }
}
