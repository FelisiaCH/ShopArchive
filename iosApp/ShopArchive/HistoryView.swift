import SwiftUI
import PhotosUI
import _PhotosUI_SwiftUI
import ClientCore

/// The entries of a date range with the filters, and one entry opened: its facts, slips and changes, and editing, moving and deleting it. The slips of an edit come from the photo library or, where there is a camera, from a photo taken now.
struct HistoryView: View {
    let ws: Workspace
    @State private var detail: EntryDetailState?

    init(ws: Workspace) {
        self.ws = ws
        _detail = State(initialValue: ws.history.detail.value)
    }

    var body: some View {
        Group {
            if let detail {
                EntryDetailView(ws: ws, state: detail)
            } else {
                HistoryList(ws: ws)
            }
        }
        .collect(ws.history.detail, into: $detail)
    }
}

func rangeText(_ problem: RangeProblem) -> String {
    switch problem {
    case .notADate: return text("range_not_a_date")
    case .backwards: return text("range_backwards")
    case .tooLong: return text("range_too_long")
    }
}

private func methodText(_ method: TenderMethod) -> String {
    text(method == .cash ? "method_cash" : "method_online")
}

/// The name of category `key` in the app's language; the key itself when the shop no longer lists it.
func categoryTitle(_ ws: Workspace, _ key: String?) -> String? {
    guard let key else { return nil }
    guard case .ready(let ready) = onEnum(of: ws.config.value) else { return key }
    return ready.config.categories.first { $0.key == key }.map { categoryName($0.name) } ?? key
}

private func branchTitle(_ ws: Workspace, _ key: String) -> String {
    ws.branches.first { $0.key == key }?.displayName ?? key
}

func isBlank(_ s: String) -> Bool {
    s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
}

/// The amounts of an entry, one line per payment, with the sign of its type.
private struct TenderLines: View {
    let entry: EntryDto

