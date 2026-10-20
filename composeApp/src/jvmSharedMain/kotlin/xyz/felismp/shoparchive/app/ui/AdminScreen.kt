package xyz.felismp.shoparchive.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import xyz.felismp.shoparchive.app.flow.AdminState
import xyz.felismp.shoparchive.app.flow.AdminUi
import xyz.felismp.shoparchive.app.flow.Workspace
import xyz.felismp.shoparchive.app.resources.*
import xyz.felismp.shoparchive.shared.AdminUserDto
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.ROLE_NONE
import java.time.OffsetDateTime

private enum class Section { STAFF, BRANCHES, CATEGORIES }

/** Manage shop: the staff, branches and categories the server console used to be needed for. Each part shows only for who may change it. */
@Composable
fun AdminScreen(ws: Workspace) {
    val admin = ws.admin
    val ui by admin.ui.collectAsState()
    var picked by remember { mutableStateOf(Section.STAFF) }
    LaunchedEffect(Unit) { admin.load() }
    val sections = listOfNotNull(
        Section.STAFF.takeIf { ui.canManageUsers }, Section.BRANCHES.takeIf { ui.canManageBranches }, Section.CATEGORIES.takeIf { ui.canManageCategories },
    )
    val section = if (picked in sections) picked else sections.firstOrNull()
    val labels = mapOf(
        Section.STAFF to stringResource(Res.string.manage_staff),
        Section.BRANCHES to stringResource(Res.string.manage_branches),
        Section.CATEGORIES to stringResource(Res.string.manage_categories),
    )
    ShopPage(stringResource(Res.string.nav_manage)) {
        if (section == null) return@ShopPage
        if (sections.size > 1) ShopSegmented(sections.map { it to labels.getValue(it) }, section, { picked = it; admin.clearFailure() })
        ui.failure?.let { ShopBanner(it.text(), Tone.Error) }
        if (ui.busy || ui.loading) ShopBanner(stringResource(Res.string.working), Tone.Info)
        if (ui.blocked) ShopText(stringResource(Res.string.needs_connection), TextRole.Caption, muted = true)
        val off = ui.busy || ui.loading || ui.blocked
        when (section) {
            Section.STAFF -> StaffSection(admin, ui, off)
            Section.BRANCHES -> BranchesSection(admin, ui, off)
            Section.CATEGORIES -> CategoriesSection(admin, ui, off)
        }
    }
}

