import SwiftUI
import ClientCore

private enum AdminSection: CaseIterable {
    case staff, branches, categories

    var title: String {
        switch self {
        case .staff: return text("manage_staff")
        case .branches: return text("manage_branches")
        case .categories: return text("manage_categories")
        }
    }
}

/// Manage shop: the staff, branches and categories the server console used to be needed for. Each part shows only for who may change it.
/// It sits under More (see ReportsView): the title and the way back to More are here.
struct AdminView: View {
    let ws: Workspace
    @State private var ui: AdminUi
    @State private var picked = AdminSection.staff

    init(ws: Workspace) {
        self.ws = ws
        _ui = State(initialValue: ws.admin.ui.value)
    }

    private var sections: [AdminSection] {
        AdminSection.allCases.filter { section in
            switch section {
            case .staff: return ui.canManageUsers
            case .branches: return ui.canManageBranches
            case .categories: return ui.canManageCategories
            }
        }
    }

    var body: some View {
        let shown = sections
        let section = shown.contains(picked) ? picked : shown.first
        let off = ui.busy || ui.loading || ui.blocked
        Page {
            if let section {
                if shown.count > 1 {
                    Picker(text("nav_manage"), selection: Binding(get: { section }, set: { picked = $0; ws.admin.clearFailure() })) {
                        ForEach(shown, id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                if let failure = ui.failure {
                    Banner(text: failureText(failure), tone: .error)
                }
                if ui.busy || ui.loading {
                    Banner(text: text("working"), tone: .info)
                }
                if ui.blocked {
                    Text(text("needs_connection")).font(.caption).foregroundColor(.secondary)
                }
                switch section {
                case .staff: StaffSection(admin: ws.admin, ui: ui, off: off)
                case .branches: BranchesSection(admin: ws.admin, ui: ui, off: off)
                case .categories: CategoriesSection(admin: ws.admin, ui: ui, off: off)
                }
            }
        }
        .navigationTitle(text("nav_manage"))
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(text("nav_more")) { ws.go(to: .more) }
            }
        }
        .task { ws.admin.load() }
        .collect(ws.admin.ui, into: $ui)
    }
}

// ---- staff ----

private struct StaffSection: View {
    let admin: AdminState
    let ui: AdminUi
    let off: Bool
    @State private var adding = false
    // The id, not the name: a rename keeps the panel open.
    @State private var editing: String?
    @State private var confirmReset: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if !ui.loaded {
                if !ui.loading {
                    Button(text("retry")) { admin.load() }
                        .buttonStyle(.filledFull)
                }
            } else {
                content
            }
        }
        .onChange(of: ui.done) { _ in
            adding = false
            confirmReset = nil
        }
    }

    @ViewBuilder
    private var content: some View {
        if adding {
            AddStaffForm(admin: admin, ui: ui, off: off) { adding = false; admin.clearFailure() }
        } else {
            Button { adding = true; admin.clearFailure() } label: { Label(text("manage_staff_add"), systemImage: "plus") }
                .buttonStyle(.filledFull)
                .disabled(off)
        }
        ForEach(ui.users, id: \.id) { user in
            Card {
                Text(user.name).font(.headline)
                StaffChips(user: user)
                let detail = staffDetail(user)
                if !detail.isEmpty {
                    Text(verbatim: detail).font(.footnote).foregroundColor(.secondary)
                }
                if confirmReset == user.id {
                    resetAsk(user)
                } else if editing == user.id {
                    StaffPanel(admin: admin, ui: ui, user: user, off: off, onReset: { confirmReset = user.id }, onClose: { editing = nil; admin.clearFailure() })
                        .id(user.name)
                } else {
                    Button(text("detail_edit")) { editing = user.id; admin.clearFailure() }
                        .buttonStyle(.tonalFull)
                        .disabled(ui.busy)
                }
            }
        }
    }

    /// Asks before the reset: it signs the person out of every device.
    @ViewBuilder
    private func resetAsk(_ user: AdminUserDto) -> some View {
        Text(text("manage_reset_ask", user.name)).font(.headline)
        Banner(text: text("manage_reset_body", user.name), tone: .error)
        HStack(spacing: 8) {
            Button(text("manage_reset_yes"), role: .destructive) { admin.resetUser(name: user.name) }
                .buttonStyle(.filledFull)
                .disabled(off)
            Button(text("cancel")) { confirmReset = nil }
                .buttonStyle(.tonalFull)
                .disabled(ui.busy)
        }
    }

    /// `Role · Branch, Branch`, a branch by its name when the config has it, else its key.
    private func staffDetail(_ user: AdminUserDto) -> String {
        let names = user.branches.map { key in ui.branches.first(where: { $0.key == key })?.displayName ?? key }
        let parts = [roleText(user), names.joined(separator: ", ")].filter { !$0.isEmpty }
        return parts.joined(separator: " · ")
    }
}

