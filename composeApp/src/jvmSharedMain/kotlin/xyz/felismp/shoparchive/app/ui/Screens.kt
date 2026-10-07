package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.client.formatFingerprint
import xyz.felismp.shoparchive.app.AppBuild
import xyz.felismp.shoparchive.app.flow.AppFlow
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.EnrollInput
import xyz.felismp.shoparchive.app.flow.PasswordHint
import xyz.felismp.shoparchive.app.flow.PinHint
import xyz.felismp.shoparchive.app.flow.passwordHints
import xyz.felismp.shoparchive.app.flow.pinHints
import xyz.felismp.shoparchive.app.flow.formatCountdown
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.app.flow.ReauthPrompt
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION

private enum class PairTab { LINK, CODE }

@Composable
fun AppHost(flow: AppFlow) {
    val prompt = flow.reauth.collectAsState().value
    if (prompt != null) {
        ReauthScreen(prompt, flow)
        return
    }
    when (val s = flow.state.collectAsState().value) {
        AppState.Starting -> ScreenFrame(stringResource(Res.string.app_name)) { ShopText(stringResource(Res.string.starting), muted = true) }
        is AppState.Servers -> ServersScreen(s, flow)
        is AppState.Pair -> PairScreen(s, flow)
        is AppState.Enroll -> EnrollScreen(s, flow)
        is AppState.Locked -> LockedScreen(s, flow)
        is AppState.Unlocked -> flow.workspace?.let { Shell(s, flow, it) }
        is AppState.Settings -> SettingsScreen(s, flow)
        is AppState.CertChanged -> CertChangedScreen(s, flow)
        is AppState.ProtocolMismatch -> ScreenFrame(stringResource(Res.string.proto_title)) {
            ShopBanner(stringResource(Res.string.proto_body), Tone.Error)
            ShopText(stringResource(Res.string.proto_versions, PROTOCOL_VERSION.toString(), s.serverProtocol?.toString() ?: stringResource(Res.string.proto_unknown)))
        }
    }
}

@Composable
private fun Status(busy: Boolean, problem: Problem?) {
    if (busy) ShopBanner(stringResource(Res.string.working), Tone.Info)
    problem?.let { ShopBanner(it.text(), Tone.Error) }
}

/** A saved server showed another key: Cancel is the safe default, trusting the new key is the secondary choice. */
@Composable
private fun CertChangedScreen(s: AppState.CertChanged, flow: AppFlow) {
    ScreenFrame(stringResource(Res.string.pinmm_title)) {
        ShopText(s.name.ifBlank { s.address }, TextRole.Title)
        ShopText(s.address, TextRole.Caption, muted = true)
        ShopBanner(stringResource(Res.string.cert_changed_body), Tone.Error)
        Status(s.busy, s.problem)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopButton(stringResource(Res.string.cancel), flow::cancelCertChanged, enabled = !s.busy)
            ShopButton(stringResource(Res.string.cert_trust), flow::trustNewKey, primary = false, enabled = !s.busy)
        }
    }
}

