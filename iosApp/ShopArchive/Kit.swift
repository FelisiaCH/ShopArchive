import SwiftUI
import ClientCore

/// The app's colors in one place, as in Compose's ShopColors: a near-black accent (near-white in dark mode) and the only two meaning colors, money in and money out.
/// Everything else is a system color, so light and dark follow the device.
enum Theme {
    static let accent = pair(light: 0x171717, dark: 0xFAFAFA)
    static let onAccent = pair(light: 0xFAFAFA, dark: 0x0A0A0A)
    static let moneyIn = pair(light: 0x047857, dark: 0x34D399)
    static let moneyOut = pair(light: 0xDC2626, dark: 0xF87171)

    /// Green for a positive amount (sign 1), red for a negative one (-1), the plain text color for zero.
    static func color(sign: Int) -> Color {
        if sign > 0 { return moneyIn }
        if sign < 0 { return moneyOut }
        return .primary
    }

    /// The color of money that came in (an income) or went out (an expense).
    static func color(of type: EntryType) -> Color {
        type == .income ? moneyIn : moneyOut
    }

    private static func pair(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            let rgb = traits.userInterfaceStyle == .dark ? dark : light
            let red = CGFloat((rgb >> 16) & 0xFF) / 255
            let green = CGFloat((rgb >> 8) & 0xFF) / 255
            let blue = CGFloat(rgb & 0xFF) / 255
            return UIColor(red: red, green: green, blue: blue, alpha: 1)
        })
    }
}

/// How loud a banner or chip is; the same three as Compose's Tone. Only success and error have a color of their own.
enum Tone {
    case info, success, error

    var color: Color {
        switch self {
        case .info: return Color(.secondaryLabel)
        case .success: return Theme.moneyIn
        case .error: return Theme.moneyOut
        }
    }

    var symbol: String {
        switch self {
        case .info: return "info.circle.fill"
        case .success: return "checkmark.circle.fill"
        case .error: return "exclamationmark.triangle.fill"
        }
    }
}

/// An inline message.
struct Banner: View {
    let text: String
    let tone: Tone

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: tone.symbol)
                .foregroundColor(tone.color)
                .accessibilityHidden(true)
            Text(text).frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.subheadline)
        .padding(12)
        .background(tone.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// A small status pill.
struct Chip: View {
    let text: String
    let tone: Tone
    var systemImage: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage).accessibilityHidden(true)
            }
            Text(text)
        }
        .font(.footnote.weight(.medium))
        .foregroundColor(tone.color)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(tone.color.opacity(0.14), in: Capsule())
    }
}

/// A small title above a block, in the secondary color, as the header of an inset grouped section.
struct SectionHeader: View {
    let title: String

    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .font(.footnote)
            .foregroundColor(.secondary)
            .padding(.horizontal, 16)
    }
}

private extension View {
    /// The look of an inset grouped section: a rounded block on the grouped background.
    func groupedBlock() -> some View {
        background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// A block that groups related content, with an optional title above it.
struct Card<Content: View>: View {
    let title: String?
    let content: Content

    init(title: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let title { SectionHeader(title) }
            VStack(alignment: .leading, spacing: 8) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .groupedBlock()
        }
    }
}

/// Rows in one block with a hairline between them, inset 16pt as in a grouped list. Each row pads itself with `rowInset()`, so a row that is a button is pressed over its whole width.
struct RowsCard<Item, ID: Hashable, Row: View>: View {
    let title: String?
    let items: [Item]
    let id: KeyPath<Item, ID>
    let row: (Item) -> Row

    init(title: String? = nil, _ items: [Item], id: KeyPath<Item, ID>, @ViewBuilder row: @escaping (Item) -> Row) {
        self.title = title
        self.items = items
        self.id = id
        self.row = row
    }

    var body: some View {
        let last = items.last.map { $0[keyPath: id] }
        VStack(alignment: .leading, spacing: 6) {
            if let title { SectionHeader(title) }
            VStack(alignment: .leading, spacing: 0) {
                ForEach(items, id: id) { item in
                    row(item)
                    if item[keyPath: id] != last {
                        Divider().padding(.leading, 16)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .groupedBlock()
        }
    }
}

extension View {
    /// The padding of a row in a `RowsCard`.
    func rowInset() -> some View {
        padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A field inside a card: a filled rounded box instead of the bordered one.
    func field() -> some View {
        padding(12)
            .background(Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// A scrollable column for one screen inside the shell. The grouped background is the shell's.
struct Page<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
        }
    }
}

/// An amount in a color, with digits of one width so a column of them lines up.
struct Amount: View {
    let shown: String
    var color: Color = .primary
    var font: Font = .body

    var body: some View {
        Text(verbatim: shown)
            .font(font.monospacedDigit())
            .foregroundColor(color)
    }
}

/// A row of a block: the label on the left, the value on the right.
struct AmountLine: View {
    let label: String
    let shown: String
    var color: Color = .primary

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label).frame(maxWidth: .infinity, alignment: .leading)
            Amount(shown: shown, color: color).multilineTextAlignment(.trailing)
        }
    }
}

/// A row that leads somewhere: an icon, the title and a chevron.
struct NavRow: View {
    let title: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 12) {
            Label(title, systemImage: systemImage)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundColor(Color(.tertiaryLabel))
                .accessibilityHidden(true)
        }
        .rowInset()
        .contentShape(Rectangle())
    }
}