private func roleText(_ user: AdminUserDto) -> String {
    user.role == AdminKt.ROLE_NONE ? text("manage_role_none") : user.role
}

/// True while the account waits after wrong tries; a time the app cannot read counts as locked, as the server said so.
private func isLocked(_ user: AdminUserDto) -> Bool {
    guard let until = user.lockedUntil else { return false }
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let date = ISO8601DateFormatter().date(from: until) ?? fractional.date(from: until) else { return true }
    return date > Date()
}

private struct StaffChips: View {
    let user: AdminUserDto

    var body: some View {
        let locked = isLocked(user)
        let noLogin = !user.hasPin && !user.hasPassword
        if user.op || !user.enabled || locked || noLogin {
            FlowLayout {
                if user.op { Chip(text: text("manage_admin"), tone: .info) }
                if !user.enabled { Chip(text: text("manage_off"), tone: .error) }
                if locked { Chip(text: text("locked_title"), tone: .error) }
                if noLogin { Chip(text: text("manage_no_pin"), tone: .info) }
            }
        }
    }
}

private struct AddStaffForm: View {
    let admin: AdminState
    let ui: AdminUi
    let off: Bool
    let onCancel: () -> Void
    @State private var name = ""
    @State private var role = AdminKt.ROLE_NONE
    @State private var branches: Set<String> = []

    var body: some View {
        Card {
            Text(text("manage_staff_add")).font(.headline)
            TextField(text("username_label"), text: $name)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .field()
                .disabled(off)
            Text(text("manage_role")).font(.caption).foregroundColor(.secondary)
            RoleChoices(ui: ui, role: role, off: off) { role = $0 }
            Text(text("manage_branches")).font(.caption).foregroundColor(.secondary)
            FlowLayout {
                ForEach(ui.branches.filter { !$0.archived }, id: \.key) { b in
                    Choice(name: b.displayName, selected: branches.contains(b.key)) { toggle(b.key) }
                }
            }
            Text(text("manage_staff_hint")).font(.caption).foregroundColor(.secondary)
            HStack(spacing: 8) {
                Button(text("manage_add")) { add() }
                    .buttonStyle(.filledFull)
                    .disabled(off || trimmed.isEmpty)
                Button(text("cancel"), action: onCancel)
                    .buttonStyle(.tonalFull)
                    .disabled(ui.busy)
            }
        }
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func toggle(_ key: String) {
        if branches.contains(key) { branches.remove(key) } else { branches.insert(key) }
    }

    /// The branches in the config's order, as the list shows them.
    private func add() {
        let chosen = ui.branches.map { $0.key }.filter { branches.contains($0) }
        admin.addUser(name: trimmed, role: role, branches: chosen)
    }
}

private struct RoleChoices: View {
    let ui: AdminUi
    let role: String
    let off: Bool
    let onPick: (String) -> Void

    var body: some View {
        FlowLayout {
            Choice(name: text("manage_role_none"), selected: role == AdminKt.ROLE_NONE) { if !off { onPick(AdminKt.ROLE_NONE) } }
            ForEach(ui.roles, id: \.name) { r in
                Choice(name: r.name, selected: role == r.name) { if !off { onPick(r.name) } }
            }
        }
    }
}

/// What one person can be changed to: a new name, role and branches take effect at once; the buttons below do the rest.
private struct StaffPanel: View {
    let admin: AdminState
    let ui: AdminUi
    let user: AdminUserDto
    let off: Bool
    let onReset: () -> Void
    let onClose: () -> Void
    @State private var name: String

    init(admin: AdminState, ui: AdminUi, user: AdminUserDto, off: Bool, onReset: @escaping () -> Void, onClose: @escaping () -> Void) {
        self.admin = admin
        self.ui = ui
        self.user = user
        self.off = off
        self.onReset = onReset
        self.onClose = onClose
        _name = State(initialValue: user.name)
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Divider()
            TextField(text("username_label"), text: $name)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .field()
                .disabled(off)
            Button(text("manage_rename")) { admin.renameUser(name: user.name, to: trimmed) }
                .buttonStyle(.tonalFull)
                .disabled(off || trimmed.isEmpty || trimmed == user.name)
            Text(text("manage_role")).font(.caption).foregroundColor(.secondary)
            RoleChoices(ui: ui, role: user.role, off: off) { admin.setUserRole(name: user.name, role: $0) }
            Text(text("manage_branches")).font(.caption).foregroundColor(.secondary)
            branchChoices
            actions
        }
    }

