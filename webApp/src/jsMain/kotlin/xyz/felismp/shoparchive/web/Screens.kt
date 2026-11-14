package xyz.felismp.shoparchive.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import xyz.felismp.shoparchive.app.flow.AppFlow
import xyz.felismp.shoparchive.app.flow.AppState
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.app.flow.ReauthPrompt
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION

@Composable
private fun Status(t: Translator, busy: Boolean, problem: Problem?) {
    if (busy) Banner(t.t("working"))
    problem?.let { Banner(t.text(it.message()), error = true) }
}

/** The page's own server is added by itself (see [AutoAdd]); this shows that, or what stopped it with a button to try again. */
@Composable
fun ServersScreen(t: Translator, s: AppState.Servers, autoAddFired: Boolean, retry: () -> Unit) {
    val view = serversView(s, autoAddFired)
    Screen(t.t("app_name")) {
        Banner(t.text(view.message), view.error)
        if (view.retry) Btn(t.t("retry"), enabled = !s.busy, action = retry)
    }
}

/** A user name and PIN; a user who has no PIN yet chooses one (typed twice) with the setup code the owner gave them. */
@Composable
fun LoginScreen(t: Translator, s: AppState.Login, flow: AppFlow) {
    var username by remember { mutableStateOf("") }
    // Secrets are only ever in memory here, never stored.
    var pin by remember { mutableStateOf("") }
    var setupCode by remember(s.needsNewPin) { mutableStateOf("") }
    var newPin by remember(s.needsNewPin) { mutableStateOf("") }
    var newPinRepeat by remember(s.needsNewPin) { mutableStateOf("") }
    val submit = { flow.login(username, pin, newPin.takeIf { s.needsNewPin }, newPinRepeat.takeIf { s.needsNewPin }, setupCode.takeIf { s.needsNewPin }) }
    Screen(t.t(if (s.adding) "add_user_title" else "login_title")) {
        P(attrs = { classes("title") }) { Text(s.serverName.ifBlank { s.server }) }
        SubmitForm(submit) {
            Field(t.t("username_label"), username, { username = it }, enabled = !s.busy)
            if (s.needsNewPin) {
                Note(t.t("login_set_pin"))
                Field(t.t("setup_code_label"), setupCode, { setupCode = it }, FieldKind.Pin, enabled = !s.busy)
                // An old server does not say how many digits: then 4 to 12, and the server refuses a wrong length in words.
                Field(t.t("pin_new", s.pinLength?.toString() ?: "4–12"), newPin, { newPin = it }, FieldKind.Pin, enabled = !s.busy)
                Field(t.t("pin_repeat"), newPinRepeat, { newPinRepeat = it }, FieldKind.Pin, enabled = !s.busy)
            } else {
                Field(t.t("pin_field"), pin, { pin = it }, FieldKind.Pin, enabled = !s.busy)
            }
            Status(t, s.busy, s.problem)
            Btn(t.t("login_submit"), enabled = !s.busy, submit = true)
        }
        if (s.adding) Btn(t.t("add_user_back"), enabled = !s.busy, primary = false, action = flow::cancelLogin)
    }
}

/** Personal device: the one user. Shared device: the people who logged in here, then the PIN of the one picked. */
@Composable
fun LockedScreen(t: Translator, s: AppState.Locked, flow: AppFlow) {
    // A new field when the server asks for the password instead, or another user is picked: what was typed is not the next secret.
    var secret by remember(s.needsPassword, s.username) { mutableStateOf("") }
    Screen(t.t("locked_title")) {
        val username = s.username
        if (username == null) {
            Note(t.t("lock_pick_user"))
            Div(attrs = { classes("stack") }) {
                s.users.forEach { name -> Btn(name, enabled = !s.busy, primary = false) { flow.selectUser(name) } }
            }
            Status(t, s.busy, s.problem)
            Btn(t.t("lock_add_user"), enabled = !s.busy, primary = false, action = flow::addUser)
        } else {
            P { Text(t.t("locked_body", username, s.server)) }
            SubmitForm({ flow.unlock(secret) }) {
                Field(t.t(if (s.needsPassword) "password_enter" else "pin_field"), secret, { secret = it }, if (s.needsPassword) FieldKind.Password else FieldKind.Pin, enabled = !s.busy)
                Status(t, s.busy, s.problem)
                Btn(t.t(if (s.needsPassword) "unlock_password" else "unlock_pin"), enabled = !s.busy, submit = true)
            }
            if (s.shared) Btn(t.t("lock_other_user"), enabled = !s.busy, primary = false) { flow.selectUser(null) }
            else Btn(t.t("lock_add_user"), enabled = !s.busy, primary = false, action = flow::addUser)
        }
    }
}

/** The PIN or password an important action asked for. */
@Composable
fun ReauthScreen(t: Translator, p: ReauthPrompt, flow: AppFlow) {
    var secret by remember(p.needsPassword) { mutableStateOf("") }
    Screen(t.t("reauth_title")) {
        Note(t.t(if (p.needsPassword) "reauth_body_password" else "reauth_body_pin"))
        SubmitForm({ flow.submitReauth(secret) }) {
            Field(t.t(if (p.needsPassword) "password_enter" else "pin_field"), secret, { secret = it }, if (p.needsPassword) FieldKind.Password else FieldKind.Pin, enabled = !p.busy)
            Status(t, p.busy, p.problem)
            Btn(t.t("reauth_confirm"), enabled = !p.busy, submit = true)
        }
        Btn(t.t("cancel"), enabled = !p.busy, primary = false, action = flow::cancelReauth)
    }
}

/** A saved server showed another key: Cancel is the safe default, trusting the new key the secondary choice (the same as the apps). */
@Composable
fun CertChangedScreen(t: Translator, s: AppState.CertChanged, flow: AppFlow) {
    Screen(t.t("pinmm_title")) {
        P(attrs = { classes("title") }) { Text(s.name.ifBlank { s.address }) }
        Banner(t.t("cert_changed_body"), error = true)
        Status(t, s.busy, s.problem)
        Btn(t.t("cancel"), enabled = !s.busy, action = flow::cancelCertChanged)
        Btn(t.t("cert_trust"), enabled = !s.busy, primary = false, action = flow::trustNewKey)
    }
}

@Composable
fun ProtocolMismatchScreen(t: Translator, s: AppState.ProtocolMismatch) {
    Screen(t.t("proto_title")) {
        Banner(t.t("proto_body"), error = true)
        P { Text(t.t("proto_versions", PROTOCOL_VERSION.toString(), s.serverProtocol?.toString() ?: t.t("proto_unknown"))) }
    }
}