// ---- staff ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffSection(admin: AdminState, ui: AdminUi, off: Boolean) {
    var adding by remember { mutableStateOf(false) }
    // The id, not the name: a rename keeps the panel open.
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf<String?>(null) }
    // The innermost open thing closes first; while a call is running Back does nothing, like the disabled buttons.
    OnBackPress(adding || editing != null || confirmReset != null) {
        if (!ui.busy) {
            when {
                confirmReset != null -> confirmReset = null
                adding -> adding = false
                else -> editing = null
            }
            admin.clearFailure()
        }
    }
    LaunchedEffect(ui.done) { adding = false; confirmReset = null }

    if (!ui.loaded) {
        if (!ui.loading) ShopButton(stringResource(Res.string.retry), admin::load, Modifier.fillWidthOnPhone())
        return
    }
    if (adding) {
        AddStaffForm(admin, ui, off) { adding = false; admin.clearFailure() }
    } else {
        ShopButton(stringResource(Res.string.manage_staff_add), { adding = true; admin.clearFailure() }, Modifier.fillWidthOnPhone(), enabled = !off, icon = ShopIcon.AddPayment)
    }
    ui.users.forEach { user ->
        ShopCard {
            ShopText(user.name, TextRole.Title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (user.op) ShopChip(stringResource(Res.string.manage_admin), Tone.Info)
                if (!user.enabled) ShopChip(stringResource(Res.string.manage_off), Tone.Error)
                if (user.isLocked()) ShopChip(stringResource(Res.string.locked_title), Tone.Error)
                if (!user.hasPin && !user.hasPassword) ShopChip(stringResource(Res.string.manage_no_pin), Tone.Info)
            }
            val branches = user.branches.joinToString(", ") { key -> ui.branches.firstOrNull { it.key == key }?.displayName ?: key }
            ShopText(listOf(user.roleText(), branches).filter { it.isNotEmpty() }.joinToString(" · "), muted = true)
            when {
                confirmReset == user.id -> {
                    ShopText(stringResource(Res.string.manage_reset_ask, user.name), TextRole.Title)
                    ShopBanner(stringResource(Res.string.manage_reset_body, user.name), Tone.Error)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShopButton(stringResource(Res.string.manage_reset_yes), { admin.resetUser(user.name) }, enabled = !off)
                        ShopButton(stringResource(Res.string.cancel), { confirmReset = null }, primary = false, enabled = !ui.busy)
                    }
                }
                editing == user.id -> StaffPanel(admin, ui, user, off, { confirmReset = user.id }) { editing = null; admin.clearFailure() }
                else -> ShopButton(stringResource(Res.string.detail_edit), { editing = user.id; admin.clearFailure() }, primary = false, enabled = !ui.busy)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddStaffForm(admin: AdminState, ui: AdminUi, off: Boolean, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ROLE_NONE) }
    var branches by remember { mutableStateOf(emptySet<String>()) }
    ShopCard {
        ShopText(stringResource(Res.string.manage_staff_add), TextRole.Title)
        ShopTextField(name, { name = it }, stringResource(Res.string.username_label), Modifier.fillMaxWidth(), enabled = !off)
        ShopText(stringResource(Res.string.manage_role), TextRole.Caption, muted = true)
        RoleChoices(ui, role, { role = it }, off)
        ShopText(stringResource(Res.string.manage_branches), TextRole.Caption, muted = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.branches.filter { !it.archived }.forEach { b ->
                ShopChoice(b.displayName, b.key in branches, { branches = if (b.key in branches) branches - b.key else branches + b.key })
            }
        }
        ShopText(stringResource(Res.string.manage_staff_hint), TextRole.Caption, muted = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShopButton(stringResource(Res.string.manage_add), { admin.addUser(name.trim(), role, ui.branches.map { it.key }.filter { it in branches }) }, enabled = !off && name.isNotBlank())
            ShopButton(stringResource(Res.string.cancel), onCancel, primary = false, enabled = !ui.busy)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleChoices(ui: AdminUi, role: String, onPick: (String) -> Unit, off: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopChoice(stringResource(Res.string.manage_role_none), role == ROLE_NONE, { if (!off) onPick(ROLE_NONE) })
        ui.roles.forEach { r -> ShopChoice(r.name, role == r.name, { if (!off) onPick(r.name) }) }
    }
}

/** What one person can be changed to: a new name, role and branches take effect at once; the buttons below do the rest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaffPanel(admin: AdminState, ui: AdminUi, user: AdminUserDto, off: Boolean, onReset: () -> Unit, onClose: () -> Unit) {
    var name by remember(user.name) { mutableStateOf(user.name) }
    ShopTextField(name, { name = it }, stringResource(Res.string.username_label), Modifier.fillMaxWidth(), enabled = !off)
    ShopButton(stringResource(Res.string.manage_rename), { admin.renameUser(user.name, name.trim()) }, primary = false, enabled = !off && name.isNotBlank() && name.trim() != user.name)
    ShopText(stringResource(Res.string.manage_role), TextRole.Caption, muted = true)
    RoleChoices(ui, user.role, { admin.setUserRole(user.name, it) }, off)
    ShopText(stringResource(Res.string.manage_branches), TextRole.Caption, muted = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // An archived branch the person still has can be taken off, but not given.
        ui.branches.filter { !it.archived || it.key in user.branches }.forEach { b ->
            val has = b.key in user.branches
            ShopChoice(b.displayName, has, { if (!off) { if (has) admin.removeUserBranch(user.name, b.key) else admin.addUserBranch(user.name, b.key) } })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (user.enabled) ShopButton(stringResource(Res.string.manage_disable), { admin.setUserEnabled(user.name, false) }, primary = false, enabled = !off)
        else if (user.disabledReason != "backoff") ShopButton(stringResource(Res.string.manage_enable), { admin.setUserEnabled(user.name, true) }, primary = false, enabled = !off)
        if (user.isLocked() || user.disabledReason == "backoff" || user.failedLogins > 0) ShopButton(stringResource(Res.string.manage_unlock), { admin.unlockUser(user.name) }, primary = false, enabled = !off)
        ShopButton(stringResource(Res.string.manage_reset), onReset, primary = false, enabled = !off)
        ShopButton(stringResource(Res.string.back), onClose, primary = false, enabled = !ui.busy)
    }
}

@Composable
private fun AdminUserDto.roleText(): String = if (role == ROLE_NONE) stringResource(Res.string.manage_role_none) else role

/** True while the account waits after wrong tries; a time the app cannot read counts as locked, as the server said so. */
private fun AdminUserDto.isLocked(): Boolean =
    lockedUntil?.let { runCatching { OffsetDateTime.parse(it).isAfter(OffsetDateTime.now()) }.getOrDefault(true) } ?: false

// ---- branches ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchesSection(admin: AdminState, ui: AdminUi, off: Boolean) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    OnBackPress(adding || editing != null) {
        if (!ui.busy) {
            if (adding) adding = false else editing = null
            admin.clearFailure()
        }
    }
    LaunchedEffect(ui.done) { adding = false; editing = null }

    if (adding) {
        NameForm(stringResource(Res.string.manage_branch_add), "", off, ui.busy, { admin.addBranch(it) }) { adding = false; admin.clearFailure() }
    } else {
        ShopButton(stringResource(Res.string.manage_branch_add), { adding = true; editing = null; admin.clearFailure() }, Modifier.fillWidthOnPhone(), enabled = !off, icon = ShopIcon.AddPayment)
    }
    ui.branches.forEach { b ->
        ShopCard {
            ShopText(b.displayName, TextRole.Title)
            if (b.archived) ShopChip(stringResource(Res.string.manage_archived), Tone.Info)
            if (editing == b.key) {
                NameForm(stringResource(Res.string.manage_rename), b.displayName, off, ui.busy, { admin.updateBranch(b.key, it, null) }) { editing = null; admin.clearFailure() }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShopButton(stringResource(Res.string.manage_rename), { editing = b.key; adding = false; admin.clearFailure() }, primary = false, enabled = !off)
                    ShopButton(archiveLabel(b.archived), { admin.updateBranch(b.key, null, !b.archived) }, primary = false, enabled = !off)
                }
            }
        }
    }
}