    /// An archived branch the person still has can be taken off, but not given.
    private var branchChoices: some View {
        FlowLayout {
            ForEach(ui.branches.filter { !$0.archived || user.branches.contains($0.key) }, id: \.key) { b in
                let has = user.branches.contains(b.key)
                Choice(name: b.displayName, selected: has) { toggle(b.key, has: has) }
            }
        }
    }

    private func toggle(_ key: String, has: Bool) {
        if off { return }
        if has { admin.removeUserBranch(name: user.name, branch: key) } else { admin.addUserBranch(name: user.name, branch: key) }
    }

    private var actions: some View {
        let backoff = user.disabledReason == "backoff"
        return FlowLayout {
            if user.enabled {
                Button(text("manage_disable")) { admin.setUserEnabled(name: user.name, enabled: false) }
                    .buttonStyle(.tonal)
                    .disabled(off)
            } else if !backoff {
                Button(text("manage_enable")) { admin.setUserEnabled(name: user.name, enabled: true) }
                    .buttonStyle(.tonal)
                    .disabled(off)
            }
            if isLocked(user) || backoff || user.failedLogins > 0 {
                Button(text("manage_unlock")) { admin.unlockUser(name: user.name) }
                    .buttonStyle(.tonal)
                    .disabled(off)
            }
            Button(text("manage_reset"), action: onReset)
                .buttonStyle(.tonal)
                .disabled(off)
            Button(text("back"), action: onClose)
                .buttonStyle(.tonal)
                .disabled(ui.busy)
        }
    }
}

// ---- branches ----

private struct BranchesSection: View {
    let admin: AdminState
    let ui: AdminUi
    let off: Bool
    @State private var adding = false
    @State private var editing: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if adding {
                Card {
                    NameForm(submitLabel: text("manage_branch_add"), initial: "", off: off, busy: ui.busy, submit: { admin.addBranch(displayName: $0) }) {
                        adding = false
                        admin.clearFailure()
                    }
                }
            } else {
                Button { adding = true; editing = nil; admin.clearFailure() } label: { Label(text("manage_branch_add"), systemImage: "plus") }
                    .buttonStyle(.filledFull)
                    .disabled(off)
            }
            ForEach(ui.branches, id: \.key) { b in
                Card {
                    Text(b.displayName).font(.headline)
                    if b.archived { Chip(text: text("manage_archived"), tone: .info) }
                    if editing == b.key {
                        NameForm(submitLabel: text("manage_rename"), initial: b.displayName, off: off, busy: ui.busy, submit: { admin.updateBranch(key: b.key, displayName: $0, archived: nil) }) {
                            editing = nil
                            admin.clearFailure()
                        }
                    } else {
                        HStack(spacing: 8) {
                            Button(text("manage_rename")) { editing = b.key; adding = false; admin.clearFailure() }
                                .buttonStyle(.tonalFull)
                                .disabled(off)
                            Button(archiveLabel(b.archived)) { admin.updateBranch(key: b.key, displayName: nil, archived: KotlinBoolean(value: !b.archived)) }
                                .buttonStyle(.tonalFull)
                                .disabled(off)
                        }
                    }
                }
            }
        }
        .onChange(of: ui.done) { _ in
            adding = false
            editing = nil
        }
    }
}

private func archiveLabel(_ archived: Bool) -> String {
    text(archived ? "manage_unarchive" : "manage_archive")
}

/// A form of one name: `submit` gets it trimmed.
private struct NameForm: View {
    let submitLabel: String
    let initial: String
    let off: Bool
    let busy: Bool
    let submit: (String) -> Void
    let onCancel: () -> Void
    @State private var name: String

    init(submitLabel: String, initial: String, off: Bool, busy: Bool, submit: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.submitLabel = submitLabel
        self.initial = initial
        self.off = off
        self.busy = busy
        self.submit = submit
        self.onCancel = onCancel
        _name = State(initialValue: initial)
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField(text("manage_branch_name"), text: $name)
                .field()
                .disabled(off)
            HStack(spacing: 8) {
                Button(submitLabel) { submit(trimmed) }
                    .buttonStyle(.filledFull)
                    .disabled(off || trimmed.isEmpty)
                Button(text("cancel"), action: onCancel)
                    .buttonStyle(.tonalFull)
                    .disabled(busy)
            }
        }
    }
}

// ---- categories ----

