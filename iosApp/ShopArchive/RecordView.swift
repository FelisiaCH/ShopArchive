import SwiftUI
import PhotosUI
import _PhotosUI_SwiftUI
import ClientCore

/// One entry at a time: type, category, item and note, the payment lines, the slips for online payments, and Save. The slips come from the photo library or, where there is a camera, from a photo taken now; a photo can also be scanned for the QR code of a transfer.
struct RecordView: View {
    let ws: Workspace
    let can: Capabilities
    @State private var ui: RecordUi
    // What is typed is kept here as well: a field bound to the flow alone loses characters while the flow's answer is on its way back.
    @State private var item: String
    @State private var note: String
    @State private var amounts: [Int32: String]
    @State private var picked: PhotosPickerItem?
    @State private var camera: CameraUse?

    init(ws: Workspace, can: Capabilities) {
        self.ws = ws
        self.can = can
        let ui = ws.record.ui.value
        _ui = State(initialValue: ui)
        _item = State(initialValue: ui.draft.item)
        _note = State(initialValue: ui.draft.note)
        _amounts = State(initialValue: Dictionary(uniqueKeysWithValues: ui.draft.tenders.map { ($0.key, $0.amount) }))
    }

    private var record: RecordState { ws.record }
    private var draft: Draft { ui.draft }
    private var editable: Bool { !ui.frozen }

    private var saved: Bool {
        if case .saved = onEnum(of: ui.phase) { return true }
        return false
    }