/** The home screen: the servers saved here, the others found on the local network, and adding one by its address. */
@Composable
private fun ServersScreen(s: AppState.Servers, flow: AppFlow) {
    var address by rememberSaveable { mutableStateOf("") }
    ScreenFrame(stringResource(Res.string.servers_title)) {
        if (s.saved.isEmpty()) ShopText(stringResource(Res.string.servers_none), muted = true)
        s.saved.forEach { row ->
            ShopCard(Modifier.fillMaxWidth()) {
                ShopText(row.name, TextRole.Title)
                ShopText(row.endpoint + " · " + pluralStringResource(Res.plurals.server_users, row.userCount, row.userCount), TextRole.Caption, muted = true)
                ShopButton(stringResource(Res.string.servers_open), { flow.openServer(row.serverId) }, primary = false, enabled = !s.busy)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopText(stringResource(Res.string.servers_found), TextRole.Title, modifier = Modifier.weight(1f))
            ShopButton(stringResource(Res.string.servers_refresh), flow::refreshFound, primary = false, enabled = !s.searching && !s.busy)
        }
        when {
            s.searching -> ShopText(stringResource(Res.string.servers_searching), muted = true)
            s.found.isEmpty() -> ShopText(stringResource(Res.string.servers_found_none), muted = true)
        }
        s.found.forEach { found ->
            ShopButton("${found.name} · ${found.endpoint}", { flow.openFound(found) }, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
        }

        ShopTextField(address, { address = it }, stringResource(Res.string.servers_add_label), enabled = !s.busy)
        Status(s.busy, s.problem)
        ShopButton(stringResource(Res.string.servers_add), { flow.addServer(address) }, Modifier.fillMaxWidth(), enabled = !s.busy && address.isNotBlank())
    }
}

@Composable
private fun PairScreen(s: AppState.Pair, flow: AppFlow) {
    // A server picked from the list brings its address: the manual-code form starts with it.
    var tab by rememberSaveable(s.address) { mutableStateOf(if (s.address.isEmpty()) PairTab.LINK else PairTab.CODE) }
    // Secrets (link, code, password, PIN) use remember, never rememberSaveable: saved state can outlive the process outside the credential store.
    var link by remember { mutableStateOf("") }
    var address by rememberSaveable(s.address) { mutableStateOf(s.address) }
    var username by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    ScreenFrame(stringResource(if (s.adding) Res.string.add_user_title else Res.string.pair_title)) {
        val check = s.check
        val preview = s.preview
        when {
            check != null -> {
                ShopText(stringResource(Res.string.fp_title), TextRole.Title)
                ShopText(stringResource(Res.string.fp_body))
                ShopText(check.address, muted = true)
                ShopText(check.fingerprint, TextRole.Mono)
                Status(s.busy, s.problem)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShopButton(stringResource(Res.string.fp_confirm), flow::confirmFingerprint, enabled = !s.busy)
                    ShopButton(stringResource(Res.string.cancel), flow::cancelFingerprint, primary = false, enabled = !s.busy)
                }
            }
            preview != null -> {
                ShopText(stringResource(Res.string.link_found))
                ShopText(stringResource(Res.string.link_user, preview.u))
                ShopText(stringResource(Res.string.link_addresses, preview.ep.joinToString(", ")))
                ShopText(stringResource(Res.string.link_fingerprint), muted = true)
                ShopText(formatFingerprint(preview.fp), TextRole.Mono)
                Status(s.busy, s.problem)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShopButton(stringResource(Res.string.continue_), flow::redeemLink, enabled = !s.busy)
                    ShopButton(stringResource(Res.string.cancel), flow::cancelPreview, primary = false, enabled = !s.busy)
                }
            }
            else -> {
                ShopSegmented(
                    listOf(PairTab.LINK to stringResource(Res.string.tab_link), PairTab.CODE to stringResource(Res.string.tab_code)),
                    tab, { tab = it },
                )
                if (tab == PairTab.LINK) {
                    ShopTextField(link, { link = it }, stringResource(Res.string.link_label), kind = FieldKind.Multiline, enabled = !s.busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShopButton(stringResource(Res.string.continue_), { flow.previewLink(link) }, enabled = !s.busy && link.isNotBlank())
                        ShopButton(stringResource(Res.string.paste), { clipboard.getText()?.text?.let { link = it } }, primary = false)
                    }
                } else {
                    ShopTextField(address, { address = it }, stringResource(Res.string.address_label), enabled = !s.busy)
                    ShopTextField(username, { username = it }, stringResource(Res.string.username_label), enabled = !s.busy)
                    ShopTextField(code, { code = it }, stringResource(Res.string.code_label), enabled = !s.busy)
                    ShopButton(stringResource(Res.string.continue_), { flow.submitManual(address, username, code) }, enabled = !s.busy)
                }
                Status(s.busy, s.problem)
                if (s.adding) ShopButton(stringResource(Res.string.add_user_back), flow::cancelAddUser, primary = false, enabled = !s.busy)
                else ShopButton(stringResource(Res.string.servers_back), flow::showServers, primary = false, enabled = !s.busy)
            }
        }
    }
}