    var body: some View {
        let sign = entry.type == .income ? "+" : "-"
        ForEach(Array(entry.tenders.enumerated()), id: \.offset) { _, t in
            Text(sign + groupThousands(t.amount) + " " + t.currency + " · " + methodText(t.method))
                .font(.body.monospacedDigit())
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
}

// ---- the list ----

private struct HistoryList: View {
    let ws: Workspace
    @State private var ui: HistoryUi
    @State private var config: any ConfigState
    // What is typed is kept here as well: a field bound to the flow alone loses characters while the flow's answer is on its way back.
    @State private var from: String
    @State private var to: String

    init(ws: Workspace) {
        self.ws = ws
        let ui = ws.history.ui.value
        _ui = State(initialValue: ui)
        _config = State(initialValue: ws.config.value)
        _from = State(initialValue: ui.from)
        _to = State(initialValue: ui.to)
    }

    private var history: HistoryState { ws.history }

    var body: some View {
        Page {
            filters
            if let failure = ui.failure {
                Banner(text: text("history_failed") + " " + failureText(failure), tone: .error)
                Button(text("retry")) { history.reload() }
                    .buttonStyle(.bordered)
            }
            if ui.rangeProblem == nil {
                if let entries = ui.entries {
                    if entries.isEmpty && ui.failure == nil {
                        Text(text("history_empty")).foregroundColor(.secondary)
                    } else {
                        if ui.loading {
                            Text(text("working")).font(.caption).foregroundColor(.secondary)
                        }
                        ForEach(entries, id: \.id) { e in
                            Button { history.open(entry: e) } label: { EntryRow(ws: ws, entry: e) }
                                .buttonStyle(.plain)
                        }
                    }
                } else if ui.failure == nil {
                    Text(text("working")).foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle(text("nav_history"))
        .collect(ws.history.ui, into: $ui)
        .collect(ws.config, into: $config)
    }

    @ViewBuilder
    private var filters: some View {
        let ready: ConfigResponse? = { if case .ready(let r) = onEnum(of: config) { return r.config } else { return nil } }()
        let currencies = (ready?.currencies ?? []).map(\.code)
        let categories = (ready?.categories ?? []).filter { !$0.archived }
        Card {
            Text(text("filter_range")).font(.headline)
            TextField(text("filter_from"), text: Binding(get: { from }, set: { from = $0; history.setFrom(text: $0) }))
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField(text("filter_to"), text: Binding(get: { to }, set: { to = $0; history.setTo(text: $0) }))
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if let problem = ui.rangeProblem {
                Text(rangeText(problem)).font(.caption)
            }
            if ws.branches.count > 1 {
                Text(text("branch_label")).font(.caption).foregroundColor(.secondary)
                FlowLayout {
                    Choice(name: text("filter_all"), selected: ui.branch == nil) { history.setBranch(key: nil) }
                    ForEach(ws.branches, id: \.key) { b in
                        Choice(name: b.displayName, selected: ui.branch == b.key) { history.setBranch(key: b.key) }
                    }
                }
            }
            FlowLayout {
                Choice(name: text("filter_all"), selected: ui.type == nil) { history.setType(type: nil) }
                Choice(name: text("type_income"), selected: ui.type == .income) { history.setType(type: .income) }
                Choice(name: text("type_expense"), selected: ui.type == .expense) { history.setType(type: .expense) }
            }
            if currencies.count > 1 {
                FlowLayout {
                    Choice(name: text("filter_all"), selected: ui.currency == nil) { history.setCurrency(code: nil) }
                    ForEach(currencies, id: \.self) { c in
                        Choice(name: c, selected: ui.currency == c) { history.setCurrency(code: c) }
                    }
                }
            }
            if !categories.isEmpty {
                Text(text("category_label")).font(.caption).foregroundColor(.secondary)
                FlowLayout {
                    Choice(name: text("filter_all"), selected: ui.category == nil) { history.setCategory(key: nil) }
                    ForEach(categories, id: \.key) { c in
                        Choice(name: categoryName(c.name), selected: ui.category == c.key) { history.setCategory(key: c.key) }
                    }
                }
            }
            Choice(name: text("filter_deleted"), selected: ui.includeDeleted) { history.setIncludeDeleted(on: !ui.includeDeleted) }
        }
    }
}

private struct EntryRow: View {
    let ws: Workspace
    let entry: EntryDto

    var body: some View {
        Card {
            HStack(spacing: 8) {
                VStack(alignment: .leading) {
                    Text(isBlank(entry.item) ? (categoryTitle(ws, entry.category) ?? "—") : entry.item)
                    Text(entry.date + " " + clockTime(entry.createdAt) + " · " + entry.createdBy.name)
                        .font(.caption).foregroundColor(.secondary)
                    if ws.branches.count > 1 {
                        Text(branchTitle(ws, entry.branch)).font(.caption).foregroundColor(.secondary)
                    }
                    if !entry.slips.isEmpty {
                        Text(text("history_slips", String(entry.slips.count))).font(.caption).foregroundColor(.secondary)
                    }
                    if entry.deleted {
                        Chip(text: text("history_deleted"), tone: .error)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack { TenderLines(entry: entry) }
                    .frame(maxWidth: .infinity)
            }
        }
        .contentShape(Rectangle())
    }
}

// ---- one entry ----

private struct EntryDetailView: View {
    let ws: Workspace
    let state: EntryDetailState
    @State private var ui: DetailUi

    init(ws: Workspace, state: EntryDetailState) {
        self.ws = ws
        self.state = state
        _ui = State(initialValue: state.ui.value)
    }

    var body: some View {
        let e = ui.entry
        Page {
            Button(text("back")) { ws.history.closeDetail() }
                .buttonStyle(.bordered)
            if let edit = ui.edit {
                EditFormView(ws: ws, state: state, ui: ui, edit: edit)
            } else {
                EntryFacts(ws: ws, entry: e)
                Actions(state: state, ui: ui)
                Text(text("slips_title")).font(.headline)
                if e.slips.isEmpty {
                    Text(text("detail_no_slips")).foregroundColor(.secondary)
                }
                ForEach(intKeyed(ui.slips).sorted { $0.key < $1.key }, id: \.key) { n, load in
                    SlipImage(n: n, load: load)
                }
                if !e.transfers.isEmpty {
                    Text(text("detail_transfers")).font(.headline)
                    ForEach(e.transfers, id: \.code) { TransferLine(transfer: $0) }
                }
                Text(text("detail_history")).font(.headline)
                switch onEnum(of: ui.history) {
                case .loading:
                    Text(text("working")).foregroundColor(.secondary)
                case .failed(let h):
                    Banner(text: failureText(h.failure), tone: .error)
                case .ready(let h):
                    ForEach(Array(((h.value as? [HistoryItem]) ?? []).enumerated()), id: \.offset) { _, item in
                        HistoryLine(item: item)
                    }
                }
            }
        }
        .navigationTitle(isBlank(e.item) ? (categoryTitle(ws, e.category) ?? text("nav_history")) : e.item)
        .collect(state.ui, into: $ui)
    }
}

private struct EntryFacts: View {
    let ws: Workspace
    let entry: EntryDto

    var body: some View {
        Card {
            if entry.deleted {
                Chip(text: text("history_deleted"), tone: .error)
            }
            Text(text(entry.type == .income ? "type_income" : "type_expense")).font(.headline)
            Text(entry.date + " · " + branchTitle(ws, entry.branch) + (categoryTitle(ws, entry.category).map { " · " + $0 } ?? ""))
            if !isBlank(entry.note) {
                Text(entry.note).foregroundColor(.secondary)
            }
            Text(text("detail_created", clockTime(entry.createdAt), entry.createdBy.name)).font(.caption).foregroundColor(.secondary)
            if entry.updatedAt != entry.createdAt {
                Text(text("detail_updated", clockTime(entry.updatedAt))).font(.caption).foregroundColor(.secondary)
            }
            TenderLines(entry: entry)
        }
    }
}

/// The result of the last change as a banner: working, or not done with the server's words.
@ViewBuilder
private func actionBanner(_ action: any ActionPhase) -> some View {
    switch onEnum(of: action) {
    case .working: Banner(text: text("working"), tone: .info)
    case .failed(let a): Banner(text: text("detail_failed") + " " + failureText(a.failure), tone: .error)
    case .idle: EmptyView()
    }
}

private func isWorking(_ action: any ActionPhase) -> Bool {
    if case .working = onEnum(of: action) { return true }
    return false
}

private struct Actions: View {
    let state: EntryDetailState
    let ui: DetailUi
    // Each tap on Move starts the card again: the flow puts the entry's own date back, and the card's typed text must show that one.
    @State private var moveOpened = 0

    var body: some View {
        let busy = isWorking(ui.action)
        if !ui.entry.deleted && (ui.canEdit || ui.canDelete) {
            FlowLayout {
                if ui.canEdit {
                    Button(text("detail_edit")) { state.startEdit() }
                        .buttonStyle(.bordered)
                        .disabled(busy)
                    Button(text("detail_move")) {
                        state.openMove()
                        moveOpened += 1
                    }
                        .buttonStyle(.bordered)
                        .disabled(busy)
                }
                if ui.canDelete {
                    Button(text("detail_delete")) { state.askDelete() }
                        .buttonStyle(.bordered)
                        .disabled(busy)
                }
            }
            if !ui.canWrite {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
        }
        if ui.confirmDelete {
            Card {
                Text(text("detail_delete_ask")).font(.headline)
                Text(text("detail_delete_body")).foregroundColor(.secondary)
                HStack(spacing: 8) {
                    Button(text("detail_delete_yes")) { state.confirmDelete() }
                        .buttonStyle(.borderedProminent)
                        .disabled(busy || !ui.canWrite)
                    Button(text("cancel")) { state.cancelDelete() }
                        .buttonStyle(.bordered)
                }
            }
        }
        if let moveText = ui.moveText {
            MoveCard(state: state, ui: ui, first: moveText).id(moveOpened)
        }
        actionBanner(ui.action)
    }
}

private struct MoveCard: View {
    let state: EntryDetailState
    let ui: DetailUi
    // What is typed is kept here as well (see HistoryList); this card is there only while a move is being asked for.
    @State private var typed: String

    init(state: EntryDetailState, ui: DetailUi, first: String) {
        self.state = state
        self.ui = ui
        _typed = State(initialValue: first)
    }

    var body: some View {
        let busy = isWorking(ui.action)
        Card {
            Text(text("detail_move_to")).font(.headline)
            TextField(text("detail_move_date"), text: Binding(get: { typed }, set: { typed = $0; state.setMoveDate(text: $0) }))
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if !ui.moveValid {
                Text(text("range_not_a_date")).font(.caption)
            }
            HStack(spacing: 8) {
                Button(text("detail_move_yes")) { state.confirmMove() }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || !ui.moveValid || !ui.canWrite)
                Button(text("cancel")) { state.cancelMove() }
                    .buttonStyle(.bordered)
            }
        }
    }
}

/// What the bank confirmed when the entry was saved: amount, time, and who paid whom (names and accounts as the bank shows them).
private struct TransferLine: View {
    let transfer: TransferDto

    var body: some View {
        let t = transfer
        let payer = t.from.map { name in name + (t.fromAccount.map { " (\($0))" } ?? "") }
        let line = [
            "\(t.source) ✓ \(groupThousands(t.amount)) \(t.currency)",
            t.time,
            [payer, "\(t.to) (\(t.toAccount))"].compactMap { $0 }.joined(separator: " → "),
        ].joined(separator: " · ")
        if t.toShop?.boolValue == false {
            Banner(text: text("slip_check_not_shop", t.to) + "\n" + line, tone: .error)
        } else {
            Banner(text: line, tone: .success)
        }
    }
}

private struct SlipImage: View {
    let n: Int
    let load: any Load
    @State private var picture = Picture.working

    private enum Picture {
        case working, image(UIImage), unreadable
    }

    /// The bytes once the slip has arrived.
    private var bytes: KotlinByteArray? {
        if case .ready(let ready) = onEnum(of: load) { return ready.value as? KotlinByteArray }
        return nil
    }

    var body: some View {
        Card {
            Text(text("detail_slip", String(n))).font(.caption).foregroundColor(.secondary)
            switch onEnum(of: load) {
            case .loading:
                Text(text("working")).foregroundColor(.secondary)
            case .failed(let f):
                Banner(text: failureText(f.failure), tone: .error)
            case .ready:
                switch picture {
                case .working: Text(text("working")).foregroundColor(.secondary)
                case .unreadable: Text(text("notice_unreadable")).foregroundColor(.secondary)
                case .image(let image):
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: 420)
                        .accessibilityLabel(text("detail_slip", String(n)))
                }
            }
        }
        .task(id: bytes.map(ObjectIdentifier.init)) {
            guard let bytes else { return }
            picture = await decode(bytes)
        }
    }

    /// The bytes go over one by one, so off the main thread.
    private func decode(_ bytes: KotlinByteArray) async -> Picture {
        await Task.detached {
            var data = Data(count: Int(bytes.size))
            for i in 0..<data.count { data[i] = UInt8(bitPattern: bytes.get(index: Int32(i))) }
            return UIImage(data: data).map(Picture.image) ?? .unreadable
        }.value
    }
}

private struct HistoryLine: View {
    let item: HistoryItem

    var body: some View {
        Card {
            Text(verbatim: "\(item.action) · \(item.at.replacingOccurrences(of: "T", with: " ").prefix(16)) · \(item.by.name)")
                .font(.caption).foregroundColor(.secondary)
            ForEach(Array(item.changes.enumerated()), id: \.offset) { _, c in
                Text(verbatim: "\(c.field): \(c.from.isEmpty ? "—" : c.from) → \(c.to.isEmpty ? "—" : c.to)").font(.caption)
            }
        }
    }
}

// ---- edit ----

private struct EditFormView: View {
    let ws: Workspace
    let state: EntryDetailState
    let ui: DetailUi
    let edit: EditUi
    // What is typed is kept here as well (see HistoryList); this form is there only while the entry is being edited.
    @State private var item: String
    @State private var note: String
    @State private var amounts: [Int32: String]
    @State private var picked: PhotosPickerItem?
    @State private var camera = false

    init(ws: Workspace, state: EntryDetailState, ui: DetailUi, edit: EditUi) {
        self.ws = ws
        self.state = state
        self.ui = ui
        self.edit = edit
        _item = State(initialValue: edit.form.item)
        _note = State(initialValue: edit.form.note)
        _amounts = State(initialValue: Dictionary(uniqueKeysWithValues: edit.form.tenders.map { ($0.key, $0.amount) }))
    }

    private var f: EditForm { edit.form }
    private var busy: Bool { isWorking(ui.action) }

    var body: some View {
        Picker("", selection: Binding(get: { f.type }, set: { state.setType(type: $0) })) {
            Text(text("type_income")).tag(EntryType.income)
            Text(text("type_expense")).tag(EntryType.expense)
        }
        .pickerStyle(.segmented)
        .disabled(busy)

        if !edit.categories.isEmpty {
            Text(text("category_label")).font(.headline)
            FlowLayout {
                if !edit.policy.requireCategory {
                    Choice(name: text("category_none"), selected: f.category == nil) { state.setCategory(key: nil) }
                }
                ForEach(edit.categories, id: \.key) { c in
                    Choice(name: categoryName(c.name), selected: f.category == c.key) { state.setCategory(key: c.key) }
                }
            }
            .disabled(busy)
        }

        TextField(text("item_label"), text: Binding(get: { item }, set: { item = $0; state.setItem(text: $0) }))
            .textFieldStyle(.roundedBorder)
            .disabled(busy)
        TextField(text("note_label"), text: Binding(get: { note }, set: { note = $0; state.setNote(text: $0) }), axis: .vertical)
            .lineLimit(1...5)
            .textFieldStyle(.roundedBorder)
            .disabled(busy)

        Text(text("tenders_title")).font(.headline)
        ForEach(f.tenders, id: \.key) { tender($0) }
        Button(text("tender_add")) { state.addTender() }
            .buttonStyle(.bordered)
            .disabled(busy)

        if edit.online || !f.keep.isEmpty || !f.added.isEmpty {
            slips
        }

        if edit.stale {
            Banner(text: text("detail_stale"), tone: .error)
            EntryFacts(ws: ws, entry: ui.entry)
        }
        if let issue = edit.issues.first {
            Text(issueText(issue)).font(.caption).foregroundColor(.secondary)
        }
        actionBanner(ui.action)
        if !ui.canWrite {
            Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
        }
        HStack(spacing: 8) {
            Button(text("record_save")) { state.saveEdit() }
                .buttonStyle(.borderedProminent)
                .disabled(!edit.issues.isEmpty || edit.compressing || edit.stale || busy || !ui.canWrite)
            Button(text("cancel")) { state.cancelEdit() }
                .buttonStyle(.bordered)
                .disabled(busy)
        }
    }

    @ViewBuilder
    private func tender(_ row: TenderRow) -> some View {
        let currency = edit.currencies.first { $0.code == row.currency }
        let value = amounts[row.key] ?? row.amount
        Card {
            if edit.currencies.count > 1 {
                FlowLayout {
                    ForEach(edit.currencies, id: \.code) { c in
                        Choice(name: c.code, selected: c.code == row.currency) { state.setCurrency(row: row.key, code: c.code) }
                    }
                }
                .disabled(busy)
            }
            Picker("", selection: Binding(get: { row.method }, set: { state.setMethod(row: row.key, method: $0) })) {
                Text(text("method_cash")).tag(TenderMethod.cash)
                Text(text("method_online")).tag(TenderMethod.online)
            }
            .pickerStyle(.segmented)
            .disabled(busy)
            TextField(text("amount_label", row.currency), text: Binding(
                get: { value },
                set: {
                    amounts[row.key] = $0
                    state.setAmount(row: row.key, text: $0)
                }
            ))
            .keyboardType(.decimalPad)
            .textFieldStyle(.roundedBorder)
            .disabled(busy)
            if let currency, let minor = MoneyTextKt.parseTypedAmount(text: value, exponent: currency.exponent, allowZero: false) {
                Text(text("record_amount_shown", displayAmount(minor: minor.int64Value, exponent: currency.exponent) + " " + currency.code))
                    .font(.body.monospacedDigit())
                    .frame(maxWidth: .infinity, alignment: .trailing)
            } else if ints(edit.badRows).contains(Int(row.key)) {
                Text(text("issue_amount_invalid")).font(.caption)
            }
            if f.tenders.count > 1 {
                Button(text("tender_remove")) { state.removeTender(row: row.key) }
                    .buttonStyle(.bordered)
                    .disabled(busy)
            }
        }
    }

    @ViewBuilder
    private var slips: some View {
        Text(text("slips_title")).font(.headline)
        Text(text("slips_count", String(f.keep.count + f.added.count), String(edit.policy.slipMaxCount))).font(.caption).foregroundColor(.secondary)
        FlowLayout {
            PhotosPicker(selection: $picked, matching: .images) {
                Text(text(cameraAvailable ? "slip_gallery" : "slip_file"))
            }
            .buttonStyle(.bordered)
            .disabled(busy)
            .onChange(of: picked) { photo in
                guard let photo else { return }
                picked = nil
                add(photo)
            }
            if cameraAvailable {
                Button(text("slip_camera")) { camera = true }
                    .buttonStyle(.bordered)
                    .disabled(busy)
            }
        }
        .fullScreenCover(isPresented: $camera) {
            CameraPicker { image in
                camera = false
                if let image { add(image) }
            }
            .ignoresSafeArea()
        }
        if edit.compressing {
            Text(text("slip_preparing")).foregroundColor(.secondary)
        }
        ForEach(f.keep, id: \.file) { slip in
            HStack {
                Text(slip.file).frame(maxWidth: .infinity, alignment: .leading)
                Button(text("slip_remove", slip.file)) { state.removeStoredSlip(file: slip.file) }
                    .buttonStyle(.bordered)
                    .disabled(busy)
            }
        }
        // Numbered after the slips kept, as on Record.
        ForEach(Array(f.added.enumerated()), id: \.element.key) { i, slip in
            let label = text("detail_slip", String(f.keep.count + i + 1))
            HStack {
                Text(label + " · \(Int(slip.bytes.size) / 1024 + 1) KB").frame(maxWidth: .infinity, alignment: .leading)
                Button(text("slip_remove", label)) { state.removeNewSlip(key: slip.key) }
                    .buttonStyle(.bordered)
                    .disabled(busy)
            }
        }
        // Only Record scans a QR code, so the other notices are not shown here.
        switch edit.notice {
        case .tooMany: Banner(text: text("notice_too_many", String(edit.policy.slipMaxCount)), tone: .error)
        case .tooBig: Banner(text: text("notice_too_big", String(edit.policy.slipMaxSizeKb)), tone: .error)
        case .unreadable: Banner(text: text("notice_unreadable"), tone: .error)
        default: EmptyView()
        }
    }

    /// Reads the picked photo and hands it to the flow, off the main thread: a photo is megabytes and goes over byte by byte. A photo that cannot be read is dropped without a word, as in Compose.
    private func add(_ photo: PhotosPickerItem) {
        let state = state
        Task.detached {
            guard let data = try? await photo.loadTransferable(type: Data.self) else { return }
            let raw = KotlinByteArray(size: Int32(data.count))
            for (i, byte) in data.enumerated() { raw.set(index: Int32(i), value: Int8(bitPattern: byte)) }
            // Back on the main thread, as on Android: addSlip reads the form and then changes it.
            await MainActor.run { state.addSlip(name: photo.itemIdentifier ?? "slip.jpg", raw: raw) }
        }
    }

    /// The same for a photo just taken.
    private func add(_ image: UIImage) {
        let state = state
        Task.detached {
            guard let raw = slipBytes(image) else { return }
            await MainActor.run { state.addSlip(name: "photo.jpg", raw: raw) }
        }
    }
}
