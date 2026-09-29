package xyz.felismp.shoparchive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.resources.Res
import xyz.felismp.shoparchive.app.resources.app_name

@Composable
fun App() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(stringResource(Res.string.app_name))
    }
}