@Composable
private fun EnrollScreen(s: AppState.Enroll, flow: AppFlow) {
    val r = s.redeem
    var password by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pinRepeat by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(DeviceMode.PERSONAL) }
    var label by rememberSaveable { mutableStateOf(flow.device.label) }
    val digits = r.pinLength.toString()
    // The time is counted by the flow from the server's seconds; the screen only asks again now and then to redraw it.
    var left by remember(s.deadlineMs) { mutableStateOf(flow.enrollSecondsLeft()) }
    LaunchedEffect(s.deadlineMs) {
        while (true) {
            left = flow.enrollSecondsLeft()
            if ((left ?: 0L) == 0L) break
            delay(250)
        }
    }
    val secondsLeft = left
    if (secondsLeft == 0L) {
        // Out of time: nothing to fill in any more, only the way back to a new pairing.
        ScreenFrame(stringResource(Res.string.enroll_title)) {
            ShopBanner(stringResource(Res.string.err_enroll_expired), Tone.Error)
            ShopButton(stringResource(Res.string.start_over), flow::startOver, enabled = !s.busy)
        }
        return
    }
    ScreenFrame(stringResource(Res.string.enroll_title)) {
        ShopText(stringResource(Res.string.enroll_user, r.username, s.server))
        if (secondsLeft != null) ShopText(stringResource(Res.string.enroll_time_left, formatCountdown(secondsLeft)), TextRole.Caption, muted = true)
        if (r.passwordRequired) {
            if (r.hasPassword) {
                ShopTextField(password, { password = it }, stringResource(Res.string.password_enter), kind = FieldKind.Password, enabled = !s.busy)
            } else {
                ShopTextField(password, { password = it }, stringResource(Res.string.password_new), kind = FieldKind.Password, enabled = !s.busy)
                ShopTextField(passwordRepeat, { passwordRepeat = it }, stringResource(Res.string.password_repeat), kind = FieldKind.Password, enabled = !s.busy)
                // Hints only: the server takes any password, so the button below stays on.
                passwordHints(password, r.username, r.serverName, r.suggestedPasswordMin).forEach { ShopBanner(passwordHintWords(it), Tone.Info) }
            }
        }
        if (r.hasPin) {
            ShopTextField(pin, { pin = it }, stringResource(Res.string.pin_enter, digits), kind = FieldKind.Pin, enabled = !s.busy)
        } else {
            ShopTextField(pin, { pin = it }, stringResource(Res.string.pin_new, digits), kind = FieldKind.Pin, enabled = !s.busy)
            ShopTextField(pinRepeat, { pinRepeat = it }, stringResource(Res.string.pin_repeat), kind = FieldKind.Pin, enabled = !s.busy)
            pinHints(pin, r.pinLength).forEach { ShopBanner(pinHintWords(it), Tone.Info) }
        }
        if (!s.adding) {
            ShopText(stringResource(Res.string.mode_label))
            ShopSegmented(
                listOf(DeviceMode.PERSONAL to stringResource(Res.string.mode_personal), DeviceMode.SHARED to stringResource(Res.string.mode_shared)),
                mode, { mode = it },
            )
            ShopText(stringResource(if (mode == DeviceMode.PERSONAL) Res.string.mode_personal_hint else Res.string.mode_shared_hint), TextRole.Caption, muted = true)
            ShopTextField(label, { label = it }, stringResource(Res.string.device_label), enabled = !s.busy)
        }
        Status(s.busy, s.problem)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopButton(
                stringResource(Res.string.enroll_submit),
                { flow.enroll(EnrollInput(password, passwordRepeat, pin, pinRepeat, mode, label)) },
                enabled = !s.busy,
            )
            ShopButton(stringResource(Res.string.start_over), flow::startOver, primary = false, enabled = !s.busy)
        }
    }
}

