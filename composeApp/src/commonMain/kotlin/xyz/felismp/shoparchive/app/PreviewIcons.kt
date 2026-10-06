package xyz.felismp.shoparchive.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Minimal glyphs for the theme previews only (no icon library yet: the icon set is chosen in P09).
// Drawn in black; callers tint them.

private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = block)
    }.build()

internal val PreviewHomeIcon: ImageVector = icon("home") {
    moveTo(12f, 3f); lineTo(2f, 12f); horizontalLineTo(5f); verticalLineTo(21f); horizontalLineTo(10f)
    verticalLineTo(14f); horizontalLineTo(14f); verticalLineTo(21f); horizontalLineTo(19f)
    verticalLineTo(12f); horizontalLineTo(22f); close()
}

internal val PreviewChartIcon: ImageVector = icon("chart") {
    moveTo(4f, 20f); verticalLineTo(12f); horizontalLineTo(8f); verticalLineTo(20f); close()
    moveTo(10f, 20f); verticalLineTo(4f); horizontalLineTo(14f); verticalLineTo(20f); close()
    moveTo(16f, 20f); verticalLineTo(9f); horizontalLineTo(20f); verticalLineTo(20f); close()
}

internal val PreviewSettingsIcon: ImageVector = icon("settings") {
    moveTo(12f, 2.5f); arcTo(9.5f, 9.5f, 0f, true, true, 12f, 21.5f); arcTo(9.5f, 9.5f, 0f, true, true, 12f, 2.5f); close()
    moveTo(12f, 8f); arcTo(4f, 4f, 0f, true, false, 12f, 16f); arcTo(4f, 4f, 0f, true, false, 12f, 8f); close()
}
