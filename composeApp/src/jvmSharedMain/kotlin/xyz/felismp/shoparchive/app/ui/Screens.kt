package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.AppBuild
import xyz.felismp.shoparchive.app.flow.AppFlow
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.app.flow.ReauthPrompt
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION

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
        is AppState.Login -> LoginScreen(s, flow)
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

/** A user name and PIN; a user who has no PIN yet chooses one (typed twice). */
@Composable
private fun LoginScreen(s: AppState.Login, flow: AppFlow) {
    var username by rememberSaveable { mutableStateOf("") }
    // Secrets use remember, never rememberSaveable, like the other forms.
    var pin by remember { mutableStateOf("") }
    var newPin by remember(s.needsNewPin) { mutableStateOf("") }
    var newPinRepeat by remember(s.needsNewPin) { mutableStateOf("") }
    ScreenFrame(stringResource(if (s.adding) Res.string.add_user_title else Res.string.login_title)) {
        ShopText(s.serverName.ifBlank { s.server }, TextRole.Title)
        ShopText(s.server, TextRole.Caption, muted = true)
        ShopTextField(username, { username = it }, stringResource(Res.string.username_label), enabled = !s.busy)
        if (s.needsNewPin) {
            ShopText(stringResource(Res.string.login_set_pin))
            // An old server does not say how many digits: then 4 to 12, and the server refuses a wrong length in words.
            ShopTextField(newPin, { newPin = it }, stringResource(Res.string.pin_new, s.pinLength?.toString() ?: "4–12"), kind = FieldKind.Pin, enabled = !s.busy)
            ShopTextField(newPinRepeat, { newPinRepeat = it }, stringResource(Res.string.pin_repeat), kind = FieldKind.Pin, enabled = !s.busy)
        } else {
            ShopTextField(pin, { pin = it }, stringResource(Res.string.pin_field), kind = FieldKind.Pin, enabled = !s.busy)
        }
        Status(s.busy, s.problem)
        ShopButton(
            stringResource(Res.string.login_submit),
            { flow.login(username, pin, newPin.takeIf { s.needsNewPin }, newPinRepeat.takeIf { s.needsNewPin }) },
            Modifier.fillMaxWidth(), enabled = !s.busy,
        )
        if (s.adding) ShopButton(stringResource(Res.string.add_user_back), flow::cancelLogin, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
        ShopButton(stringResource(Res.string.servers_back), flow::showServers, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
    }
}

/** Personal device: the one user. Shared device: the people who logged in here, then the PIN of the one picked. */
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
            // A personal device has no list of users to pick from: adding one starts here (an old personal device is told what to do).
            else ShopButton(stringResource(Res.string.lock_add_user), flow::addUser, Modifier.fillMaxWidth(), primary = false, enabled = !s.busy)
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
            ShopButton(stringResource(Res.string.lock_now), { flow.lockNow() }, icon = ShopIcon.LockNow)
            ShopButton(stringResource(Res.string.back), flow::closeSettings, primary = false)
        }
    }
}

@Composable
internal fun Problem.text(): String = when (this) {
    Problem.Unreachable -> stringResource(Res.string.err_unreachable)
    is Problem.TooManyAttempts -> if (seconds != null) stringResource(Res.string.err_too_many, seconds.toString()) else stringResource(Res.string.err_too_many_later)
    Problem.Banned -> stringResource(Res.string.err_banned)
    Problem.WrongCredentials -> stringResource(Res.string.err_wrong_credentials)
    Problem.CredentialsChanged -> stringResource(Res.string.err_credentials_changed)
    Problem.BadAddress -> stringResource(Res.string.err_bad_address)
    Problem.BadUsername -> stringResource(Res.string.err_bad_username)
    Problem.PinsDiffer -> stringResource(Res.string.err_pins_differ)
    Problem.PinDigits -> stringResource(Res.string.err_pin_digits)
    is Problem.PinExactly -> stringResource(Res.string.err_pin_exact, length)
    Problem.PersonalDevice -> stringResource(Res.string.err_personal_device)
    Problem.SessionEnded -> stringResource(Res.string.err_session_ended)
    Problem.DeviceRejected -> stringResource(Res.string.err_device_rejected)
    Problem.ReauthCancelled -> stringResource(Res.string.err_reauth_cancelled)
    is Problem.Rejected -> stringResource(refusalWords(code, reason))
    Problem.PinMismatch -> stringResource(Res.string.pinmm_body)
    Problem.OtherServer -> stringResource(Res.string.err_other_server)
    Problem.ProtocolMismatch -> stringResource(Res.string.proto_body)
    Problem.Unknown -> stringResource(Res.string.err_unknown)
}
