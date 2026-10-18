import SwiftUI
import UIKit.UIGestureRecognizerSubclass
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
            .background(ActivityReporter(flow: flow))
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

/// Any touch tells the flow the user is there (else the auto-lock fires under someone using the app). It watches the whole window,
/// sheets included, from UIKit: a SwiftUI drag gesture over a Form takes the taps from its buttons.
private struct ActivityReporter: UIViewRepresentable {
    let flow: AppFlow

    func makeUIView(context: Context) -> UIView { WindowHook(onTouch: { [flow] in flow.userActive() }) }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

private final class WindowHook: UIView {
    private let recognizer: TouchWatcher

    init(onTouch: @escaping () -> Void) {
        recognizer = TouchWatcher(onTouch: onTouch)
        super.init(frame: .zero)
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { nil }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        recognizer.view?.removeGestureRecognizer(recognizer)
        window?.addGestureRecognizer(recognizer)
    }
}

/// Sees each touch begin and then fails, so every other recognizer and control gets the touch as if it were not there.
private final class TouchWatcher: UIGestureRecognizer, UIGestureRecognizerDelegate {
    private let onTouch: () -> Void

    init(onTouch: @escaping () -> Void) {
        self.onTouch = onTouch
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        onTouch()
        state = .failed
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}
