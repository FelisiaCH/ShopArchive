import SwiftUI
import UIKit.UIGestureRecognizerSubclass
import ClientCore

/// Shows the screen for the flow's current state, and the PIN prompt over it when an important action asks for one.
/// The language chosen in Settings is set on `Language` before the screens are drawn again, each as a new view (`.id`), since a view whose inputs did not change would keep its old words.
struct RootView: View {
    let flow: AppFlow
    @State private var state: AppState?
    @State private var reauth: ReauthPrompt?
    @State private var language: String?
    @Environment(\.scenePhase) private var scenePhase

    init(flow: AppFlow) {
        self.flow = flow
        let chosen = flow.language.value as String?
        Language.choose(chosen)
        _language = State(initialValue: chosen)
    }

    var body: some View {
        content
            .id(language)
            .background(ActivityReporter(flow: flow))
            .task {
                for await next in flow.state {
                    state = next
                }
            }
            .task {
                for await next in flow.language {
                    Language.choose(next as String?)
                    language = next as String?
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
                        .id(language)
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
            case .unlocked(let s):
                if let ws = flow.workspace { ShellView(state: s, flow: flow, ws: ws).id(ObjectIdentifier(ws)) }
            case .settings(let s): SettingsView(state: s, flow: flow, language: language)
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
            Text(text("starting")).foregroundColor(.secondary).padding(.top, 8)
        }
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