/// Filled (the main action of a screen) or tonal (any other), 44pt at least, rounded like the buttons of the system. A destructive role turns it red.
struct ActionButtonStyle: ButtonStyle {
    let filled: Bool
    let wide: Bool
    @Environment(\.isEnabled) private var enabled

    init(filled: Bool, wide: Bool) {
        self.filled = filled
        self.wide = wide
    }

    func makeBody(configuration: Configuration) -> some View {
        let destructive = configuration.role == .destructive
        configuration.label
            .fontWeight(.medium)
            .multilineTextAlignment(.center)
            .foregroundColor(foreground(destructive: destructive))
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: wide ? .infinity : nil, minHeight: 44)
            .background(background(destructive: destructive), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .opacity(opacity(pressed: configuration.isPressed))
            .contentShape(Rectangle())
    }

    private func foreground(destructive: Bool) -> Color {
        if filled { return destructive ? .white : Theme.onAccent }
        return destructive ? Theme.moneyOut : .primary
    }

    private func background(destructive: Bool) -> Color {
        if filled { return destructive ? Theme.moneyOut : Theme.accent }
        return destructive ? Theme.moneyOut.opacity(0.12) : Color(.secondarySystemFill)
    }

    private func opacity(pressed: Bool) -> Double {
        if !enabled { return 0.4 }
        return pressed ? 0.7 : 1
    }
}

extension ButtonStyle where Self == ActionButtonStyle {
    static var filled: ActionButtonStyle { ActionButtonStyle(filled: true, wide: false) }
    static var filledFull: ActionButtonStyle { ActionButtonStyle(filled: true, wide: true) }
    static var tonal: ActionButtonStyle { ActionButtonStyle(filled: false, wide: false) }
    static var tonalFull: ActionButtonStyle { ActionButtonStyle(filled: false, wide: true) }
}

/// A button that is a whole row: it is shaded while pressed.
struct RowButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color(.systemFill) : Color.clear)
    }
}

extension ButtonStyle where Self == RowButtonStyle {
    static var row: RowButtonStyle { RowButtonStyle() }
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

/// 1, 0 or -1 for an amount the server sent: positive, zero (`0`, `0.00`) or negative.
func amountSign(_ amount: String) -> Int {
    if amount.trimmingCharacters(in: CharacterSet(charactersIn: "-+0.,")).isEmpty { return 0 }
    return amount.hasPrefix("-") ? -1 : 1
}

/// `groupThousands` with a `+` in front of a positive amount.
func signedText(_ amount: String) -> String {
    let grouped = groupThousands(amount)
    return amountSign(amount) > 0 ? "+\(grouped)" : grouped
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
                .font(.subheadline)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .foregroundColor(selected ? Theme.onAccent : .primary)
                .background(selected ? Theme.accent : Color(.tertiarySystemFill), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// The picture in a slip's bytes, off the main thread (the bytes go over one by one); `nil` if they are not a picture. With `side` it is cut down to fit a square of that many points.
func decodeSlip(_ bytes: KotlinByteArray, side: CGFloat? = nil) async -> UIImage? {
    await Task.detached { () -> UIImage? in
        var data = Data(count: Int(bytes.size))
        for i in 0..<data.count { data[i] = UInt8(bitPattern: bytes.get(index: Int32(i))) }
        guard let image = UIImage(data: data) else { return nil }
        guard let side else { return image }
        return image.preparingThumbnail(of: CGSize(width: side, height: side)) ?? image
    }.value
}

/// A slip's picture as a small page, gray until it has been read.
struct SlipThumbnail: View {
    let bytes: KotlinByteArray
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(.tertiarySystemFill)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "doc.text").foregroundColor(.secondary)
            }
        }
        .frame(width: 56, height: 72)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(Color(.separator), lineWidth: 0.5))
        .task(id: ObjectIdentifier(bytes)) { image = await decodeSlip(bytes, side: 240) }
        .accessibilityHidden(true)
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
