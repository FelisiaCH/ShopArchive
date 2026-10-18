import SwiftUI
import ClientCore

@main
struct ShopArchiveApp: App {
    // One flow for as long as the app runs; it is the whole state of the app, the screens only show it.
    private let flow = IosAppFlowKt.createIosAppFlow()

    var body: some Scene {
        WindowGroup {
            RootView(flow: flow)
        }
    }
}