@Composable
private fun passwordHintWords(hint: PasswordHint): String = when (hint) {
    is PasswordHint.Short -> stringResource(Res.string.hint_password_short, hint.length.toString(), hint.suggestedMin.toString())
    PasswordHint.Common -> stringResource(Res.string.hint_password_common)
    is PasswordHint.HasUserName -> stringResource(Res.string.hint_password_has_username, hint.name)
    is PasswordHint.HasServerName -> stringResource(Res.string.hint_password_has_server_name, hint.name)
}

@Composable
private fun pinHintWords(hint: PinHint): String = when (hint) {
    is PinHint.Repeated -> stringResource(Res.string.hint_pin_repeated, hint.digit.toString())
    is PinHint.Run -> stringResource(Res.string.hint_pin_run, hint.first.toString(), hint.last.toString())
}

/** Personal device: the one user. Shared device: the people paired here, then the PIN of the one picked. */
@Composable
private fun LockedScreen(s: AppState.Locked, flow: AppFlow) {
    // A new field when the server asks for the password instead, or another user is picked: what was typed is not the next secret.
    var secret by remember(s.needsPassword, s.username) { mutableStateOf("") }
    ScreenFrame(stringResource(Res.string.locked_title)) {
        val username = s.username
        if (username == null) {
            ShopText(stringResource(Res.string.lock_pick_user))
            s.users.forEach { name -> ShopButton(name, { flow.selectUser(name) }, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy) }
            Status(s.busy, s.problem)
            ShopButton(stringResource(Res.string.lock_add_user), flow::addUser, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
        } else {
            ShopText(stringResource(Res.string.locked_body, username, s.server))
            ShopTextField(
                secret, { secret = it },
                stringResource(if (s.needsPassword) Res.string.password_enter else Res.string.pin_field),
                kind = if (s.needsPassword) FieldKind.Password else FieldKind.Pin, enabled = !s.busy,
            )
            Status(s.busy, s.problem)
            ShopButton(
                stringResource(if (s.needsPassword) Res.string.unlock_password else Res.string.unlock_pin),
                { flow.unlock(secret) }, Modifier.fillMaxWidth(), enabled = !s.busy,
            )
            if (s.shared) ShopButton(stringResource(Res.string.lock_other_user), { flow.selectUser(null) }, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
        }
        ShopButton(stringResource(Res.string.servers_back), flow::showServers, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
    }
}

/** The PIN or password an important action asked for. */
@Composable
private fun ReauthScreen(p: ReauthPrompt, flow: AppFlow) {
    var secret by remember(p.needsPassword) { mutableStateOf("") }
    ScreenFrame(stringResource(Res.string.reauth_title)) {
        ShopText(stringResource(if (p.needsPassword) Res.string.reauth_body_password else Res.string.reauth_body_pin))
        ShopTextField(
            secret, { secret = it },
            stringResource(if (p.needsPassword) Res.string.password_enter else Res.string.pin_field),
            kind = if (p.needsPassword) FieldKind.Password else FieldKind.Pin, enabled = !p.busy,
        )
        Status(p.busy, p.problem)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopButton(stringResource(Res.string.reauth_confirm), { flow.submitReauth(secret) }, enabled = !p.busy)
            ShopButton(stringResource(Res.string.cancel), flow::cancelReauth, primary = false, enabled = !p.busy)
        }
    }
}

private val LANGUAGES = listOf<Pair<String?, StringResource>>(
    null to Res.string.lang_system, "en" to Res.string.lang_en, "lo" to Res.string.lang_lo, "th" to Res.string.lang_th,
)

@Composable
private fun SettingsScreen(s: AppState.Settings, flow: AppFlow) {
    val language by flow.language.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    ScreenFrame(stringResource(Res.string.nav_settings)) {
        ShopText(stringResource(Res.string.language_label), TextRole.Title)
        ShopSegmented(LANGUAGES.map { (tag, name) -> tag to stringResource(name) }, language, flow::setLanguage)

        ShopText(stringResource(Res.string.devices_title), TextRole.Title)
        val devices = s.devices
        if (devices == null) {
            if (s.problem == null) ShopText(stringResource(Res.string.working), muted = true)
        } else {
            devices.forEach { d ->
                ShopText(stringResource(Res.string.device_info, d.label, d.platform, d.lastUsed) + if (d.current) " · " + stringResource(Res.string.device_this) else "")
                if (!d.current) ShopButton(stringResource(Res.string.device_revoke), { flow.revokeDevice(d.id) }, primary = false, enabled = !s.busy)
            }
        }
        Status(s.busy, s.problem)

        // Only on a platform that can install an update; the version is the one this app was built as.
        flow.workspace?.updates?.let { update ->
            ShopText(stringResource(Res.string.update_title), TextRole.Title)
            ShopText(stringResource(Res.string.update_current, AppBuild.version.toString()), muted = true)
            UpdatePanel(update, asked = true)
            ShopButton(stringResource(Res.string.update_check), { update.check() }, primary = false)
        }

        if (confirmRemove) {
            ShopBanner(stringResource(Res.string.remove_confirm_body), Tone.Error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopButton(stringResource(Res.string.remove_confirm), flow::removeServer, enabled = !s.busy)
                ShopButton(stringResource(Res.string.cancel), { confirmRemove = false }, primary = false, enabled = !s.busy)
            }
        } else {
            ShopButton(stringResource(Res.string.remove_server), { confirmRemove = true }, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopButton(stringResource(Res.string.lock_now), { flow.lockNow() })
            ShopButton(stringResource(Res.string.back), flow::closeSettings, primary = false)
        }
    }
}

@Composable
internal fun Problem.text(): String = when (this) {
    Problem.InvalidLink -> stringResource(Res.string.err_invalid_link)
    Problem.Unreachable -> stringResource(Res.string.err_unreachable)
    Problem.PairingInvalid -> stringResource(Res.string.err_pairing_invalid)
    is Problem.TooManyAttempts -> if (seconds != null) stringResource(Res.string.err_too_many, seconds.toString()) else stringResource(Res.string.err_too_many_later)
    Problem.Banned -> stringResource(Res.string.err_banned)
    Problem.WrongCredentials -> stringResource(Res.string.err_wrong_credentials)
    Problem.CredentialsChanged -> stringResource(Res.string.err_credentials_changed)
    Problem.BadAddress -> stringResource(Res.string.err_bad_address)
    Problem.BadUsername -> stringResource(Res.string.err_bad_username)
    Problem.BadCode -> stringResource(Res.string.err_bad_code)
    Problem.PasswordEmpty -> stringResource(Res.string.err_password_empty)
    Problem.PasswordsDiffer -> stringResource(Res.string.err_passwords_differ)
    is Problem.PinFormat -> stringResource(Res.string.err_pin_format, length.toString())
    Problem.PinsDiffer -> stringResource(Res.string.err_pins_differ)
    Problem.LabelEmpty -> stringResource(Res.string.err_label_empty)
    Problem.SessionEnded -> stringResource(Res.string.err_session_ended)
    Problem.DeviceRejected -> stringResource(Res.string.err_device_rejected)
    Problem.EnrollmentExpired -> stringResource(Res.string.err_enroll_expired)
    Problem.WrongServer -> stringResource(Res.string.err_wrong_server)
    Problem.ReauthCancelled -> stringResource(Res.string.err_reauth_cancelled)
    is Problem.Rejected -> stringResource(refusalWords(code, reason))
    Problem.PinMismatch -> stringResource(Res.string.pinmm_body)
    Problem.OtherServer -> stringResource(Res.string.err_other_server)
    Problem.ProtocolMismatch -> stringResource(Res.string.proto_body)
    Problem.Unknown -> stringResource(Res.string.err_unknown)
}
