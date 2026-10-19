package xyz.felismp.shoparchive.app.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import xyz.felismp.shoparchive.app.theme.FluentShapes
import xyz.felismp.shoparchive.app.theme.FluentTheme
import xyz.felismp.shoparchive.app.theme.LocalShopFonts
import xyz.felismp.shoparchive.app.theme.shopAnnotatedString

/** Text box: caption label above, 1px stroke and, on focus, the accent line under it (2 dp) as in WinUI. [amount] sets what is typed as a figure. */
@Composable
fun FluentTextBox(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    enabled: Boolean = true,
    amount: Boolean = false,
) {
    val c = FluentTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    // What is typed gets the same Lao spans as FluentText, or Lao would show in a font Windows picks.
    val fonts = LocalShopFonts.current
    val spans = remember(fonts) { VisualTransformation { typed -> TransformedText(shopAnnotatedString(typed.text, fonts), OffsetMapping.Identity) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FluentText(label, style = FluentTheme.typography.caption, color = c.shop.textSecondary)
        val accent = c.shop.accent
        BasicTextField(
            value, onValueChange,
            Modifier.fillMaxWidth().heightIn(min = if (singleLine) 36.dp else 96.dp).clip(FluentShapes.control)
                .background(if (enabled) c.controlFill else c.layer)
                .border(1.dp, c.controlStroke, FluentShapes.control)
                .drawBehind { if (focused) drawRect(accent, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx())) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            enabled = enabled,
            singleLine = singleLine,
            textStyle = (if (amount) FluentTheme.typography.display.copy(fontSize = 18.sp, lineHeight = 28.sp, fontFamily = FontFamily.Monospace) else FluentTheme.typography.body)
                .copy(color = if (enabled) c.shop.text else c.shop.textSecondary),
            cursorBrush = SolidColor(c.shop.text),
            visualTransformation = if (password) PasswordVisualTransformation() else spans,
            keyboardOptions = KeyboardOptions.Default,
            interactionSource = source,
        )
    }
}

/** InfoBar: tinted bar with a stroke, radius 8 and a 4dp severity stripe at the left. */
@Composable
fun FluentInfoBar(text: String, stripe: Color, fill: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(FluentShapes.card).background(fill).border(1.dp, stripe.copy(alpha = 0.25f), FluentShapes.card).height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
        FluentText(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = FluentTheme.colors.shop.text)
    }
}

/** Pill with a short status text. */
@Composable
fun FluentChip(text: String, textColor: Color, fill: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(fill).padding(horizontal = 12.dp, vertical = 3.dp)) {
        FluentText(text, style = FluentTheme.typography.caption, color = textColor)
    }
}
