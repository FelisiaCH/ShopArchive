package xyz.felismp.shoparchive.server.records

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.getPath
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.server.auth.putJson
import xyz.felismp.shoparchive.server.config.backups
import xyz.felismp.shoparchive.server.config.text
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.CreateBranchRequest
import xyz.felismp.shoparchive.shared.CreateCategoryRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.UpdateBranchRequest
import xyz.felismp.shoparchive.shared.UpdateCategoryRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Branches and categories: the files, the console, the API, and what GET /config says about them. */
class BranchesCategoriesTest {
    @TempDir
    lateinit var root: Path

    private fun env() = AuthEnv(root, withRecords = true)

    // --- console and files ---

    @Test
    fun theConsoleAddsListsAndArchivesBranchesAndTheFileIsVersioned() = env().run {
        assertEquals(listOf("No branches yet. Add one with: branch add main Main"), console("branch list"))

        assertEquals(listOf("Branch 'main' added: Main"), console("branch add main Main"))
        console("branch add market Night Market  ວັດ")
        console("branch archive market")

        assertEquals(listOf("main: Main", "market: Night Market ວັດ (archived)"), console("branch list"))
        val text = root.text("data/branches.yml")
        assertTrue(text.contains("\nfile-version: 1\n"), text)
        assertTrue(text.contains("  - key: market\n    display-name: \"Night Market ວັດ\"\n    archived: true\n"), text)
        console("branch unarchive market")
        assertFalse(records!!.branches.find("market")!!.archived)
    }

    @Test
    fun aNewUserJoinsTheOnlyActiveBranchAndArchivedBranchesDoNotCount() = env().run {
        console("branch add main Main")
        console("branch add market Market")
        console("branch archive market")

        assertEquals("User 'a1x' created, branches main. They open the app, type the name 'a1x' and set their own PIN.", console("user add a1x").single())

        console("branch unarchive market")
        assertEquals(listOf("main", "market"), records!!.branches.activeKeys())
        assertEquals("No branch yet: user branch b2x add <branch>", console("user add b2x").last())
        assertEquals(emptyList(), users.user("b2x").branches)
    }

    @Test
    fun branchCommandsRefuseWhatIsWrongWithAMessageNotACrash() = env().run {
        console("branch add main Main")

        assertEquals(listOf("A branch 'main' exists already."), console("branch add main Again"))
        assertEquals(listOf("A branch key is 1 to 32 characters from a-z, 0-9 and -."), console("branch add Bad_Key Name"))
        assertEquals(listOf("No branch 'ghost'."), console("branch archive ghost"))
        assertTrue(console("branch add").single().startsWith("Usage: branch add"))
        assertTrue(console("branch nonsense").single().startsWith("Usage: branch"))
        assertEquals(1, records!!.branches.all().size)
    }

    @Test
    fun categoriesHaveThreeNamesAKindAndAnImmutableKey() = env().run {
        assertTrue(console("category add supplies expense Ice and cups").single().contains("Category 'supplies' added for expense"))
        console("category name supplies lo ນ້ຳກ້ອນ")
        console("category name supplies th น้ำแข็ง")
        console("category add sales income Sales")
        console("category archive sales")

        assertEquals(
            listOf("supplies: Ice and cups / น้ำแข็ง / ນ້ຳກ້ອນ [expense]", "sales: Sales / Sales / Sales [income] (archived)"),
            console("category list"),
        )
        val text = root.text("data/categories.yml")
        assertTrue(text.contains("  - key: supplies\n    name: { lo: \"ນ້ຳກ້ອນ\", th: \"น้ำแข็ง\", en: \"Ice and cups\" }\n    applies-to: expense\n    archived: false\n"), text)
        assertEquals(listOf("A category 'supplies' exists already."), console("category add supplies both Again"))
        assertTrue(console("category add x sideways Name").single().startsWith("Usage: category add"))
        assertTrue(console("category name supplies fr Texte").single().startsWith("Usage: category name"))
    }

