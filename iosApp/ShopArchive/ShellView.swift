import SwiftUI
import ClientCore

/// The app after unlock, as the phone layout of Compose's Shell: a tab bar, and over the current screen the connection banner and the branch choice.
/// Only the screens built so far have a tab: Today (Open day shows under it), Record and More.
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
        TabView(selection: Binding(get: { selected }, set: { ws.go(to: $0) })) {
            page {
                screen {
                    if destination == .openDay {
                        OpenDayView(ws: ws, can: capabilities)
                    } else {
                        TodayView(ws: ws, can: capabilities)
                    }
                }
            }
                .tabItem { Label(text("nav_today"), systemImage: "calendar") }
                .tag(Destination.today)
            if showsRecord {
                page { screen { RecordView(ws: ws, can: capabilities) } }
                    .tabItem { Label(text("nav_record"), systemImage: "square.and.pencil") }
                    .tag(Destination.record)
            }
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
                        Choice(name: b.displayName, selected: b.key == branch) { ws.selectBranch(key: b.key) }
                    }
                }
                .padding(.horizontal)
            }
            .padding(.vertical, 4)
        }
    }

    /// The tab with the selection it shows: Open day is under Today.
    private var selected: Destination {
        switch destination {
        case .more, .record: return destination
        default: return .today
        }
    }

    /// Record sits between Today and More, as in Compose's phone navigation: only once the config has loaded, and only for who may record.
    private var showsRecord: Bool {
        if case .ready = onEnum(of: config) { return capabilities.allows(destination: .record) }
        return false
    }

    /// `ready` once the config is loaded and a branch chosen; before that the screen says what is missing.
    @ViewBuilder
    private func screen<Ready: View>(@ViewBuilder _ ready: () -> Ready) -> some View {
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
            } else {
                ready()
            }
        }
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
