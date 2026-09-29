package xyz.felismp.shoparchive.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.app_name

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = stringResource(Res.string.app_name)) {
        App()
    }
}