private struct CategoriesSection: View {
    let admin: AdminState
    let ui: AdminUi
    let off: Bool
    @State private var adding = false
    @State private var editing: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if adding {
                Card {
                    Text(text("manage_category_add")).font(.headline)
                    CategoryForm(initial: LocalizedName(lo: "", th: "", en: ""), initialAppliesTo: .both, submitLabel: text("manage_add"), off: off, busy: ui.busy, submit: { admin.addCategory(name: $0, appliesTo: $1) }) {
                        adding = false
                        admin.clearFailure()
                    }
                }
            } else {
                Button { adding = true; editing = nil; admin.clearFailure() } label: { Label(text("manage_category_add"), systemImage: "plus") }
                    .buttonStyle(.filledFull)
                    .disabled(off)
            }
            ForEach(ui.categories, id: \.key) { c in
                row(c)
            }
        }
        .onChange(of: ui.done) { _ in
            adding = false
            editing = nil
        }
    }

    private func row(_ c: CategoryDto) -> some View {
        Card {
            Text(categoryName(c.name)).font(.headline)
            FlowLayout {
                Chip(text: appliesText(c.appliesTo), tone: .success)
                if c.archived { Chip(text: text("manage_archived"), tone: .info) }
            }
            if editing == c.key {
                CategoryForm(initial: c.name, initialAppliesTo: c.appliesTo, submitLabel: text("record_save"), off: off, busy: ui.busy, submit: { admin.updateCategory(key: c.key, name: $0, appliesTo: $1, archived: nil) }) {
                    editing = nil
                    admin.clearFailure()
                }
            } else {
                HStack(spacing: 8) {
                    Button(text("detail_edit")) { editing = c.key; adding = false; admin.clearFailure() }
                        .buttonStyle(.tonalFull)
                        .disabled(off)
                    Button(archiveLabel(c.archived)) { admin.updateCategory(key: c.key, name: nil, appliesTo: nil, archived: KotlinBoolean(value: !c.archived)) }
                        .buttonStyle(.tonalFull)
                        .disabled(off)
                }
            }
        }
    }
}

private func appliesText(_ appliesTo: AppliesTo) -> String {
    switch appliesTo {
    case .income: return text("type_income")
    case .expense: return text("type_expense")
    case .both: return text("manage_both")
    }
}

/// The names in Lao, Thai and English and what the category is for. One name is enough: the server wants all three, so a language left empty gets the first name that was written.
private struct CategoryForm: View {
    let busy: Bool
    let off: Bool
    let submitLabel: String
    let submit: (LocalizedName, AppliesTo) -> Void
    let onCancel: () -> Void
    @State private var lo: String
    @State private var th: String
    @State private var en: String
    @State private var appliesTo: AppliesTo

    init(initial: LocalizedName, initialAppliesTo: AppliesTo, submitLabel: String, off: Bool, busy: Bool, submit: @escaping (LocalizedName, AppliesTo) -> Void, onCancel: @escaping () -> Void) {
        self.busy = busy
        self.off = off
        self.submitLabel = submitLabel
        self.submit = submit
        self.onCancel = onCancel
        _lo = State(initialValue: initial.lo)
        _th = State(initialValue: initial.th)
        _en = State(initialValue: initial.en)
        _appliesTo = State(initialValue: initialAppliesTo)
    }

    private func trimmed(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// The first name written, in the order Lao, Thai, English.
    private var first: String? {
        [lo, th, en].map { trimmed($0) }.first { !$0.isEmpty }
    }

    private func send(_ first: String) {
        let loName = trimmed(lo).isEmpty ? first : trimmed(lo)
        let thName = trimmed(th).isEmpty ? first : trimmed(th)
        let enName = trimmed(en).isEmpty ? first : trimmed(en)
        submit(LocalizedName(lo: loName, th: thName, en: enName), appliesTo)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField(text("manage_name_lo"), text: $lo).field().disabled(off)
            TextField(text("manage_name_th"), text: $th).field().disabled(off)
            TextField(text("manage_name_en"), text: $en).field().disabled(off)
            Text(text("manage_names_hint")).font(.caption).foregroundColor(.secondary)
            Text(text("manage_applies_to")).font(.caption).foregroundColor(.secondary)
            Picker(text("manage_applies_to"), selection: $appliesTo) {
                Text(appliesText(.income)).tag(AppliesTo.income)
                Text(appliesText(.expense)).tag(AppliesTo.expense)
                Text(appliesText(.both)).tag(AppliesTo.both)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            HStack(spacing: 8) {
                Button(submitLabel) { if let first { send(first) } }
                    .buttonStyle(.filledFull)
                    .disabled(off || first == nil)
                Button(text("cancel"), action: onCancel)
                    .buttonStyle(.tonalFull)
                    .disabled(busy)
            }
        }
    }
}
