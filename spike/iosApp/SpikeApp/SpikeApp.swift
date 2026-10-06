import SwiftUI
import SpikeShared

@main
struct ShopArchiveSpikeApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.keyboard)
        }
    }
}

// Hosts the Compose Multiplatform UI from the Kotlin framework (app/src/iosMain/.../IosApp.kt).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosAppKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
