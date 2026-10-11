package xyz.felismp.shoparchive.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.client.DesktopContext
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.app_name
import xyz.felismp.shoparchive.app.ui.ShopApp
import java.awt.Dimension
import javax.imageio.ImageIO

/**
 * The window and taskbar icon at every size Windows asks for (16 to 24 are the simplified drawing), so it picks one instead of
 * shrinking a large one; the installer's own is `icons/shoparchive.ico`.
 */
private val windowIcons = listOf(16, 20, 24, 32, 40, 48, 64, 256).map { ImageIO.read(object {}.javaClass.getResource("/icon/shoparchive-$it.png")) }

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = stringResource(Res.string.app_name), // "ShopArchive"
        state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
    ) {
        SideEffect { window.minimumSize = Dimension(480, 560) }
        LaunchedEffect(Unit) { window.iconImages = windowIcons }
        ShopApp(DesktopContext)
    }
}
