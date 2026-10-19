import SwiftUI
import ClientCore

/// The language, the devices signed in with this account and removing the server from this device, with Lock now and Back (the phone version of Compose's SettingsScreen).
/// The update section is not here: iOS has no `updates`.
struct SettingsView: View {
    let state: AppStateSettings
    let flow: AppFlow
    let language: String?
    @State private var confirmRemove = false

    private static let languages: [(tag: String?, key: String)] = [
        (nil, "lang_system"), ("en", "lang_en"), ("lo", "lang_lo"), ("th", "lang_th"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(text("language_label"), selection: Binding(get: { language }, set: { flow.setLanguage(tag: $0) })) {
                        ForEach(Self.languages, id: \.key) { Text(text($0.key)).tag($0.tag) }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text(text("language_label")).textCase(nil)
                }

                Section {
                    if let devices = state.devices {
                        ForEach(devices, id: \.id) { d in
                            VStack(alignment: .leading) {
                                Text(text("device_info", d.label, d.platform, localTime(d.lastUsed)) + (d.current ? " · " + text("device_this") : ""))
                                if !d.current {
                                    Button(text("device_revoke")) { flow.revokeDevice(id: d.id) }
                                        .disabled(state.busy)
                                }
                            }
                        }
                    } else if state.problem == nil {
                        Text(text("working")).foregroundColor(.secondary)
                    }
                } header: {
                    Text(text("devices_title")).textCase(nil)
                }

                StatusSection(busy: state.busy, problem: state.problem)

                Section {
                    if confirmRemove {
                        Text(text("remove_confirm_body")).foregroundColor(.red)
                        Button(text("remove_confirm"), role: .destructive) { flow.removeServer() }
                        Button(text("cancel")) { confirmRemove = false }
                    } else {
                        Button(text("remove_server")) { confirmRemove = true }
                    }
                }
                .disabled(state.busy)

                Section {
                    Button(text("lock_now")) { flow.lockNow(problem: nil) }
                    Button(text("back")) { flow.closeSettings() }
                }
            }
            .navigationTitle(text("nav_settings"))
        }
    }

    /// `2026-10-19 09:47` in this device's time zone from a UTC instant like `2026-10-19T02:47:05Z`; the text itself if it is not one.
    private func localTime(_ instant: String) -> String {
        guard let date = ISO8601DateFormatter().date(from: instant) else { return instant }
        let out = DateFormatter()
        out.locale = Locale(identifier: "en_US_POSIX")
        out.timeZone = .current
        out.dateFormat = "yyyy-MM-dd HH:mm"
        return out.string(from: date)
    }
}
