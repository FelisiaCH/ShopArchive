package xyz.felismp.shoparchive.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The icons the screens use. Each platform draws them from its own icon set (Fluent on Windows, Material Symbols on Android). */
enum class ShopIcon { Today, Record, History, More, OpenDay, CloseDay, Save, AddPayment, Reports, Console, Settings, LockNow }

/**
 * One icon from an SVG's path data as given in the set. The grid is [grid] units square and starts at y = [top] (-960 for Material Symbols,
 * 0 for Fluent). The fill is black; the control tints it.
 */
internal fun iconVector(name: String, grid: Float, top: Float, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, grid, grid)
        .addGroup(translationY = -top)
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()