/** A form of one name: [submit] gets it trimmed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameForm(submitLabel: String, initial: String, off: Boolean, busy: Boolean, submit: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    ShopTextField(name, { name = it }, stringResource(Res.string.manage_branch_name), Modifier.fillMaxWidth(), enabled = !off)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopButton(submitLabel, { submit(name.trim()) }, enabled = !off && name.isNotBlank())
        ShopButton(stringResource(Res.string.cancel), onCancel, primary = false, enabled = !busy)
    }
}

@Composable
private fun archiveLabel(archived: Boolean) = stringResource(if (archived) Res.string.manage_unarchive else Res.string.manage_archive)

// ---- categories ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoriesSection(admin: AdminState, ui: AdminUi, off: Boolean) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    OnBackPress(adding || editing != null) {
        if (!ui.busy) {
            if (adding) adding = false else editing = null
            admin.clearFailure()
        }
    }
    LaunchedEffect(ui.done) { adding = false; editing = null }

    if (adding) {
        ShopCard {
            ShopText(stringResource(Res.string.manage_category_add), TextRole.Title)
            CategoryForm(LocalizedName("", "", ""), AppliesTo.BOTH, stringResource(Res.string.manage_add), off, ui.busy, { name, appliesTo -> admin.addCategory(name, appliesTo) }) {
                adding = false; admin.clearFailure()
            }
        }
    } else {
        ShopButton(stringResource(Res.string.manage_category_add), { adding = true; editing = null; admin.clearFailure() }, Modifier.fillWidthOnPhone(), enabled = !off, icon = ShopIcon.AddPayment)
    }
    ui.categories.forEach { c ->
        ShopCard {
            ShopText(c.name.pick(), TextRole.Title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopChip(appliesText(c.appliesTo), when (c.appliesTo) { AppliesTo.INCOME -> Tone.Success; AppliesTo.EXPENSE -> Tone.Error; AppliesTo.BOTH -> Tone.Info })
                if (c.archived) ShopChip(stringResource(Res.string.manage_archived), Tone.Info)
            }
            if (editing == c.key) {
                CategoryForm(c.name, c.appliesTo, stringResource(Res.string.record_save), off, ui.busy, { name, appliesTo -> admin.updateCategory(c.key, name, appliesTo, null) }) {
                    editing = null; admin.clearFailure()
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShopButton(stringResource(Res.string.detail_edit), { editing = c.key; adding = false; admin.clearFailure() }, primary = false, enabled = !off)
                    ShopButton(archiveLabel(c.archived), { admin.updateCategory(c.key, null, null, !c.archived) }, primary = false, enabled = !off)
                }
            }
        }
    }
}

@Composable
private fun appliesText(appliesTo: AppliesTo) = stringResource(
    when (appliesTo) {
        AppliesTo.INCOME -> Res.string.type_income
        AppliesTo.EXPENSE -> Res.string.type_expense
        AppliesTo.BOTH -> Res.string.manage_both
    },
)

/**
 * The names in Lao, Thai and English and what the category is for. One name is enough: the server wants all three, so a language left empty gets the first name
 * that was written.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryForm(
    initial: LocalizedName, initialAppliesTo: AppliesTo, submitLabel: String, off: Boolean, busy: Boolean,
    submit: (LocalizedName, AppliesTo) -> Unit, onCancel: () -> Unit,
) {
    var lo by remember { mutableStateOf(initial.lo) }
    var th by remember { mutableStateOf(initial.th) }
    var en by remember { mutableStateOf(initial.en) }
    var appliesTo by remember { mutableStateOf(initialAppliesTo) }
    val first = listOf(lo, th, en).map { it.trim() }.firstOrNull { it.isNotEmpty() }
    ShopTextField(lo, { lo = it }, stringResource(Res.string.manage_name_lo), Modifier.fillMaxWidth(), enabled = !off)
    ShopTextField(th, { th = it }, stringResource(Res.string.manage_name_th), Modifier.fillMaxWidth(), enabled = !off)
    ShopTextField(en, { en = it }, stringResource(Res.string.manage_name_en), Modifier.fillMaxWidth(), enabled = !off)
    ShopText(stringResource(Res.string.manage_names_hint), TextRole.Caption, muted = true)
    ShopText(stringResource(Res.string.manage_applies_to), TextRole.Caption, muted = true)
    ShopSegmented(
        listOf(AppliesTo.INCOME, AppliesTo.EXPENSE, AppliesTo.BOTH).map { it to appliesText(it) }, appliesTo, { appliesTo = it },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopButton(
            submitLabel,
            { first?.let { f -> submit(LocalizedName(lo.trim().ifEmpty { f }, th.trim().ifEmpty { f }, en.trim().ifEmpty { f }), appliesTo) } },
            enabled = !off && first != null,
        )
        ShopButton(stringResource(Res.string.cancel), onCancel, primary = false, enabled = !busy)
    }
}
