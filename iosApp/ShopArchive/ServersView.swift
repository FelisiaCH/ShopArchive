import SwiftUI
import ClientCore

/// The busy row and the problem of the last attempt, as the last section of a form.
struct StatusSection: View {
    let busy: Bool
    let problem: (any Problem)?

    var body: some View {
        if busy || problem != nil {
            Section {
                if busy {
                    HStack {
                        ProgressView()
                        Text(text("working")).foregroundColor(.secondary).padding(.leading, 8)
                    }
                }
                if let problem {
                    Text(problemText(problem)).foregroundColor(Theme.moneyOut)
                }
            }
        }
    }
}

/// The home screen: the servers saved here, the others found on the local network, and adding one by its address.
struct ServersView: View {
    let state: AppStateServers
    let flow: AppFlow
    @State private var address = ""
    @State private var removingId: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if state.saved.isEmpty {
                        Text(text("servers_none")).foregroundColor(.secondary)
                    }
                    ForEach(state.saved, id: \.serverId) { row in
                        // Remove is a button in sight, as on Android and in Minecraft's list: a swipe alone is found by nobody who does not know it.
                        HStack(spacing: 12) {
                            Button { flow.openServer(serverId: row.serverId) } label: {
                                VStack(alignment: .leading) {
                                    Text(row.name).foregroundColor(.primary)
                                    Text(row.endpoint + " · " + text("server_users", row.userCount))
                                        .font(.caption).foregroundColor(.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                            Button(text("remove_row"), role: .destructive) { removingId = row.serverId }
                                .buttonStyle(.bordered)
                                .tint(Theme.moneyOut)
                        }
                        .disabled(state.busy)
                        .swipeActions(edge: .trailing) {
                            Button(text("remove_row"), role: .destructive) { removingId = row.serverId }
                                .tint(Theme.moneyOut)
                                .disabled(state.busy)
                        }
                    }
                }

                Section {
                    if state.searching {
                        Text(text("servers_searching")).foregroundColor(.secondary)
                    } else if state.found.isEmpty {
                        Text(text("servers_found_none")).foregroundColor(.secondary)
                    }
                    ForEach(state.found, id: \.serverId) { found in
                        Button(found.name + " · " + found.endpoint) { flow.openFound(found: found) }
                            .disabled(state.busy)
                    }
                } header: {
                    HStack {
                        Text(text("servers_found"))
                        Spacer()
                        Button(text("servers_refresh")) { flow.refreshFound() }
                            .disabled(state.searching || state.busy)
                    }
                    .textCase(nil)
                }

                Section {
                    TextField(text("servers_add_label"), text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(state.busy)
                    Button(text("servers_add")) { flow.addServer(address: address) }
                        .disabled(state.busy || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                StatusSection(busy: state.busy, problem: state.problem)
            }
            .navigationTitle(text("servers_title"))
            .confirmationDialog(
                text("remove_confirm_body"),
                isPresented: Binding(get: { removingId != nil }, set: { if !$0 { removingId = nil } }),
                titleVisibility: .visible
            ) {
                Button(text("remove_confirm"), role: .destructive) {
                    if let id = removingId { flow.removeSavedServer(serverId: id) }
                    removingId = nil
                }
            }
        }
    }
}
