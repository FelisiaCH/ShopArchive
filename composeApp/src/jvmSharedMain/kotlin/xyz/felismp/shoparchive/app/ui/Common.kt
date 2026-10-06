package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.LocalizedName
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun Failure.text(): String = when (this) {
    Failure.Unreachable -> stringResource(Res.string.err_unreachable)
    is Failure.Refused -> stringResource(refusalWords(code, reason))
    is Failure.Client -> problem.text()
    Failure.Other -> stringResource(Res.string.err_unknown)
}

/** The name in the app's language, English if that is empty. */
internal fun LocalizedName.pick(): String = when (Locale.getDefault().language) {
    "lo" -> lo
    "th" -> th
    else -> en
}.ifBlank { en }

/** `08:12` from a server timestamp like `2026-10-04T08:12:30+07:00`; the text itself if it is not one. */
internal fun clockTime(timestamp: String): String = try {
    OffsetDateTime.parse(timestamp).format(DateTimeFormatter.ofPattern("HH:mm"))
} catch (_: Exception) {
    timestamp
}

/** `+1,500` / `-1,500` for [minor] units of a currency with [exponent] decimals; a plain `0` for zero. */
internal fun signedAmount(minor: Long, exponent: Int): String =
    (if (minor > 0) "+" else "") + xyz.felismp.shoparchive.app.flow.displayAmount(minor, exponent)

/** The name of category [key] in the app's language; the key itself when the shop no longer lists it. */
internal fun xyz.felismp.shoparchive.app.flow.Workspace.categoryName(key: String?): String? {
    if (key == null) return null
    val list = (config.value as? xyz.felismp.shoparchive.app.flow.ConfigState.Ready)?.config?.categories.orEmpty()
    return list.firstOrNull { it.key == key }?.name?.pick() ?: key
}

internal fun xyz.felismp.shoparchive.app.flow.Workspace.branchName(key: String): String = branches.firstOrNull { it.key == key }?.displayName ?: key

/** One row of a table: the first cell is text, the others are amounts that line up on the right. */
@Composable
internal fun TableLine(cells: List<String>, header: Boolean = false) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, cell ->
            val mod = androidx.compose.ui.Modifier.weight(if (i == 0) 1.3f else 1f)
            when {
                header -> ShopText(cell, TextRole.Caption, muted = true, modifier = mod)
                i == 0 -> ShopText(cell, modifier = mod)
                else -> ShopText(cell, TextRole.Amount, modifier = mod)
            }
        }
    }
}
