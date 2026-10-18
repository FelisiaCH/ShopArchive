import SwiftUI
import ClientCore

/// A user name and PIN; a user who has no PIN yet chooses one (typed twice).
struct LoginView: View {
    let state: AppStateLogin
    let flow: AppFlow
    @State private var username = ""
    // Secrets live in plain @State, never saved anywhere.
    @State private var pin = ""
    @State private var newPin = ""
    @State private var newPinRepeat = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading) {
                        Text(state.serverName.isEmpty ? state.server : state.serverName).font(.headline)
                        Text(state.server).font(.caption).foregroundColor(.secondary)
                    }
                }

                Section {
                    TextField(text("username_label"), text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if state.needsNewPin {
                        // An old server does not say how many digits: then 4 to 12, and the server refuses a wrong length in words.
                        SecureField(text("pin_new", state.pinLength.map { String($0.intValue) } ?? "4–12"), text: $newPin)
                            .keyboardType(.numberPad)
                        SecureField(text("pin_repeat"), text: $newPinRepeat)
                            .keyboardType(.numberPad)
                    } else {
                        SecureField(text("pin_field"), text: $pin)
                            .keyboardType(.numberPad)
                    }
                } footer: {
                    if state.needsNewPin { Text(text("login_set_pin")) }
                }
                .disabled(state.busy)

                StatusSection(busy: state.busy, problem: state.problem)

                Section {
                    Button(text("login_submit")) {
                        flow.login(
                            username: username, pin: pin,
                            newPin: state.needsNewPin ? newPin : nil,
                            newPinRepeat: state.needsNewPin ? newPinRepeat : nil
                        )
                    }
                    if state.adding {
                        Button(text("add_user_back")) { flow.cancelLogin() }
                    }
                    Button(text("servers_back")) { flow.showServers() }
                }
                .disabled(state.busy)
            }
            .navigationTitle(text(state.adding ? "add_user_title" : "login_title"))
            // What was typed as the new PIN is not the next one when the server asks for it differently.
            .onChange(of: state.needsNewPin) { _ in
                newPin = ""
                newPinRepeat = ""
            }
        }
    }
}
