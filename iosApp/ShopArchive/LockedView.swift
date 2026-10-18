import SwiftUI
import ClientCore

/// Personal device: the one user. Shared device: the people who logged in here, then the PIN of the one picked.
struct LockedView: View {
    let state: AppStateLocked
    let flow: AppFlow
    @State private var secret = ""

    var body: some View {
        NavigationStack {
            Form {
                if let username = state.username {
                    Section {
                        Text(text("locked_body", username, state.server))
                    }
                    Section {
                        SecureField(text(state.needsPassword ? "password_enter" : "pin_field"), text: $secret)
                            .keyboardType(state.needsPassword ? .default : .numberPad)
                            .disabled(state.busy)
                        Button(text(state.needsPassword ? "unlock_password" : "unlock_pin")) { flow.unlock(secret: secret) }
                            .disabled(state.busy)
                    }
                } else {
                    Section {
                        ForEach(state.users, id: \.self) { name in
                            Button(name) { flow.selectUser(username: name) }
                                .disabled(state.busy)
                        }
                    } header: {
                        Text(text("lock_pick_user")).textCase(nil)
                    }
                }

                StatusSection(busy: state.busy, problem: state.problem)

                Section {
                    if state.username == nil || !state.shared {
                        // A personal device has no list of users to pick from: adding one starts here.
                        Button(text("lock_add_user")) { flow.addUser() }
                    } else {
                        Button(text("lock_other_user")) { flow.selectUser(username: nil) }
                    }
                    Button(text("servers_back")) { flow.showServers() }
                }
                .disabled(state.busy)
            }
            .navigationTitle(text("locked_title"))
            // A new field when the server asks for the password instead, or another user is picked: what was typed is not the next secret.
            .onChange(of: state.needsPassword) { _ in secret = "" }
            .onChange(of: state.username) { _ in secret = "" }
        }
    }
}
