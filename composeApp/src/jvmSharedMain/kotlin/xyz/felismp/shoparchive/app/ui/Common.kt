package xyz.felismp.shoparchive.app.ui

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.LocalizedName
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
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

/** `2026-10-19 09:47` in this device's time zone from a UTC instant like `2026-10-19T02:47:05Z`; the text itself if it is not one. */
internal fun localTime(instant: String): String = try {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.parse(instant).atZone(ZoneId.systemDefault()))
} catch (_: Exception) {
    instant
}

/** `+1,500` / `-1,500` for [minor] units of a currency with [exponent] decimals; a plain `0` for zero. */
internal fun signedAmount(minor: Long, exponent: Int): String =
    (if (minor > 0) "+" else "") + xyz.felismp.shoparchive.app.flow.displayAmount(minor, exponent)

/** The platform a device told the server it is on (`android`, `windows`, `ios`, or the desktop's OS name) as a person says it; any other word as it came. */
internal fun platformName(raw: String): String = when (raw) {
    "android" -> "Android"
    "windows" -> "Windows"
    "ios" -> "iOS"
    "linux" -> "Linux"
    "mac os x" -> "macOS"
    else -> raw
}

/** The name of category [key] in the app's language; the key itself when the shop no longer lists it. */
internal fun xyz.felismp.shoparchive.app.flow.Workspace.categoryName(key: String?): String? {
    if (key == null) return null
    val list = (config.value as? xyz.felismp.shoparchive.app.flow.ConfigState.Ready)?.config?.categories.orEmpty()
    return list.firstOrNull { it.key == key }?.name?.pick() ?: key
}

internal fun xyz.felismp.shoparchive.app.flow.Workspace.branchName(key: String): String = branches.firstOrNull { it.key == key }?.displayName ?: key
