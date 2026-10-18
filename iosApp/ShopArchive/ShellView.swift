import SwiftUI
import ClientCore

/// The app after unlock, as the phone layout of Compose's Shell: a tab bar, and over the current screen the connection banner and the branch choice.
/// Only the screens built so far have a tab: Today (Open day shows under it) and More.
/// Today's tab is there before the config has loaded too (Compose offers only More then), since a tab bar cannot select a tab it does not have: its screen says it is loading, or offers a retry.
struct ShellView: View {
    let state: AppStateUnlocked
    let flow: AppFlow
    let ws: Workspace
    @State private var destination: Destination
    @State private var config: any ConfigState
    @State private var branch: String?
    @State private var capabilities: Capabilities
    @State private var connection: ConnectionStatus

    init(state: AppStateUnlocked, flow: AppFlow, ws: Workspace) {
        self.state = state
        self.flow = flow
        self.ws = ws
        _destination = State(initialValue: ws.destination.value)
        _config = State(initialValue: ws.config.value)
        _branch = State(initialValue: ws.branch.value as String?)
        _capabilities = State(initialValue: ws.capabilities.value)
        _connection = State(initialValue: state.connection.value)
    }

    var body: some View {
        TabView(selection: Binding(get: { destination == .more ? Destination.more : Destination.today }, set: { ws.go(to: $0) })) {
            page { todayScreen }
                .tabItem { Label(text("nav_today"), systemImage: "calendar") }
                .tag(Destination.today)
            page { MoreView(state: state, flow: flow) }
                .tabItem { Label(text("nav_more"), systemImage: "ellipsis") }
                .tag(Destination.more)
        }
        .collect(ws.destination, into: $destination)
        .collect(ws.config, into: $config)
        .collect(ws.branch, into: $branch)
        .collect(ws.capabilities, into: $capabilities)
        .collect(state.connection, into: $connection)
    }

    private func page<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack {
            VStack(spacing: 0) {
                connectionBanner
                branchChoice
                content()
            }
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    @ViewBuilder
    private var connectionBanner: some View {
        switch connection {
        case .connected: EmptyView()
        case .connecting: Banner(text: text("status_connecting"), tone: .info).padding(.horizontal).padding(.vertical, 8)
        case .offline: Banner(text: text("offline_banner"), tone: .error).padding(.horizontal).padding(.vertical, 8)
        }
    }

    @ViewBuilder
    private var branchChoice: some View {
        let branches = ws.branches
        if branches.count > 1 {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(branches, id: \.key) { b in
                        BranchChoice(name: b.displayName, selected: b.key == branch) { ws.selectBranch(key: b.key) }
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 4)
        }
    }

    @ViewBuilder
    private var todayScreen: some View {
        switch onEnum(of: config) {
        case .loading:
            Page { Text(text("working")).foregroundColor(.secondary) }
        case .failed:
            Page {
                Banner(text: text("cfg_failed"), tone: .error)
                Button(text("retry")) { ws.retry() }
            }
        case .ready:
            if branch == nil {
                Page { Banner(text: text("no_branch"), tone: .info) }
            } else if destination == .openDay {
                OpenDayView(ws: ws, can: capabilities)
            } else {
                TodayView(ws: ws, can: capabilities)
            }
        }
    }
}

/// A branch to work in: filled when it is the one chosen.
private struct BranchChoice: View {
    let name: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(name)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .foregroundColor(selected ? .white : .primary)
                .background(selected ? Color.accentColor : Color(.secondarySystemBackground), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Who is signed in, and the way to Settings and to Lock now.
private struct MoreView: View {
    let state: AppStateUnlocked
    let flow: AppFlow

    var body: some View {
        Page {
            Text(text("more_signed_in", state.username)).foregroundColor(.secondary)
            Text(state.server).font(.caption).foregroundColor(.secondary)
            Button(text("nav_settings")) { flow.openSettings() }
                .buttonStyle(.bordered)
            Button(text("lock_now")) { flow.lockNow(problem: nil) }
                .buttonStyle(.borderedProminent)
        }
        .navigationTitle(text("nav_more"))
    }
}
