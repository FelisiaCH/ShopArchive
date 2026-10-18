import SwiftUI
import ClientCore

/// Shows the screen for the flow's current state. The screens themselves come next; until then each state shows only its title.
struct RootView: View {
    let flow: AppFlow
    @State private var state: AppState?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .task {
                for await next in flow.state {
                    state = next
                }
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active { flow.appResumed() }
            }
    }

    @ViewBuilder
    private var content: some View {
        if let state {
            switch onEnum(of: state) {
            case .starting: ProgressView()
            case .servers: title("servers_title")
            case .login: title("login_title")
            case .locked: title("locked_title")
            case .unlocked: title("unlocked_title")
            case .settings: title("nav_settings")
            case .certChanged: title("pinmm_title")
            case .protocolMismatch: title("proto_title")
            }
        } else {
            ProgressView()
        }
    }

    private func title(_ key: LocalizedStringKey) -> some View {
        Text(key).font(.title2).multilineTextAlignment(.center).padding()
    }
}
