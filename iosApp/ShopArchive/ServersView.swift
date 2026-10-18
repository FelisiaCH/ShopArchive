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
                        Text("working").foregroundColor(.secondary).padding(.leading, 8)
                    }
                }
                if let problem {
                    Text(problemText(problem)).foregroundColor(.red)
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

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if state.saved.isEmpty {
                        Text("servers_none").foregroundColor(.secondary)
                    }
                    ForEach(state.saved, id: \.serverId) { row in
                        Button { flow.openServer(serverId: row.serverId) } label: {
                            VStack(alignment: .leading) {
                                Text(row.name)
                                Text(row.endpoint + " · " + String.localizedStringWithFormat(text("server_users"), row.userCount))
                                    .font(.caption).foregroundColor(.secondary)
                            }
                        }
                        .disabled(state.busy)
                    }
                }

                Section {
                    if state.searching {
                        Text("servers_searching").foregroundColor(.secondary)
                    } else if state.found.isEmpty {
                        Text("servers_found_none").foregroundColor(.secondary)
                    }
                    ForEach(state.found, id: \.serverId) { found in
                        Button(found.name + " · " + found.endpoint) { flow.openFound(found: found) }
                            .disabled(state.busy)
                    }
                } header: {
                    HStack {
                        Text("servers_found")
                        Spacer()
                        Button("servers_refresh") { flow.refreshFound() }
                            .disabled(state.searching || state.busy)
                    }
                    .textCase(nil)
                }

                Section {
                    TextField(text("servers_add_label"), text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(state.busy)
                    Button("servers_add") { flow.addServer(address: address) }
                        .disabled(state.busy || address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                StatusSection(busy: state.busy, problem: state.problem)
            }
            .navigationTitle("servers_title")
        }
    }
}
