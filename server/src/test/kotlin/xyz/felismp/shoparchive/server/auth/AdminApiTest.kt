package xyz.felismp.shoparchive.server.auth

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import xyz.felismp.shoparchive.server.records.BRANCHES_MANAGE_NODE
import xyz.felismp.shoparchive.server.records.CATEGORIES_MANAGE_NODE
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.server.users.DISABLED_BY_BACKOFF
import xyz.felismp.shoparchive.server.users.NO_ROLE
import xyz.felismp.shoparchive.shared.AddUserBranchRequest
import xyz.felismp.shoparchive.shared.AdminRoleDto
import xyz.felismp.shoparchive.shared.AdminUserDto
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.CreateBranchRequest
import xyz.felismp.shoparchive.shared.CreateCategoryRequest
import xyz.felismp.shoparchive.shared.CreateUserRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.ROLE_NONE
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RenameUserRequest
import xyz.felismp.shoparchive.shared.SetUserRoleRequest
import xyz.felismp.shoparchive.shared.UpdateBranchRequest
import xyz.felismp.shoparchive.shared.UpdateCategoryRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/api/v1/admin/...`, and the reauth, audit and live message that `/branches` and `/categories` got: what the console does for an op, in the app. */
class AdminApiTest {
    @TempDir
    lateinit var root: Path

    private fun env() = AuthEnv(root, withRecords = true).also { it.console("branch add main Main") }

    private val userList = ListSerializer(AdminUserDto.serializer())
    private val admin = "/api/v1/admin/users"

    private suspend fun ApplicationTestBuilder.reauth(token: String) =
        postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(password = TEST_PASSWORD), token)

    /** Every admin call once, as (name, call): a stranger, a staff member or a stale session gets the same refusal from each. */
    private fun calls(): List<Pair<String, suspend ApplicationTestBuilder.(String) -> HttpResponse>> = listOf(
        "list users" to { t -> getPath(admin, t) },
        "list roles" to { t -> getPath("/api/v1/admin/roles", t) },
        "add" to { t -> postJson(admin, CreateUserRequest.serializer(), CreateUserRequest("newbie"), t) },
        "rename" to { t -> postJson("$admin/noy/rename", RenameUserRequest.serializer(), RenameUserRequest("noy2"), t) },
        "role" to { t -> putJson("$admin/noy/role", SetUserRoleRequest.serializer(), SetUserRoleRequest(ROLE_NONE), t) },
        "branch add" to { t -> postJson("$admin/noy/branches", AddUserBranchRequest.serializer(), AddUserBranchRequest("main"), t) },
        "branch remove" to { t -> deletePath("$admin/noy/branches/main", t) },
        "enable" to { t -> postRaw("$admin/noy/enable", "", t) },
        "disable" to { t -> postRaw("$admin/noy/disable", "", t) },
        "unlock" to { t -> postRaw("$admin/noy/unlock", "", t) },
        "reset" to { t -> postRaw("$admin/noy/reset", "", t) },
    )

    @Test
    fun onlyAnOpMayUseTheAdminCallsNotStaffNorAUserWhoWasGivenTheUsersNode() = env().run {
        val staff = login("noy")
        val manager = login("manager", grant = listOf(USERS_MANAGE_NODE, BRANCHES_MANAGE_NODE))
        val boss = login("boss", op = true)
        api {
            for ((what, call) in calls()) {
                for (token in listOf(staff, manager)) {
                    val refused = call(token)
                    assertEquals(HttpStatusCode.Forbidden, refused.status, what)
                    assertEquals(ErrorReasons.PERMISSION_MISSING, refused.errorReason(), what)
                }
            }
            assertEquals(HttpStatusCode.Unauthorized, getPath(admin, null).status)
            assertEquals(HttpStatusCode.OK, getPath(admin, boss).status)
        }
        // Nothing was done by the refused calls.
        assertEquals(listOf("boss", "manager", "noy"), users.userNames())
    }

    @Test
    fun everyAdminCallNeedsThePinOrPasswordEnteredRecentlyLikeTheConsole() = env().run {
        login("noy")
        val boss = login("boss", op = true)
        api {
            clock.advance(Duration.ofMinutes(6))
            for ((what, call) in calls()) {
                val asked = call(boss)
                assertEquals(HttpStatusCode.Unauthorized, asked.status, what)
                assertEquals(ErrorCode.REAUTH_REQUIRED, asked.errorCode(), what)
            }
            assertEquals(listOf("boss", "noy"), users.userNames())

            assertEquals(HttpStatusCode.NoContent, reauth(boss).status)
            assertEquals(HttpStatusCode.OK, getPath(admin, boss).status)
        }
    }

    @Test
    fun theListShowsEveryAccountWithoutAnySecret() = env().run {
        login("noy")
        val boss = login("boss", op = true)
        users.addUser("fresh", "none", listOf("main"))
        api {
            val response = getPath(admin, boss)
            val list = response.parsed(userList)

            assertEquals(listOf("boss", "fresh", "noy"), list.map { it.name })
            val bossDto = list.first { it.name == "boss" }
            assertTrue(bossDto.op && bossDto.hasPin && bossDto.hasPassword && bossDto.enabled)
            val fresh = list.first { it.name == "fresh" }
            assertTrue(!fresh.hasPin && !fresh.hasPassword, "a new account has no PIN yet: it sets one at its first login")
            assertEquals(listOf("main"), fresh.branches)
            assertEquals(ROLE_NONE, fresh.role)
            assertEquals(users.user("noy").id, list.first { it.name == "noy" }.id)

            val text = getPath(admin, boss).bodyAsText()
            assertFalse("argon2" in text, text)
            assertTrue(users.user("noy").pin!!.take(30) !in text)
        }
    }

    @Test
    fun addingAUserMakesWhatTheConsoleMakesAndGivesNoPin() = env().run {
        val boss = login("boss", op = true)
        console("role create clerk")
        console("user add viaconsole --role clerk")
        api {
            val created = postJson(admin, CreateUserRequest.serializer(), CreateUserRequest("viaapi", role = "clerk"), boss)

            assertEquals(HttpStatusCode.Created, created.status)
            val dto = created.parsed(AdminUserDto.serializer())
            val byConsole = users.user("viaconsole")
            val byApi = users.user("viaapi")
            // Same account but for the id and the name it started with: role, the branch the only active branch gives, the permission copy, no credentials.
            assertEquals(byConsole.copy(id = byApi.id, displayName = "viaapi"), byApi)
            assertEquals(listOf("main"), dto.branches)
            assertEquals("clerk", dto.role)
            assertTrue(!dto.hasPin && !dto.hasPassword && dto.enabled)
            assertFalse("argon2" in created.bodyAsText())
        }
    }

    @Test
    fun aNewUserGetsTheBranchesNamedAndNoBranchWhenThereAreSeveralActive() = env().run {
        console("branch add market Market")
        val boss = login("boss", op = true)
        api {
            val none = postJson(admin, CreateUserRequest.serializer(), CreateUserRequest("nobranch"), boss).parsed(AdminUserDto.serializer())
            val named = postJson(admin, CreateUserRequest.serializer(), CreateUserRequest("named", branches = listOf("market", "main")), boss).parsed(AdminUserDto.serializer())

            assertEquals(emptyList(), none.branches)
            assertEquals(listOf("market", "main"), named.branches)
        }
    }

    @Test
    fun refusalsCarryTheSameRulesAsTheConsoleAndAReasonTheAppCanWord() = env().run {
        login("noy")
        val boss = login("boss", op = true)
        console("role create clerk")
        api {
            suspend fun reason(response: HttpResponse, status: HttpStatusCode): String? {
                assertEquals(status, response.status)
                return response.errorReason()
            }
            suspend fun add(name: String, role: String, branches: List<String>) =
                postJson(admin, CreateUserRequest.serializer(), CreateUserRequest(name, role, branches), boss)

            assertEquals(ErrorReasons.USER_NAME_INVALID, reason(add("Bad Name", NO_ROLE, emptyList()), HttpStatusCode.BadRequest))
            assertEquals(ErrorReasons.USER_NAME_INVALID, reason(add("role", NO_ROLE, emptyList()), HttpStatusCode.BadRequest))
            assertEquals(ErrorReasons.USER_EXISTS, reason(add("noy", NO_ROLE, emptyList()), HttpStatusCode.Conflict))
            assertEquals(ErrorReasons.ROLE_NOT_FOUND, reason(add("newbie", "ghosts", emptyList()), HttpStatusCode.NotFound))
            assertEquals(ErrorReasons.KEY_INVALID, reason(add("newbie", NO_ROLE, listOf("../x")), HttpStatusCode.BadRequest))
            assertFalse("newbie" in users.userNames())

            assertEquals(ErrorReasons.USER_EXISTS, reason(postJson("$admin/noy/rename", RenameUserRequest.serializer(), RenameUserRequest("boss"), boss), HttpStatusCode.Conflict))
            assertEquals(ErrorReasons.USER_NAME_INVALID, reason(postJson("$admin/noy/rename", RenameUserRequest.serializer(), RenameUserRequest("N"), boss), HttpStatusCode.BadRequest))
            assertEquals(ErrorReasons.USER_NOT_FOUND, reason(postJson("$admin/ghost/rename", RenameUserRequest.serializer(), RenameUserRequest("spirit"), boss), HttpStatusCode.NotFound))
            assertEquals(ErrorReasons.ROLE_NOT_FOUND, reason(putJson("$admin/noy/role", SetUserRoleRequest.serializer(), SetUserRoleRequest("ghosts"), boss), HttpStatusCode.NotFound))
            assertEquals(null, reason(putJson("$admin/noy/role", SetUserRoleRequest.serializer(), SetUserRoleRequest(ROLE_NONE), boss), HttpStatusCode.Conflict), "it has no role already")
            assertEquals(null, reason(deletePath("$admin/noy/branches/market", boss), HttpStatusCode.NotFound))
            assertEquals(ErrorReasons.USER_NOT_FOUND, reason(postRaw("$admin/ghost/enable", "", boss), HttpStatusCode.NotFound))
            assertEquals(ErrorReasons.USER_NOT_FOUND, reason(postRaw("$admin/ghost/disable", "", boss), HttpStatusCode.NotFound))
            assertEquals(ErrorReasons.USER_NOT_FOUND, reason(postRaw("$admin/ghost/unlock", "", boss), HttpStatusCode.NotFound))
            assertEquals(ErrorReasons.USER_NOT_FOUND, reason(postRaw("$admin/ghost/reset", "", boss), HttpStatusCode.NotFound))
            assertEquals(ErrorReasons.KEY_INVALID, reason(postJson("$admin/noy/branches", AddUserBranchRequest.serializer(), AddUserBranchRequest("a b"), boss), HttpStatusCode.BadRequest))
            assertEquals(HttpStatusCode.BadRequest, postRaw(admin, "{not json", boss).status)
        }
    }

    @Test
    fun renamingChangingTheRoleAndTheBranchesDoWhatTheConsoleDoes() = env().run {
        console("branch add market Market")
        console("role create clerk")
        val boss = login("boss", op = true)
        val noy = login("noy")
        val id = users.user("noy").id
        api {
            val renamed = postJson("$admin/noy/rename", RenameUserRequest.serializer(), RenameUserRequest("noi"), boss).parsed(AdminUserDto.serializer())
            assertEquals("noi", renamed.name)
            assertEquals(id, renamed.id, "same id")
            assertEquals(listOf("boss", "noi"), users.userNames())

            val role = putJson("$admin/noi/role", SetUserRoleRequest.serializer(), SetUserRoleRequest("clerk"), boss).parsed(AdminUserDto.serializer())
            assertEquals("clerk", role.role)
            assertEquals("clerk", users.user("noi").role)
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/config", noy).status, "a new role ends the user's sessions, as the console does")
            assertEquals(listOf("noi"), users.usersWithRole("clerk"))
            val roles = getPath("/api/v1/admin/roles", boss).parsed(ListSerializer(AdminRoleDto.serializer()))
            assertEquals(listOf(AdminRoleDto("clerk", 1)), roles)
            assertEquals(ROLE_NONE, putJson("$admin/noi/role", SetUserRoleRequest.serializer(), SetUserRoleRequest(ROLE_NONE), boss).parsed(AdminUserDto.serializer()).role)

            val added = postJson("$admin/noi/branches", AddUserBranchRequest.serializer(), AddUserBranchRequest("market"), boss).parsed(AdminUserDto.serializer())
            assertEquals(listOf("main", "market"), added.branches)
            val removed = deletePath("$admin/noi/branches/main", boss).parsed(AdminUserDto.serializer())
            assertEquals(listOf("market"), removed.branches)
            assertEquals(listOf("market"), users.user("noi").branches)
        }
    }

    @Test
    fun disablingEndsTheSessionsAndEnablingAndUnlockingForgetTheWrongTries() = env().run {
        val boss = login("boss", op = true)
        val noy = login("noy")
        api {
            val disabled = postRaw("$admin/noy/disable", "", boss).parsed(AdminUserDto.serializer())
            assertTrue(!disabled.enabled)
            assertEquals("admin", disabled.disabledReason)
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/config", noy).status)

            val enabled = postRaw("$admin/noy/enable", "", boss).parsed(AdminUserDto.serializer())
            assertTrue(enabled.enabled && enabled.disabledReason == null)

            users.updateLogin(users.user("noy").id) { it.copy(enabled = false, disabledReason = DISABLED_BY_BACKOFF, failedLogins = 5) }
            val locked = getPath(admin, boss).parsed(userList).first { it.name == "noy" }
            assertEquals(5, locked.failedLogins)
            assertEquals(DISABLED_BY_BACKOFF, locked.disabledReason)
            val unlocked = postRaw("$admin/noy/unlock", "", boss).parsed(AdminUserDto.serializer())
            assertTrue(unlocked.enabled && unlocked.failedLogins == 0 && unlocked.disabledReason == null)
        }
    }

    @Test
    fun resettingTakesTheUserOffEveryDeviceAndClearsThePinLikeTheConsoleButNotYourOwnAccount() = env().run {
        val boss = login("boss", op = true)
        val noy = login("noy")
        login("kham")
        val noyId = users.user("noy").id
        console("user reset kham")
        api {
            val reset = postRaw("$admin/noy/reset", "", boss).parsed(AdminUserDto.serializer())

            assertTrue(!reset.hasPin && !reset.hasPassword)
            assertEquals(users.user("kham").copy(id = noyId, displayName = "noy"), users.user("noy"))
            assertTrue(auth.devices.devicesOf(noyId).isEmpty())
            assertEquals(HttpStatusCode.Unauthorized, getPath("/api/v1/config", noy).status)
            assertTrue(audit().lines().any { "account.reset user=noy" in it && "ip=localhost" in it }, audit())

            val own = postRaw("$admin/boss/reset", "", boss)
            assertEquals(HttpStatusCode.Forbidden, own.status)
            assertEquals(ErrorReasons.USER_SELF_RESET, own.errorReason())
            assertTrue(users.user("boss").pin != null, "the op's own PIN is still there")
        }
    }

    @Test
    fun anOpCannotDisableOrChangeTheRoleOfTheirOwnAccountFromTheAppButTheConsoleStillCan() = env().run {
        val boss = login("boss", op = true)
        login("boss2", op = true)
        console("role create clerk")
        api {
            val disable = postRaw("$admin/boss/disable", "", boss)
            assertEquals(HttpStatusCode.Conflict, disable.status)
            assertEquals(ErrorCode.CONFLICT, disable.errorCode())
            assertEquals(ErrorReasons.USER_SELF_CHANGE, disable.errorReason())
            val role = putJson("$admin/boss/role", SetUserRoleRequest.serializer(), SetUserRoleRequest("clerk"), boss)
            assertEquals(HttpStatusCode.Conflict, role.status)
            assertEquals(ErrorReasons.USER_SELF_CHANGE, role.errorReason())
            assertTrue(users.user("boss").enabled && users.user("boss").role == NO_ROLE)
            assertEquals(HttpStatusCode.OK, getPath("/api/v1/config", boss).status, "the session is still there")

            // Another op may; enabling and the other changes on yourself are not held back.
            assertEquals(HttpStatusCode.OK, postRaw("$admin/boss/enable", "", boss).status)
            assertEquals(HttpStatusCode.OK, postRaw("$admin/boss2/disable", "", boss).status)
        }
        console("user disable boss")
        assertFalse(users.user("boss").enabled)
    }

    /** Whoever calls is an op that no account stands behind, as in a race with a disable: only the guard on the target is left to test. */
    private object AnyOp : AuthService {
        override fun login(request: LoginRequest, ip: String): LoginResponse = error("unused")
        override fun unlock(request: UnlockRequest, ip: String): UnlockResponse = error("unused")
        override fun authenticate(accessToken: String): Principal? = null
        override fun isValid(principal: Principal) = true
        override fun hasPermission(principal: Principal, node: String) = true
        override fun canAccessBranch(principal: Principal, branch: String) = true
        override fun requireRecentAuth(principal: Principal) {}
        override fun reauth(principal: Principal, request: ReauthRequest, ip: String) {}
    }

    @Test
    fun theLastEnabledOpCannotBeDisabledOrGivenAnotherRole() = env().run {
        console("role create clerk")
        addUser("boss", op = true)
        val services = Services().also { it.register(AuthService::class.java, AnyOp, 0, "test") }
        val service = DefaultUserAdminService(users, auth.accounts, auth.audit, services) { emptyList() }
        val caller = Principal("stranger", "stranger", "device", "session")

        for (call in listOf({ service.setEnabled(caller, "boss", false, "ip") }, { service.setRole(caller, "boss", "clerk", "ip") })) {
            val refused = assertFailsWith<ApiError> { call() }
            assertEquals(409, refused.status)
            assertEquals(ErrorReasons.USER_LAST_OP, refused.reason)
        }
        assertTrue(users.user("boss").enabled && users.user("boss").role == NO_ROLE)

        // With a second op the first may go; a disabled op does not count, so the second is the last then.
        addUser("boss2", op = true)
        assertFalse(service.setEnabled(caller, "boss", false, "ip").enabled)
        assertEquals(ErrorReasons.USER_LAST_OP, assertFailsWith<ApiError> { service.setEnabled(caller, "boss2", false, "ip") }.reason)
        // Someone who is no op is not held back by it.
        addUser("noy")
        assertEquals("clerk", service.setRole(caller, "noy", "clerk", "ip").role)
    }

    @Test
    fun everyChangeIsAuditedWithWhoDidItAndToWhomAndNeverASecret() = env().run {
        val boss = login("boss", op = true)
        login("noy")
        api {
            postJson(admin, CreateUserRequest.serializer(), CreateUserRequest("newbie"), boss)
            postRaw("$admin/noy/disable", "", boss)
            postRaw("$admin/noy/reset", "", boss)
            getPath(admin, boss)
            postJson(admin, CreateUserRequest.serializer(), CreateUserRequest("noy"), boss) // refused: not a change
        }

        val lines = audit().lines().filter { " admin.user." in it }
        assertEquals(3, lines.size, lines.toString())
        assertTrue(lines[0].contains(" admin.user.add user=boss device=") && lines[0].endsWith("ip=localhost result=ok,target=newbie"), lines[0])
        assertTrue(lines[1].contains(" admin.user.disable user=boss ") && lines[1].endsWith("result=ok,target=noy"), lines[1])
        assertTrue(lines[2].contains(" admin.user.reset user=boss ") && lines[2].endsWith("result=ok,target=noy"), lines[2])
        assertFalse(audit().contains("argon2"))
    }

    @Test
    fun getConfigSaysWhoIsAnOpSoTheAppShowsTheAdminAreaToThemOnly() = env().run {
        val boss = login("boss", op = true)
        val manager = login("manager", grant = listOf(USERS_MANAGE_NODE))
        api {
            assertTrue(getPath("/api/v1/config", boss).parsed(ConfigResponse.serializer()).admin)
            assertFalse(getPath("/api/v1/config", manager).parsed(ConfigResponse.serializer()).admin)
        }
    }

    // --- branches and categories: the routes that were there, now with the console's reauth, an audit line and a live message ---

    @Test
    fun branchesAndCategoriesNeedThePinEnteredRecentlyAndNothingChangesWithoutIt() = env().run {
        val manager = login("manager", grant = listOf(BRANCHES_MANAGE_NODE, CATEGORIES_MANAGE_NODE))
        val name = LocalizedName("ວັດສະດຸ", "วัสดุ", "Supplies")
        api {
            clock.advance(Duration.ofMinutes(6))

            val refused = listOf(
                postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("kiosk", "Kiosk"), manager),
                putJson("/api/v1/branches/main", UpdateBranchRequest.serializer(), UpdateBranchRequest(archived = true), manager),
                postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest("supplies", name, AppliesTo.EXPENSE), manager),
                putJson("/api/v1/categories/supplies", UpdateCategoryRequest.serializer(), UpdateCategoryRequest(archived = true), manager),
            )
            for (response in refused) assertEquals(ErrorCode.REAUTH_REQUIRED, response.errorCode())
            assertEquals(listOf("main"), records!!.branches.all().map { it.key })
            assertEquals(emptyList(), records.categories.all())

            reauth(manager)
            assertEquals(HttpStatusCode.Created, postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("kiosk", "Kiosk"), manager).status)
        }
    }

    @Test
    fun branchAndCategoryRefusalsCarryReasonsTheAppCanWord() = env().run {
        val manager = login("manager", grant = listOf(BRANCHES_MANAGE_NODE, CATEGORIES_MANAGE_NODE))
        val name = LocalizedName("ວັດສະດຸ", "วัสดุ", "Supplies")
        api {
            suspend fun reason(response: HttpResponse) = response.errorReason()
            suspend fun branch(key: String, display: String) = reason(postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest(key, display), manager))
            suspend fun category(key: String, names: LocalizedName) =
                reason(postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest(key, names, AppliesTo.BOTH), manager))

            assertEquals(ErrorReasons.KEY_INVALID, branch("../x", "X"))
            assertEquals(ErrorReasons.KEY_EXISTS, branch("main", "Again"))
            assertEquals(ErrorReasons.NAME_REQUIRED, branch("kiosk", " "))
            assertEquals(ErrorReasons.BRANCH_UNKNOWN, reason(putJson("/api/v1/branches/ghost", UpdateBranchRequest.serializer(), UpdateBranchRequest(archived = true), manager)))
            assertEquals(ErrorReasons.NAME_REQUIRED, reason(putJson("/api/v1/branches/main", UpdateBranchRequest.serializer(), UpdateBranchRequest(displayName = " "), manager)))

            assertEquals(ErrorReasons.KEY_INVALID, category("Bad Key", name))
            assertEquals(ErrorReasons.NAME_REQUIRED, category("supplies", name.copy(th = "")))
            assertEquals(HttpStatusCode.Created, postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest("supplies", name, AppliesTo.BOTH), manager).status)
            assertEquals(ErrorReasons.KEY_EXISTS, category("supplies", name))
            assertEquals(ErrorReasons.ENTRY_CATEGORY_UNKNOWN, reason(putJson("/api/v1/categories/ghost", UpdateCategoryRequest.serializer(), UpdateCategoryRequest(archived = true), manager)))
        }
    }

    @Test
    fun aBranchOrCategoryChangeIsAuditedAndTellsEveryOpenApp() = env().run {
        val manager = login("manager", grant = listOf(BRANCHES_MANAGE_NODE, CATEGORIES_MANAGE_NODE))
        val noy = login("noy")
        val seen = mutableListOf<String>()
        auth.events.register(authService().authenticate(noy)!!) { seen += it; true }.use {
            api {
                val name = LocalizedName("ວັດສະດຸ", "วัสดุ", "Supplies")
                postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("kiosk", "Kiosk"), manager)
                putJson("/api/v1/branches/kiosk", UpdateBranchRequest.serializer(), UpdateBranchRequest(archived = true), manager)
                postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest("supplies", name, AppliesTo.EXPENSE), manager)
                putJson("/api/v1/categories/supplies", UpdateCategoryRequest.serializer(), UpdateCategoryRequest(name = name.copy(en = "Bits")), manager)
                postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("kiosk", "Again"), manager) // refused: nothing to tell
            }
        }

        assertEquals(List(4) { """{"type":"config.changed"}""" }, seen)
        val lines = audit().lines().filter { Regex(" (branch|category)\\.").containsMatchIn(it) }
        assertEquals(listOf("branch.add", "branch.update", "category.add", "category.update"), lines.map { Regex(" ((branch|category)\\.[a-z]+) ").find(it)!!.groupValues[1] })
        assertTrue(lines.all { " user=manager " in it }, lines.toString())
        assertTrue(lines[0].endsWith("result=ok,key=kiosk"), lines[0])
    }

    @Test
    fun addingAndRemovingABranchOfAUserTellsThatUsersOpenApps() = env().run {
        console("branch add market Market")
        val boss = login("boss", op = true)
        val noy = login("noy")
        val kham = login("kham")
        val noySeen = mutableListOf<String>()
        val khamSeen = mutableListOf<String>()
        val a = auth.events.register(authService().authenticate(noy)!!) { noySeen += it; true }
        val b = auth.events.register(authService().authenticate(kham)!!) { khamSeen += it; true }
        api {
            postJson("$admin/noy/branches", AddUserBranchRequest.serializer(), AddUserBranchRequest("market"), boss)
            deletePath("$admin/noy/branches/market", boss)
        }
        a.close()
        b.close()

        assertEquals(2, noySeen.size)
        assertEquals(emptyList(), khamSeen)
    }
}
