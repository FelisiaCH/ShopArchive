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

/**
 * `--uninstall {product code}`, from the installer's Uninstall ShopArchive shortcut: the msiexec command that removes this product,
 * or null for any other arguments. The shortcut runs the app rather than msiexec because Windows leaves out of Start a shortcut
 * an installer made to msiexec.
 */
internal fun uninstallCommand(args: Array<String>): List<String>? =
    args.takeIf { it.size == 2 && it[0] == "--uninstall" && PRODUCT_CODE.matches(it[1]) }?.let { listOf("msiexec", "/x", it[1]) }

private val PRODUCT_CODE = Regex("""\{[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}}""")

fun main(args: Array<String>) {
    uninstallCommand(args)?.let { ProcessBuilder(it).start(); return }
    app()
}

private fun app() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = stringResource(Res.string.app_name), // "ShopArchive"
        state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
    ) {
        SideEffect { window.minimumSize = Dimension(480, 560) }
        LaunchedEffect(Unit) { window.iconImages = windowIcons }
        ShopApp(DesktopContext)
    }
}
