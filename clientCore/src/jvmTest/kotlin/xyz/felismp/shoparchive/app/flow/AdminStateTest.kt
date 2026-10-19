package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.AdminRoleDto
import xyz.felismp.shoparchive.shared.AdminUserDto
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.ROLE_NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AdminStateTest {
    private val noy = AdminUserDto("id-noy", "noy", enabled = true, branches = listOf("main"), hasPin = true)
    private val kham = AdminUserDto("id-kham", "kham", enabled = true, hasPin = true)

    private class Setup(val h: Harness, val admin: AdminState) {
        var configReads = 0
    }

    private fun TestScope.setup(config: ConfigState = ConfigState.Ready(testConfig())): Setup {
        val h = Harness(backgroundScope, config)
        h.api.adminUserList = listOf(kham, noy)
        h.api.adminRoleList = listOf(AdminRoleDto("clerk", 2))
        lateinit var setup: Setup
        val admin = AdminState(h.env) { setup.configReads++ }
        setup = Setup(h, admin)
        return setup
    }

    @Test fun loadingReadsTheUsersAndRolesInOneAskAndShowsTheShopsListsFromTheConfig() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        assertFalse(s.admin.ui.value.loaded)
        s.admin.load()

        val ui = s.admin.ui.value
        assertEquals(listOf(kham, noy), ui.users)
        assertEquals(listOf(AdminRoleDto("clerk", 2)), ui.roles)
        assertTrue(ui.loaded && !ui.loading && !ui.busy)
        assertEquals(listOf("main"), ui.branches.map { it.key })
        assertEquals(listOf("sales", "stock", "misc", "old"), ui.categories.map { it.key })
        assertTrue(ui.canManageUsers && ui.canManageBranches && ui.canManageCategories)
        assertEquals(listOf("adminUsers", "adminRoles"), s.h.api.adminCalls)
    }

    @Test fun aFailedLoadSaysWhyAndKeepsWhatWasThere() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.admin.load()
        s.h.api.adminError = ClientError.Api(403, ErrorCode.FORBIDDEN, "no", reason = ErrorReasons.PERMISSION_MISSING)
        s.admin.load()

        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), s.admin.ui.value.failure)
        assertEquals(listOf(kham, noy), s.admin.ui.value.users)
        assertFalse(s.admin.ui.value.loading)
        s.admin.clearFailure()
        assertNull(s.admin.ui.value.failure)
    }

    @Test fun nothingIsAskedOfTheServerByAPersonWhoMayDoNone() = runTest(UnconfinedTestDispatcher()) {
        val s = setup(ConfigState.Ready(testConfig(permissions = listOf(PermissionNodes.ENTRY_CREATE), admin = false)))
        s.admin.load()
        s.admin.addUser("new", ROLE_NONE, emptyList())
        s.admin.resetUser("noy")
        s.admin.addBranch("kiosk", "Kiosk")
        s.admin.addCategory("misc", LocalizedName("a", "b", "c"), AppliesTo.BOTH)

        assertEquals(emptyList(), s.h.api.adminCalls)
        assertFalse(s.admin.ui.value.canManageUsers || s.admin.ui.value.canManageBranches || s.admin.ui.value.canManageCategories)
        assertEquals(0, s.configReads)
    }

    @Test fun aBranchManagerWhoIsNoOpManagesBranchesButNotUsers() = runTest(UnconfinedTestDispatcher()) {
        val s = setup(ConfigState.Ready(testConfig(permissions = listOf(PermissionNodes.BRANCHES_MANAGE), admin = false)))
        s.admin.load()
        s.admin.setUserEnabled("noy", false)
        s.admin.addCategory("misc", LocalizedName("a", "b", "c"), AppliesTo.BOTH)
        s.admin.addBranch("kiosk", "Kiosk")

        assertEquals(listOf("addBranch kiosk Kiosk"), s.h.api.adminCalls)
        assertTrue(s.admin.ui.value.canManageBranches && !s.admin.ui.value.canManageUsers && !s.admin.ui.value.canManageCategories)
    }

    @Test fun eachUserActionCallsTheServerAndPutsTheAnswerInTheListByName() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.admin.load()

        s.admin.addUser("mali", "clerk", listOf("main"))
        assertEquals(listOf("kham", "mali", "noy"), s.admin.ui.value.users.map { it.name })
        s.admin.renameUser("noy", "zed")
        assertEquals(listOf("kham", "mali", "zed"), s.admin.ui.value.users.map { it.name }, "same account, new name, sorted again")
        s.admin.setUserRole("zed", "clerk")
        s.admin.addUserBranch("zed", "market")
        s.admin.removeUserBranch("zed", "main")
        s.admin.setUserEnabled("zed", false)
        val zed = s.admin.ui.value.users.first { it.name == "zed" }
        assertEquals(AdminUserDto("id-noy", "zed", enabled = false, disabledReason = "admin", role = "clerk", branches = listOf("market"), hasPin = true), zed)
        s.admin.unlockUser("zed")
        assertTrue(s.admin.ui.value.users.first { it.name == "zed" }.enabled)
        s.admin.resetUser("kham")
        assertFalse(s.admin.ui.value.users.first { it.name == "kham" }.hasPin)

        assertEquals(
            listOf(
                "adminUsers", "adminRoles", "addUser mali clerk [main]", "renameUser noy zed", "setUserRole zed clerk", "addUserBranch zed market",
                "removeUserBranch zed main", "setUserEnabled zed false", "unlockUser zed", "resetUser kham",
            ),
            s.h.api.adminCalls,
        )
        assertEquals(8, s.admin.ui.value.done, "one for each action that worked")
        assertEquals(0, s.configReads, "a user change does not touch the shop's lists")
    }

    @Test fun aRefusalIsShownWithItsReasonAndTheListIsNotChanged() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.admin.load()
        s.h.api.adminError = ClientError.Api(409, ErrorCode.CONFLICT, "exists", reason = ErrorReasons.USER_EXISTS)
        s.admin.addUser("noy", ROLE_NONE, emptyList())

        assertEquals(Failure.Refused(ErrorCode.CONFLICT, ErrorReasons.USER_EXISTS), s.admin.ui.value.failure)
        assertEquals(listOf(kham, noy), s.admin.ui.value.users)
        assertFalse(s.admin.ui.value.busy)
        assertEquals(0, s.admin.ui.value.done)

        s.h.api.adminError = null
        s.admin.addUser("mali", ROLE_NONE, emptyList())
        assertNull(s.admin.ui.value.failure, "the next action clears it")
    }

    @Test fun aDismissedPinPromptIsAFailureLikeAnyOther() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.h.api.adminError = ClientError.ReauthCancelled()
        s.admin.resetUser("noy")

        assertEquals(Failure.Client(Problem.ReauthCancelled), s.admin.ui.value.failure)
        assertFalse(s.admin.ui.value.busy)
    }

    @Test fun oneActionAtATimeAndNoneWhileOffline() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.admin.load()
        val gate = CompletableDeferred<Unit>()
        s.h.api.adminGate = gate
        s.admin.resetUser("noy")
        assertTrue(s.admin.ui.value.busy)
        s.admin.resetUser("kham")
        gate.complete(Unit)
        assertEquals(listOf("adminUsers", "adminRoles", "resetUser noy"), s.h.api.adminCalls)

        s.h.canWrite.value = false
        assertTrue(s.admin.ui.value.blocked)
        s.admin.resetUser("kham")
        assertEquals(3, s.h.api.adminCalls.size)
    }

    @Test fun aBranchOrCategoryChangeReadsTheConfigAgainAfterTheServerSaidYes() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.admin.addBranch("kiosk", "Kiosk")
        s.admin.updateBranch("kiosk", "Kiosk 2", true)
        s.admin.updateBranch("main", null, false)
        s.admin.addCategory("tips", LocalizedName("ທິບ", "ทิป", "Tips"), AppliesTo.INCOME)
        s.admin.updateCategory("tips", null, AppliesTo.BOTH, true)

        assertEquals(
            listOf(
                "addBranch kiosk Kiosk", "updateBranch kiosk Kiosk 2 true", "updateBranch main null false",
                "addCategory tips Tips INCOME", "updateCategory tips null BOTH true",
            ),
            s.h.api.adminCalls,
        )
        assertEquals(5, s.configReads)
        assertEquals(5, s.admin.ui.value.done)

        s.h.api.adminError = ClientError.Api(409, ErrorCode.CONFLICT, "exists", reason = ErrorReasons.KEY_EXISTS)
        s.admin.addBranch("kiosk", "Again")
        assertEquals(5, s.configReads, "nothing changed, nothing to read")
        assertEquals(Failure.Refused(ErrorCode.CONFLICT, ErrorReasons.KEY_EXISTS), s.admin.ui.value.failure)
    }

    @Test fun theListsOfTheShopFollowTheConfig() = runTest(UnconfinedTestDispatcher()) {
        val s = setup()
        s.h.config.value = ConfigState.Ready(testConfig(branches = listOf(xyz.felismp.shoparchive.shared.BranchDto("main", "Main", false), xyz.felismp.shoparchive.shared.BranchDto("old", "Old", true))))

        assertEquals(listOf("main", "old"), s.admin.ui.value.branches.map { it.key }, "archived ones too: they can be brought back")
    }

    @Test fun aNewBranchOrCategoryTakesTheNextFreeKey() = runTest(UnconfinedTestDispatcher()) {
        assertEquals("branch-1", nextKey("branch", listOf("main")))
        assertEquals("branch-3", nextKey("branch", listOf("branch-1", "main", "branch-2")))
        assertEquals("branch-2", nextKey("branch", listOf("branch-3", "branch-1")), "the lowest free one")

        val s = setup()
        s.admin.addBranch("Kiosk")
        s.admin.addCategory(LocalizedName("ທິບ", "ทิป", "Tips"), AppliesTo.INCOME)
        s.h.config.value = ConfigState.Ready(testConfig(branches = listOf(xyz.felismp.shoparchive.shared.BranchDto("branch-1", "A", true))))
        s.admin.addBranch("Market")
        assertEquals(listOf("addBranch branch-1 Kiosk", "addCategory category-1 Tips INCOME", "addBranch branch-2 Market"), s.h.api.adminCalls)
    }
}
