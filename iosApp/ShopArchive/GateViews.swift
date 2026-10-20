import SwiftUI
import ClientCore

/// A saved server showed another key: Cancel is the safe default, trusting the new key is the secondary choice.
struct CertChangedView: View {
    let state: AppStateCertChanged
    let flow: AppFlow

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading) {
                        Text(state.name.isEmpty ? state.address : state.name).font(.headline)
                        Text(state.address).font(.caption).foregroundColor(.secondary)
                    }
                }
                Section {
                    Text(text("cert_changed_body")).foregroundColor(Theme.moneyOut)
                }
                StatusSection(busy: state.busy, problem: state.problem)
                Section {
                    Button(text("cancel")) { flow.cancelCertChanged() }.fontWeight(.semibold)
                    Button(text("cert_trust"), role: .destructive) { flow.trustNewKey() }
                }
                .disabled(state.busy)
            }
            .navigationTitle(text("pinmm_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// The server and the app speak different protocol versions; nothing can be done here but to say so.
struct ProtocolMismatchView: View {
    let state: AppStateProtocolMismatch

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(text("proto_body")).foregroundColor(Theme.moneyOut)
                } footer: {
                    Text(text("proto_versions", String(PROTOCOL_VERSION), state.serverProtocol.map { String($0.intValue) } ?? text("proto_unknown")))
                }
            }
            .navigationTitle(text("proto_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// The PIN or password an important action asked for.
struct ReauthView: View {
    let prompt: ReauthPrompt
    let flow: AppFlow
    @State private var secret = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(text(prompt.needsPassword ? "reauth_body_password" : "reauth_body_pin"))
                    SecureField(text(prompt.needsPassword ? "password_enter" : "pin_field"), text: $secret)
                        .keyboardType(prompt.needsPassword ? .default : .numberPad)
                }
                .disabled(prompt.busy)

                StatusSection(busy: prompt.busy, problem: prompt.problem)

                Section {
                    Button(text("reauth_confirm")) { flow.submitReauth(secret: secret) }
                    Button(text("cancel")) { flow.cancelReauth() }
                }
                .disabled(prompt.busy)
            }
            .navigationTitle(text("reauth_title"))
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: prompt.needsPassword) { _ in secret = "" }
        }
    }
}
