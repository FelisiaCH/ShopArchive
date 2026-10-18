import SwiftUI
import ClientCore

/// How loud a banner or chip is; the same three as Compose's Tone.
enum Tone {
    case info, success, error

    var color: Color {
        switch self {
        case .info: return .blue
        case .success: return .green
        case .error: return .red
        }
    }
}

/// An inline message.
struct Banner: View {
    let text: String
    let tone: Tone

    var body: some View {
        Text(text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(tone.color.opacity(0.15), in: RoundedRectangle(cornerRadius: 8))
    }
}

/// A small status pill.
struct Chip: View {
    let text: String
    let tone: Tone

    var body: some View {
        Text(text)
            .font(.subheadline)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(tone.color.opacity(0.15), in: Capsule())
    }
}

/// A block that groups related content.
struct Card<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// A scrollable column for one screen inside the shell.
struct Page<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
    }
}

extension View {
    /// Keeps `binding` at the latest value of a Kotlin flow while this view is on screen.
    func collect<Flow: AsyncSequence>(_ flow: Flow, into binding: Binding<Flow.Element>) -> some View {
        task {
            do {
                for try await next in flow { binding.wrappedValue = next }
            } catch {}
        }
    }
}

/// `08:12` from a server timestamp like `2026-10-04T08:12:30+07:00`; the text itself if it is not one. As Compose's clockTime, which keeps the timestamp's own offset.
func clockTime(_ timestamp: String) -> String {
    let parts = timestamp.split(separator: "T")
    guard parts.count == 2, parts[1].count >= 5 else { return timestamp }
    let time = parts[1].prefix(5)
    return time.dropFirst(2).first == ":" ? String(time) : timestamp
}

/// An amount the server sent (`1500000`, `-120.50`) with the thousands separator of the device.
func groupThousands(_ amount: String) -> String {
    MoneyTextKt.groupThousands(amount: amount, grouping: MoneyText_iosKt.localeGroupingSeparator(), decimal: MoneyText_iosKt.localeDecimalSeparator())
}

/// `minor` units of a currency with `exponent` decimals, written for the screen: `1,500,000` for LAK, `1,200.50` for THB.
func displayAmount(minor: Int64, exponent: Int32) -> String {
    MoneyTextKt.displayAmount(minor: minor, exponent: exponent, grouping: MoneyText_iosKt.localeGroupingSeparator(), decimal: MoneyText_iosKt.localeDecimalSeparator())
}