    var body: some View {
        Page {
            if ui.block == .noDay {
                Banner(text: text(can.openDay ? "block_no_day" : "block_no_day_other"), tone: .info)
                if can.openDay {
                    Button(text("openday_btn")) { ws.go(to: .openDay) }
                        .buttonStyle(.bordered)
                }
            }
            Picker("", selection: Binding(get: { draft.type }, set: { record.setType(type: $0) })) {
                Text(text("type_income")).tag(EntryType.income)
                Text(text("type_expense")).tag(EntryType.expense)
            }
            .pickerStyle(.segmented)
            .disabled(!editable)

            if !ui.categories.isEmpty {
                Text(text("category_label")).font(.headline)
                FlowLayout {
                    if !ui.policy.requireCategory {
                        Choice(name: text("category_none"), selected: draft.category == nil) { record.setCategory(key: nil) }
                    }
                    ForEach(ui.categories, id: \.key) { c in
                        Choice(name: categoryName(c.name), selected: draft.category == c.key) { record.setCategory(key: c.key) }
                    }
                }
                .disabled(!editable)
            }

            TextField(text("item_label"), text: Binding(get: { item }, set: { item = $0; record.setItem(text: $0) }))
                .textFieldStyle(.roundedBorder)
                .disabled(!editable)
            if !ui.suggestions.isEmpty && editable {
                FlowLayout {
                    ForEach(ui.suggestions, id: \.self) { s in
                        Choice(name: s, selected: false) { item = s; record.setItem(text: s) }
                    }
                }
            }
            TextField(text("note_label"), text: Binding(get: { note }, set: { note = $0; record.setNote(text: $0) }), axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.roundedBorder)
                .disabled(!editable)

            Text(text("tenders_title")).font(.headline)
            ForEach(draft.tenders, id: \.key) { tender($0) }
            Button(text("tender_add")) { record.addTender() }
                .buttonStyle(.bordered)
                .disabled(!editable)

            if ui.online || !draft.slips.isEmpty || !draft.transfers.isEmpty {
                slips
            }

            phase
            if let issue = ui.issues.first, !saved {
                Text(issueText(issue)).font(.caption).foregroundColor(.secondary)
            }
            if ui.block == .offline {
                Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
            }
            Button(text("record_save")) { record.save() }
                .buttonStyle(.borderedProminent)
                .disabled(!ui.canSave)
        }
        .navigationTitle(text("record_title"))
        .collect(ws.record.ui, into: $ui)
        // A new draft (saved, or discarded): the fields are empty again.
        .onChange(of: draft.id) { _ in
            item = draft.item
            note = draft.note
            amounts = Dictionary(uniqueKeysWithValues: draft.tenders.map { ($0.key, $0.amount) })
        }
        // An amount read off a slip goes in its line; for any other line what is typed stands.
        .onChange(of: draft.tenders.map(\.amount)) { _ in
            for row in draft.tenders where ints(ui.amountFromSlip).contains(Int(row.key)) && amounts[row.key] != row.amount {
                amounts[row.key] = row.amount
            }
        }
        .onChange(of: picked) { photo in
            guard let photo else { return }
            picked = nil
            add(photo)
        }
        .fullScreenCover(item: $camera) { use in
            CameraPicker { image in
                camera = nil
                if let image { add(image, use) }
            }
            .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func tender(_ row: TenderRow) -> some View {
        let currency = ui.currencies.first { $0.code == row.currency }
        let value = amounts[row.key] ?? row.amount
        Card {
            if ui.currencies.count > 1 {
                FlowLayout {
                    ForEach(ui.currencies, id: \.code) { c in
                        Choice(name: c.code, selected: c.code == row.currency) { record.setCurrency(row: row.key, code: c.code) }
                    }
                }
                .disabled(!editable)
            }
            Picker("", selection: Binding(get: { row.method }, set: { record.setMethod(row: row.key, method: $0) })) {
                Text(text("method_cash")).tag(TenderMethod.cash)
                Text(text("method_online")).tag(TenderMethod.online)
            }
            .pickerStyle(.segmented)
            .disabled(!editable)
            TextField(text("amount_label", row.currency), text: Binding(
                get: { value },
                set: {
                    amounts[row.key] = $0
                    record.setAmount(row: row.key, text: $0)
                }
            ))
            .keyboardType(.decimalPad)
            .textFieldStyle(.roundedBorder)
            .disabled(!editable)
            if let currency, let minor = MoneyTextKt.parseTypedAmount(text: value, exponent: currency.exponent, allowZero: false) {
                Text(text("record_amount_shown", displayAmount(minor: minor.int64Value, exponent: currency.exponent) + " " + currency.code))
                    .font(.body.monospacedDigit())
                    .frame(maxWidth: .infinity, alignment: .trailing)
            } else if ints(ui.badRows).contains(Int(row.key)) {
                Text(text("issue_amount_invalid")).font(.caption).foregroundColor(.red)
            }
            if ints(ui.amountFromSlip).contains(Int(row.key)) {
                Text(text("amount_from_slip")).font(.caption).foregroundColor(.secondary)
            }
            if draft.tenders.count > 1 {
                Button(text("tender_remove")) { record.removeTender(row: row.key) }
                    .buttonStyle(.bordered)
                    .disabled(!editable)
            }
        }
    }

    @ViewBuilder
    private var slips: some View {
        Text(text("slips_title")).font(.headline)
        Text(text("slips_count", String(draft.slips.count), String(ui.policy.slipMaxCount))).font(.caption).foregroundColor(.secondary)
        FlowLayout {
            PhotosPicker(selection: $picked, matching: .images) {
                Text(text(cameraAvailable ? "slip_gallery" : "slip_file"))
            }
            .buttonStyle(.bordered)
            .disabled(!editable)
            if cameraAvailable {
                Button(text("slip_camera")) { camera = .photo }
                    .buttonStyle(.bordered)
                    .disabled(!editable)
                // Only a server with a bank plugin confirms a QR code.
                if ui.policy.slipCheck {
                    Button(text("slip_scan")) { camera = .scan }
                        .buttonStyle(.bordered)
                        .disabled(!editable)
                }
            }
        }
        if ui.compressing {
            Text(text("slip_preparing")).foregroundColor(.secondary)
        }
        if ui.scanning {
            Text(text("slip_scanning")).foregroundColor(.secondary)
        }
        ForEach(draft.transfers, id: \.key) { t in
            HStack {
                Text("QR · " + t.check.source).frame(maxWidth: .infinity, alignment: .leading)
                Button(text("slip_remove", "QR")) { record.removeTransfer(key: t.key) }
                    .buttonStyle(.bordered)
                    .disabled(!editable)
            }
            if let use = t.uses.first { usedBefore(use) }
            checkBanner(t.check)
        }
        // Numbered like the files the server keeps (slip-1.jpg ...): a photo picker only gives a name of its own.
        ForEach(Array(draft.slips.enumerated()), id: \.element.key) { i, slip in
            let label = text("detail_slip", String(i + 1))
            HStack {
                Text(label + " · \(Int(slip.bytes.size) / 1024 + 1) KB").frame(maxWidth: .infinity, alignment: .leading)
                Button(text("slip_remove", label)) { record.removeSlip(key: slip.key) }
                    .buttonStyle(.bordered)
                    .disabled(!editable)
            }
            if let use = slip.uses.first { usedBefore(use) }
            if slip.twiceHere { Banner(text: text("slip_attached_twice"), tone: .error) }
            if let check = slip.check { checkBanner(check) }
        }
        if let notice = ui.slipNotice {
            switch notice {
            case .tooMany: Banner(text: text("notice_too_many", String(ui.policy.slipMaxCount)), tone: .error)
            case .tooBig: Banner(text: text("notice_too_big", String(ui.policy.slipMaxSizeKb)), tone: .error)
            case .unreadable: Banner(text: text("notice_unreadable"), tone: .error)
            case .noQr: Banner(text: text("notice_no_qr"), tone: .error)
            case .alreadyAdded: Banner(text: text("slip_attached_twice"), tone: .error)
            case .notConfirmed: Banner(text: text("notice_not_confirmed"), tone: .error)
            }
        }
    }

    private func usedBefore(_ use: SlipUseDto) -> some View {
        let branch = ws.branches.first { $0.key == use.branch }?.displayName ?? use.branch
        return Banner(text: text("slip_used_before", use.date, branch, use.createdBy.name), tone: .error)
    }

    /// What the bank said about a slip: the transfer it knows, that it was paid to another account, or that it does not know the slip.
    @ViewBuilder
    private func checkBanner(_ check: SlipCheckDto) -> some View {
        if !check.known {
            Banner(text: text("slip_check_unknown", check.source), tone: .error)
        } else if check.toShop?.boolValue == false {
            Banner(text: text("slip_check_not_shop", check.to ?? check.toAccount ?? ""), tone: .error)
        } else {
            let details = [
                check.amount.map { groupThousands($0) + (check.currency.map { " " + $0 } ?? "") },
                check.time,
                check.to.map { text("slip_check_to", $0) },
            ].compactMap { $0 }
            Banner(text: text("slip_check_known", check.source, details.joined(separator: " · ")), tone: .success)
        }
    }

    @ViewBuilder
    private var phase: some View {
        switch onEnum(of: ui.phase) {
        case .saving:
            Banner(text: text("record_saving"), tone: .info)
        case .saved:
            Banner(text: text("record_saved"), tone: .success)
        case .unknown:
            Banner(text: text("record_unknown"), tone: .info)
            if let key = ui.pendingBranch {
                Text(text("record_unknown_branch", ws.branches.first { $0.key == key }?.displayName ?? key)).font(.caption)
            }
            HStack(spacing: 8) {
                Button(text("record_check_again")) { record.checkAgain() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!ui.connected)
                Button(text("record_discard")) { record.discardDraft() }
                    .buttonStyle(.bordered)
            }
        case .failed(let p):
            Banner(text: text("record_failed", failureText(p.failure)), tone: .error)
        case .editing:
            EmptyView()
        }
    }

    /// Reads the picked photo and hands it to the flow, off the main thread: a photo is megabytes and goes over byte by byte. A photo that cannot be read is dropped without a word, as in Compose.
    private func add(_ photo: PhotosPickerItem) {
        let record = record
        Task.detached {
            guard let data = try? await photo.loadTransferable(type: Data.self) else { return }
            let raw = KotlinByteArray(size: Int32(data.count))
            for (i, byte) in data.enumerated() { raw.set(index: Int32(i), value: Int8(bitPattern: byte)) }
            // Back on the main thread, as on Android: addSlip reads the draft and then changes it.
            await MainActor.run { record.addSlip(name: photo.itemIdentifier ?? "slip.jpg", raw: raw) }
        }
    }

    /// The same for a photo just taken: it is added as a slip, or read for its QR code.
    private func add(_ image: UIImage, _ use: CameraUse) {
        let record = record
        Task.detached {
            guard let raw = slipBytes(image) else { return }
            await MainActor.run {
                switch use {
                case .photo: record.addSlip(name: "photo.jpg", raw: raw)
                case .scan: record.scanQr(name: "photo.jpg", raw: raw)
                }
            }
        }
    }
}

/// What a photo taken on Record is for.
private enum CameraUse: Identifiable {
    case photo, scan

    var id: Self { self }
}

/// The name in the app's language, English if that is empty.
func categoryName(_ name: LocalizedName) -> String {
    let picked: String
    switch Language.locale.language.languageCode?.identifier {
    case "lo": picked = name.lo
    case "th": picked = name.th
    default: picked = name.en
    }
    return picked.trimmingCharacters(in: .whitespaces).isEmpty ? name.en : picked
}

func issueText(_ issue: RecordIssue) -> String {
    switch issue {
    case .amountMissing: return text("issue_amount_missing")
    case .amountInvalid: return text("issue_amount_invalid")
    case .slipNeeded: return text("issue_slip_needed")
    case .slipNotAllowed: return text("issue_slip_not_allowed")
    case .tooManySlips: return text("issue_too_many_slips")
    case .categoryNeeded: return text("issue_category_needed")
    }
}