    @Test
    fun theDataFilesAreReadBackAfterARestartAndAHandEditIsKeptWhenTheConsoleChangesSomethingElse() {
        env().run {
            console("branch add main Main")
            console("category add sales income Sales")
        }
        val second = env()
        assertEquals(listOf("main"), second.records!!.branches.all().map { it.key })
        assertEquals(AppliesTo.INCOME, second.records.categories.find("sales")!!.appliesTo)

        // an admin edits the file by hand, adding a branch
        val file = root.resolve("data/branches.yml")
        Files.writeString(file, Files.readString(file) + "  - key: harbour\n    display-name: \"Harbour\"\n    archived: false\n")
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))

        second.console("branch add kiosk Kiosk")

        assertEquals(listOf("main", "harbour", "kiosk"), second.records.branches.all().map { it.key })
        assertTrue(second.log.warnings.any { "changed by hand" in it })
    }

    @Test
    fun aBranchesFileThatIsNotUnderstoodStopsTheStartAndIsNeverReplaced() {
        Files.createDirectories(root.resolve("data"))
        Files.writeString(root.resolve("data/branches.yml"), "file-version: 1\nbranches: [oops: : :")
        val failure = runCatching { env() }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure.message!!.contains("data/branches.yml"), failure.toString())
        assertEquals("file-version: 1\nbranches: [oops: : :", root.text("data/branches.yml"))
    }

    @Test
    fun badEntriesInTheFilesAreWarnedAboutAndTheGoodOnesKept() {
        Files.createDirectories(root.resolve("data"))
        Files.writeString(
            root.resolve("data/branches.yml"),
            "file-version: 1\nbranches:\n  - key: main\n    display-name: \"Main\"\n  - key: Bad Key\n  - key: main\n  - key: shop\n    archived: maybe\n",
        )
        Files.writeString(
            root.resolve("data/categories.yml"),
            "file-version: 1\ncategories:\n  - key: ok\n    name: { en: \"Okay\" }\n    applies-to: both\n  - key: bad\n    applies-to: sideways\n",
        )

        val e = env()

        assertEquals(listOf("main", "shop"), e.records!!.branches.all().map { it.key })
        assertEquals("Okay", e.records.categories.find("ok")!!.name.lo, "a missing name falls back to English")
        assertEquals(listOf("ok"), e.records.categories.all().map { it.key })
        for (part in listOf("needs a key", "listed twice", "archived 'maybe'", "applies-to 'sideways'")) {
            assertTrue(e.log.warnings.any { part in it }, "$part in ${e.log.warnings}")
        }
        assertTrue(e.root.backups().any { it.endsWith("branches.yml") }, "the file as it was is saved before it is rewritten")
    }

    @Test
    fun usersWhoseBranchIsNotInTheFileAreWarnedAboutAtLoadAndOnReloadData() {
        val first = env()
        first.console("branch add main Main")
        first.users.addUser("noy", "none", listOf("main", "ghost"))

        val second = env()
        assertTrue(second.log.warnings.any { "user 'noy'" in it && "'ghost'" in it }, second.log.warnings.toString())
        assertFalse(second.log.warnings.any { "'main'" in it })

        second.users.addUser("lek", "none", listOf("lost"))
        second.log.warnings.clear()
        second.console("reload data")
        assertTrue(second.log.warnings.any { "user 'lek'" in it && "'lost'" in it }, second.log.warnings.toString())
    }

    @Test
    fun aFirstRunWithNoBranchesSaysHowToAddOne() {
        val e = env()

        assertTrue(e.log.infos.any { it == "No branches yet. Add the first one with: branch add main Main" }, e.log.infos.toString())
    }

    // --- API ---

    @Test
    fun theBranchListIsThoseTheCallerWorksInAndAllWithBranchAll() = env().run {
        console("branch add main Main")
        console("branch add market Market")
        val noy = login("noy", listOf("main"))
        val boss = login("boss", op = true)
        api {
            assertEquals(listOf("main"), getPath("/api/v1/branches", noy).parsed(ListSerializer(BranchDto.serializer())).map { it.key })
            assertEquals(listOf("main", "market"), getPath("/api/v1/branches", boss).parsed(ListSerializer(BranchDto.serializer())).map { it.key })
        }
    }

    @Test
    fun managingBranchesNeedsTheNodeAndKeysNeverChange() = env().run {
        console("branch add main Main")
        val noy = login("noy")
        val manager = login("manager", grant = listOf(BRANCHES_MANAGE_NODE))
        api {
            assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("kiosk", "Kiosk"), noy).status)
            assertEquals(HttpStatusCode.Forbidden, putJson("/api/v1/branches/main", UpdateBranchRequest.serializer(), UpdateBranchRequest(archived = true), noy).status)

            val created = postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("kiosk", "Kiosk"), manager)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(BranchDto("kiosk", "Kiosk", false), created.parsed(BranchDto.serializer()))
            val duplicate = postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("kiosk", "Again"), manager)
            assertEquals(HttpStatusCode.Conflict, duplicate.status)
            assertEquals(ErrorCode.CONFLICT, duplicate.errorCode())
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/branches", CreateBranchRequest.serializer(), CreateBranchRequest("../x", "X"), manager).status)

            val renamed = putJson("/api/v1/branches/kiosk", UpdateBranchRequest.serializer(), UpdateBranchRequest(displayName = "Kiosk 2", archived = true), manager)
            assertEquals(BranchDto("kiosk", "Kiosk 2", true), renamed.parsed(BranchDto.serializer()))
            assertEquals(HttpStatusCode.NotFound, putJson("/api/v1/branches/ghost", UpdateBranchRequest.serializer(), UpdateBranchRequest(archived = true), manager).status)
            assertEquals(HttpStatusCode.BadRequest, putJson("/api/v1/branches/kiosk", UpdateBranchRequest.serializer(), UpdateBranchRequest(displayName = " "), manager).status)
            assertEquals(listOf("main", "kiosk"), records!!.branches.all().map { it.key })
        }
    }

    @Test
    fun managingCategoriesNeedsTheNodeAndAnyoneMayReadThem() = env().run {
        val noy = login("noy")
        val manager = login("manager", grant = listOf(CATEGORIES_MANAGE_NODE))
        val name = LocalizedName("ວັດສະດຸ", "วัสดุ", "Supplies")
        api {
            assertEquals(HttpStatusCode.Forbidden, postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest("supplies", name, AppliesTo.EXPENSE), noy).status)

            val created = postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest("supplies", name, AppliesTo.EXPENSE), manager)
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals(CategoryDto("supplies", name, AppliesTo.EXPENSE, false), created.parsed(CategoryDto.serializer()))
            assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest("supplies", name, AppliesTo.BOTH), manager).status)
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/categories", CreateCategoryRequest.serializer(), CreateCategoryRequest("x", name.copy(th = ""), AppliesTo.BOTH), manager).status)

            val updated = putJson("/api/v1/categories/supplies", UpdateCategoryRequest.serializer(), UpdateCategoryRequest(name = name.copy(en = "Supplies 2"), appliesTo = AppliesTo.BOTH, archived = true), manager)
            assertEquals(CategoryDto("supplies", name.copy(en = "Supplies 2"), AppliesTo.BOTH, true), updated.parsed(CategoryDto.serializer()))
            assertEquals(HttpStatusCode.Forbidden, putJson("/api/v1/categories/supplies", UpdateCategoryRequest.serializer(), UpdateCategoryRequest(archived = false), noy).status)
            assertEquals(listOf("supplies"), getPath("/api/v1/categories", noy).parsed(ListSerializer(CategoryDto.serializer())).map { it.key })
        }
    }

    @Test
    fun getConfigListsTheCallersBranchesAllCategoriesAndTheRecordRules() = env().run {
        console("branch add main Main")
        console("branch add market Market")
        console("category add sales income Sales")
        val noy = login("noy", listOf("market"))
        api {
            val config = getPath("/api/v1/config", noy).parsed(ConfigResponse.serializer())

            assertEquals(listOf("market"), config.branches.map { it.key })
            assertEquals(listOf("sales"), config.categories.map { it.key })
            assertEquals(true, config.records.requireOpenDay)
            assertEquals(false, config.records.requireCategory)
            assertEquals(5, config.records.slipMaxCount)
            assertEquals(5120, config.records.slipMaxSizeKb)
            assertEquals(listOf("LAK", "THB", "USD"), config.currencies.map { it.code })
        }
    }

    @Test
    fun getConfigListsThePermissionsTheCallerHoldsAndAnOpHoldsAll() = env().run {
        console("branch add main Main")
        val staff = login("staff", listOf("main"))
        val boss = login("boss", listOf("main"), op = true)
        api {
            val own = getPath("/api/v1/config", staff).parsed(ConfigResponse.serializer()).permissions
            assertTrue(PermissionNodes.ENTRY_CREATE in own && PermissionNodes.DAY_OPEN in own, "the defaults of a clerk")
            assertTrue(PermissionNodes.DASHBOARD_VIEW !in own && PermissionNodes.DAY_CLOSE !in own && PermissionNodes.EXPORT !in own)

            val all = getPath("/api/v1/config", boss).parsed(ConfigResponse.serializer()).permissions
            assertEquals(nodes.all().map { it.node }.sorted(), all.sorted())
        }
    }
}
