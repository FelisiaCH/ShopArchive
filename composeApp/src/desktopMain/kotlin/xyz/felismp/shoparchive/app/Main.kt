package xyz.felismp.shoparchive.app

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

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = stringResource(Res.string.app_name), // "ShopArchive"
        state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
    ) {
        SideEffect { window.minimumSize = Dimension(480, 560) }
        ShopApp(DesktopContext)
    }
}
