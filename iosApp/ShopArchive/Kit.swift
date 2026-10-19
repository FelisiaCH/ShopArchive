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

/// `2026-10-19 09:47` in this device's time zone from a UTC instant like `2026-10-19T02:47:05Z`; the text itself if it is not one.
func localTime(_ instant: String) -> String {
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    // Java's Instant.toString() adds a fraction when the instant has one (`...:05.123Z`).
    guard let date = ISO8601DateFormatter().date(from: instant) ?? fractional.date(from: instant) else { return instant }
    let out = DateFormatter()
    out.locale = Locale(identifier: "en_US_POSIX")
    out.timeZone = .current
    out.dateFormat = "yyyy-MM-dd HH:mm"
    return out.string(from: date)
}

/// An amount the server sent (`1500000`, `-120.50`) with the thousands separator of the device.
func groupThousands(_ amount: String) -> String {
    MoneyTextKt.groupThousands(amount: amount, grouping: MoneyText_iosKt.localeGroupingSeparator(), decimal: MoneyText_iosKt.localeDecimalSeparator())
}

/// `minor` units of a currency with `exponent` decimals, written for the screen: `1,500,000` for LAK, `1,200.50` for THB.
func displayAmount(minor: Int64, exponent: Int32) -> String {
    MoneyTextKt.displayAmount(minor: minor, exponent: exponent, grouping: MoneyText_iosKt.localeGroupingSeparator(), decimal: MoneyText_iosKt.localeDecimalSeparator())
}

/// `displayAmount` with a `+` in front of a positive amount.
func signedAmount(minor: Int64, exponent: Int32) -> String {
    (minor > 0 ? "+" : "") + displayAmount(minor: minor, exponent: exponent)
}

/// A pill that is on or off, for picking one of a few (a branch, a category, a currency, a suggestion).
struct Choice: View {
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

/// A day picked from a calendar. The flow keeps it as `yyyy-MM-dd`; the picker takes a Date, so the two are converted in the device's time zone and the Gregorian calendar,
/// and the picker is told the same calendar so a Thai or Lao device does not show another era. It always holds a date: none of the places that use it takes an empty one.
struct DateField: View {
    let label: String
    let value: String
    let onPick: (String) -> Void

    private static func format() -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }

    private var day: Binding<Date> {
        Binding(
            get: { Self.format().date(from: value) ?? Date() },
            set: { onPick(Self.format().string(from: $0)) }
        )
    }

    var body: some View {
        DatePicker(label, selection: day, displayedComponents: .date)
            .datePickerStyle(.compact)
            .environment(\.locale, Language.locale)
            .environment(\.calendar, Calendar(identifier: .gregorian))
    }
}

/// Children side by side that wrap onto the next line when one is full, as Compose's FlowRow.
struct FlowLayout: Layout {
    private let spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(proposal.width ?? .infinity, subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let frames = arrange(bounds.width, subviews).frames
        for (subview, frame) in zip(subviews, frames) {
            subview.place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY), proposal: ProposedViewSize(frame.size))
        }
    }

    /// A child wider than the row gets the row's width, so its text wraps instead of running off the screen.
    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> (size: CGSize, frames: [CGRect]) {
        var frames: [CGRect] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for subview in subviews {
            var size = subview.sizeThatFits(.unspecified)
            if size.width > width { size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil)) }
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            widest = max(widest, x - spacing)
        }
        return (CGSize(width: widest, height: y + rowHeight), frames)
    }
}

// Kotlin hands the Ints of its sets and maps over as plain NSNumber, not the KotlinInt the Swift type names:
// reading such a set or map through its Swift type casts each one and crashes. These read them as NSNumber.

/// The Ints of a Kotlin Set<Int>.
func ints(_ set: Set<KotlinInt>) -> Set<Int> {
    Set((set as NSSet).compactMap { ($0 as? NSNumber)?.intValue })
}

/// A Kotlin Map<Int, V> with Int keys.
func intKeyed<V>(_ map: [KotlinInt: V]) -> [Int: V] {
    var out: [Int: V] = [:]
    for (key, value) in map as NSDictionary {
        if let key = key as? NSNumber, let value = value as? V { out[key.intValue] = value }
    }
    return out
}
