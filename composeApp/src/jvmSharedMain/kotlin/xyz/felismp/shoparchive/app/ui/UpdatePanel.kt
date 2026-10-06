package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.UpdateProblem
import xyz.felismp.shoparchive.app.flow.UpdateState
import xyz.felismp.shoparchive.app.flow.UpdateUi
import xyz.felismp.shoparchive.app.resources.*
import java.util.Locale

/**
 * What the app says about a newer version, and the buttons for it. Nothing is drawn unless there is something to say:
 * a newer file for this platform (Install, Later), a download in progress, a refused permission or a failure. [asked] also
 * shows the answers to a check the person started in Settings (checking, nothing newer, could not check).
 */
@Composable
fun UpdatePanel(update: UpdateState, asked: Boolean = false, modifier: Modifier = Modifier) {
    val ui by update.ui.collectAsState()
    when (val now = ui) {
        UpdateUi.Idle -> Unit
        UpdateUi.Checking -> if (asked) ShopBanner(stringResource(Res.string.working), Tone.Info, modifier)
        UpdateUi.UpToDate -> if (asked) ShopBanner(stringResource(Res.string.update_none), Tone.Success, modifier)
        is UpdateUi.Available -> {
            ShopBanner(stringResource(Res.string.update_available, now.file.version + if (now.file.build > 0) "-" + now.file.build else ""), Tone.Info, modifier)
            Choices(update, modifier)
        }
        is UpdateUi.Downloading -> ShopBanner(stringResource(Res.string.update_downloading, megabytes(now.bytes), megabytes(now.file.size)), Tone.Info, modifier)
        is UpdateUi.NeedsPermission -> {
            ShopBanner(stringResource(Res.string.update_permission), Tone.Info, modifier)
            Choices(update, modifier)
        }
        is UpdateUi.Failed -> {
            if (now.file == null && !asked) return
            val words = when (val p = now.problem) {
                is UpdateProblem.Call -> stringResource(if (now.file == null) Res.string.update_check_failed else Res.string.update_failed) + " " + p.failure.text()
                UpdateProblem.Damaged -> stringResource(Res.string.update_damaged)
                UpdateProblem.CannotInstall -> stringResource(Res.string.update_cannot_install)
            }
            ShopBanner(words, Tone.Error, modifier)
            if (now.file != null) Choices(update, modifier)
        }
    }
}

@Composable
private fun Choices(update: UpdateState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopButton(stringResource(Res.string.update_install), update::install)
        ShopButton(stringResource(Res.string.update_later), update::later, primary = false)
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)
