import SwiftUI
import ClientCore

/// Shows the screen for the flow's current state, and the PIN prompt over it when an important action asks for one.
/// Unlocked and Settings show only their title until their screens are built.
struct RootView: View {
    let flow: AppFlow
    @State private var state: AppState?
    @State private var reauth: ReauthPrompt?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .reportingActivity(to: flow)
            .task {
                for await next in flow.state {
                    state = next
                }
            }
            .task {
                for await next in flow.reauth {
                    reauth = next
                }
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active { flow.appResumed() }
            }
            // Only the buttons end the prompt, as in the other apps; the flow sets it back to nil.
            .sheet(isPresented: Binding(get: { reauth != nil }, set: { _ in })) {
                if let reauth {
                    ReauthView(prompt: reauth, flow: flow)
                        .reportingActivity(to: flow)
                        .interactiveDismissDisabled()
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        if let state {
            switch onEnum(of: state) {
            case .starting: starting
            case .servers(let s): ServersView(state: s, flow: flow)
            case .login(let s): LoginView(state: s, flow: flow)
            case .locked(let s): LockedView(state: s, flow: flow)
            case .unlocked: title("unlocked_title")
            case .settings: title("nav_settings")
            case .certChanged(let s): CertChangedView(state: s, flow: flow)
            case .protocolMismatch(let s): ProtocolMismatchView(state: s)
            }
        } else {
            starting
        }
    }

    private var starting: some View {
        VStack {
            ProgressView()
            Text("starting").foregroundColor(.secondary).padding(.top, 8)
        }
    }

    private func title(_ key: LocalizedStringKey) -> some View {
        Text(key).font(.title2).multilineTextAlignment(.center).padding()
    }
}

private extension View {
    /// Any touch tells the flow the user is there (else the auto-lock fires under someone using the app). The gesture runs
    /// alongside the ones under it, so it takes no touch from the controls.
    func reportingActivity(to flow: AppFlow) -> some View {
        simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in flow.userActive() })
    }
}
