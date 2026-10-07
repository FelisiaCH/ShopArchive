package xyz.felismp.shoparchive.server.users

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.config.FIXED_CLOCK
import xyz.felismp.shoparchive.server.config.FIXED_STAMP
import xyz.felismp.shoparchive.server.config.MigrationStep
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.server.config.backups
import xyz.felismp.shoparchive.server.config.loadConfigFile
import xyz.felismp.shoparchive.server.config.prepareRoot
import xyz.felismp.shoparchive.server.config.text
import xyz.felismp.shoparchive.server.config.write
import xyz.felismp.shoparchive.server.registerCoreCommands
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsersTest {
    @TempDir
    lateinit var root: Path

    private class RecordingSender : CommandSender {
        override val name = "test"
        val messages = mutableListOf<String>()
        override fun sendMessage(message: String) {
            messages += message
        }
    }

    private val log = RecordingLog()
    private val nodes = Permissions()
    private val sender = RecordingSender()
    private val commands = Commands()
    private lateinit var store: UserStore

    @BeforeTest
    fun setUp() {
        prepareRoot(root)
        nodes.register(PermissionNode("test.view", "View things", default = true, lo = "ເບິ່ງ"))
        nodes.register(PermissionNode("test.edit", "Edit things", default = false))
        val config = ConfigService(root, log = log, clock = FIXED_CLOCK).also { it.load() }
        store = UserStore(root, nodes, config, log, FIXED_CLOCK)
        store.load()
        registerCoreCommands(commands, config, mapOf("users" to store::load))
        registerUserCommands(commands, store)
    }

    /** Runs [line] and returns what the console printed for it. */
    private fun run(line: String): List<String> {
        sender.messages.clear()
        commands.run(sender, line)
        return sender.messages.toList()
    }

    private fun reloadFromDisk() = store.load()

    /** The lines of a file that are not comments or blank. */
    private fun content(relative: String) = root.text(relative).lines().filter { it.isNotBlank() && !it.startsWith("#") }

    /** Makes a hand edit look like one: written now, with a modified time that cannot equal the server's own write. */
    private fun handEdit(relative: String, text: String) {
        root.write(relative, text)
        Files.setLastModifiedTime(root.resolve(relative), FileTime.from(Instant.now().plusSeconds(60)))
    }

    private fun roleWithEdit() {
        run("role create cashier")
        run("role perm cashier test.edit true")
    }

    // --- creating ---

    @Test
    fun userAddWithRoleAndBranchWritesTheFileInThePlansShape() {
        roleWithEdit()

        val reply = run("user add noy --role cashier --branch market")

        assertEquals(listOf("User 'noy' created, role cashier, branches market. They open the app, type the name 'noy' and set their own PIN."), reply)
        val lines = content("user/noy.yml")
        assertEquals(
            listOf("file-version: 1", "id:", "display-name: \"noy\"", "enabled: true", "op: false", "role: cashier",
                "branches: [market]", "locale: lo", "password: null", "pin: null", "failed-logins: 0", "locked-until: null", "disabled-reason: null", "permissions:", "test.edit: true", "test.view: true"),
            lines.map { it.trim().let { l -> if (l.startsWith("id:")) "id:" else l } },
        )
        assertTrue(Regex("id: [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(lines[1]), lines[1])
        assertTrue("A copy of role 'cashier'" in root.text("user/noy.yml"))
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun userAddWithoutARoleStartsFromTheDefaultsAndRoleCreateToo() {
        run("user add noy")
        run("role create cashier")

        assertEquals(listOf("test.edit: false", "test.view: true"), content("user/noy.yml").takeLast(2).map { it.trim() })
        assertEquals(listOf("test.edit: false", "test.view: true"), content("user/role/cashier.yml").takeLast(2).map { it.trim() })
        assertTrue(content("user/noy.yml").contains("role: none"))
    }

    @Test
    fun badNamesAndUnknownRolesAreRefusedAndNothingIsWritten() {
        val bad = listOf("user add ab", "user add Noy", "user add ${"a".repeat(33)}", "user add role", "user add no-y", "role create none", "role create ab", "role create Cash")
        for (line in bad) {
            val reply = run(line)
            assertTrue(reply.single().startsWith("Invalid "), "$line -> $reply")
        }
        assertEquals(listOf("No role 'nope'"), run("user add noy --role nope"))
        assertEquals(listOf("Usage: user add <name> [--role <role>] [--branch <branch>...]"), run("user add noy --role"))
        run("user add noy")
        assertEquals(listOf("User 'noy' already exists"), run("user add noy"))

        assertEquals(listOf("noy.yml"), Files.list(root.resolve("user")).use { f -> f.map { it.fileName.toString() }.filter { it.endsWith(".yml") }.toList() })
    }

    @Test
    fun branchesCanBeGivenAsSeveralWordsOrSeveralFlagsAndChangedLater() {
        run("user add lek --branch main market --branch shop2")
        run("user branch lek add extra")
        run("user branch lek remove main")

        assertTrue("branches: [market, shop2, extra]" in root.text("user/lek.yml"), root.text("user/lek.yml"))
        assertEquals(listOf("User 'lek' has no branch 'main'"), run("user branch lek remove main"))
    }

    @Test
    fun theFirstRunWithoutUsersSuggestsUserAddThenOp() {
        assertTrue(log.infos.any { "user add <name>" in it && "op <name>" in it }, log.infos.toString())
        assertEquals(listOf("No users yet. Create one with: user add <name>"), run("user list"))
    }

    // --- resolving ---

    @Test
    fun opHasEveryNodeAndDeopTakesItBack() {
        run("user add noy")
        assertFalse(store.hasPermission("noy", "test.edit"))

        run("op noy")
        assertTrue(store.hasPermission("noy", "test.edit") && store.hasPermission("noy", "test.view") && store.hasPermission("noy", "anything.at.all"))
        assertTrue("op: true" in root.text("user/noy.yml"))

        run("deop noy")
        assertFalse(store.hasPermission("noy", "test.edit"))
        assertFalse(store.hasPermission("nobody", "test.view"))
    }

    @Test
    fun aRoleUserGetsTheRolesValuesAndANoRoleUserItsOwn() {
        roleWithEdit()
        run("user add noy --role cashier")
        run("user add lek")
        run("perm lek test.view false")

        assertTrue(store.hasPermission("noy", "test.edit") && store.hasPermission("noy", "test.view"))
        assertFalse(store.hasPermission("lek", "test.view"))
        assertFalse(store.hasPermission("lek", "test.edit"))
        // A node a file has no value for gets the node's default.
        nodes.register(PermissionNode("test.later", "Added later", default = true))
        assertTrue(store.hasPermission("lek", "test.later"))
    }

    @Test
    fun aUserWhoseRoleIsMissingHasNoPermissionsAndTheFileIsKept() {
        roleWithEdit()
        run("user add noy --role cashier")
        Files.delete(root.resolve("user/role/cashier.yml"))
        val before = root.text("user/noy.yml")

        reloadFromDisk()

        assertFalse(store.hasPermission("noy", "test.view"))
        assertTrue(log.warningsWith("user/noy.yml", "role 'cashier' does not exist").isNotEmpty(), log.warnings.toString())
        assertEquals(before.substringAfter("file-version"), root.text("user/noy.yml").substringAfter("file-version"))
    }

    // --- roles and the copy in the user file ---

    @Test
    fun editingARoleFileThenReloadUsersChangesItsUsersAndTheirCopy() {
        roleWithEdit()
        run("user add noy --role cashier")
        run("user add lek --role cashier")
        run("user add som")
        val somBefore = Files.readAllBytes(root.resolve("user/som.yml"))
        assertTrue(store.hasPermission("noy", "test.edit"))

        root.write("user/role/cashier.yml", root.text("user/role/cashier.yml").replace("test.edit: true", "test.edit: false"))
        val reply = run("reload users")

        assertEquals(listOf("Reload users done"), reply)
        assertFalse(store.hasPermission("noy", "test.edit"))
        assertFalse(store.hasPermission("lek", "test.edit"))
        for (name in listOf("noy", "lek")) assertTrue("test.edit: false" in root.text("user/$name.yml"), root.text("user/$name.yml"))
        assertContentEquals(somBefore, Files.readAllBytes(root.resolve("user/som.yml")))
        assertTrue(Files.exists(root.resolve("data/migration")) && log.infos.any { "rewritten" in it && "user/noy.yml" in it }, log.infos.toString())
    }

    @Test
    fun aHandEditedCopyInARoleUsersFileIsOverwrittenWithAWarning() {
        roleWithEdit()
        run("user add noy --role cashier")
        root.write("user/noy.yml", root.text("user/noy.yml").replace("test.edit: true", "test.edit: false"))

        reloadFromDisk()

        assertTrue(log.warningsWith("user/noy.yml", "differs from role 'cashier'").isNotEmpty(), log.warnings.toString())
        assertTrue("test.edit: true" in root.text("user/noy.yml"))
        assertTrue(store.hasPermission("noy", "test.edit"))
    }

    @Test
    fun permOnARoleUserIsAnErrorThatPointsToTheRoleAndLeavesTheFileAlone() {
        roleWithEdit()
        run("user add noy --role cashier")
        val before = Files.readAllBytes(root.resolve("user/noy.yml"))

        val reply = run("perm noy test.edit false")

        assertEquals(1, reply.size)
        assertTrue("role perm cashier test.edit false" in reply[0] && "user role noy none" in reply[0], reply[0])
        assertContentEquals(before, Files.readAllBytes(root.resolve("user/noy.yml")))
    }

    @Test
    fun rolePermChangesTheRoleAndEveryUserInIt() {
        run("role create cashier")
        run("user add noy --role cashier")
        run("user add lek --role cashier")

        val reply = run("role perm cashier test.edit true")

        assertEquals(listOf("Role 'cashier': test.edit = true (2 users updated)"), reply)
        assertTrue("test.edit: true" in root.text("user/role/cashier.yml"))
        for (name in listOf("noy", "lek")) assertTrue("test.edit: true" in root.text("user/$name.yml"))
        assertEquals(listOf("cashier: 2 users"), run("role list"))
    }

    @Test
    fun leavingARoleKeepsItsValuesAndJoiningOneLogsTheOldOnes() {
        roleWithEdit()
        run("user add noy --role cashier")

        assertEquals(listOf("User 'noy' has no role now; the values of role 'cashier' are its own"), run("user role noy none"))
        assertTrue("role: none" in root.text("user/noy.yml") && "test.edit: true" in root.text("user/noy.yml"))
        run("perm noy test.edit false")
        assertFalse(store.hasPermission("noy", "test.edit"))

        run("user role noy cashier")
        assertTrue(store.hasPermission("noy", "test.edit"))
        assertTrue(log.infos.any { "user 'noy': role none -> cashier" in it && "test.edit=false" in it && "test.view=true" in it }, log.infos.toString())
        assertEquals(listOf("User 'noy' already has role 'cashier'"), run("user role noy cashier"))
        assertEquals(listOf("No role 'ghost'"), run("user role noy ghost"))
    }

    @Test
    fun leavingARoleCopiesTheRoleTheFileNamesNowNotTheOneMemoryHad() {
        roleWithEdit()
        run("role create viewer")
        run("user add noy --role cashier")
        assertTrue(store.hasPermission("noy", "test.edit"))
        // Hand edit, no 'reload users': the file now says viewer, which cannot edit.
        handEdit("user/noy.yml", root.text("user/noy.yml").replace("role: cashier", "role: viewer"))

        run("user role noy none")

        val own = store.user("noy").permissions
        assertEquals(false, own["test.edit"], own.toString())
        assertEquals(true, own["test.view"], own.toString())
        assertTrue("role: none" in root.text("user/noy.yml") && "test.edit: false" in root.text("user/noy.yml"), root.text("user/noy.yml"))
        assertFalse(store.hasPermission("noy", "test.edit"))
    }

    @Test
    fun leavingARoleCopiesTheValuesOfARoleFileEditedByHandWithoutReload() {
        roleWithEdit()
        run("user add noy --role cashier")
        handEdit("user/role/cashier.yml", root.text("user/role/cashier.yml").replace("test.edit: true", "test.edit: false"))

        run("user role noy none")

        assertEquals(false, store.user("noy").permissions["test.edit"])
        assertTrue("test.edit: false" in root.text("user/noy.yml"), root.text("user/noy.yml"))
        assertFalse(store.hasPermission("noy", "test.edit"))
    }

    // --- new and vanished nodes, permissions.txt ---

    @Test
    fun aNewNodeIsAddedToEveryFileWithItsDefaultAndTheMarkerAndListedInPermissionsTxt() {
        roleWithEdit()
        run("user add noy --role cashier")
        run("user add lek")
        assertTrue("test.new" !in root.text("user/permissions.txt"))

        nodes.register(PermissionNode("test.new", "A node from a later phase", default = true))
        reloadFromDisk()

        for (file in listOf("user/noy.yml", "user/lek.yml", "user/role/cashier.yml")) {
            assertTrue("  test.new: true  # ใหม่" in root.text(file), "$file:\n${root.text(file)}")
        }
        assertTrue("test.new | default: true | A node from a later phase" in root.text("user/permissions.txt"))

        // The marker stays until someone removes it: loading again changes nothing.
        val backups = Files.walk(root.resolve("data/migration")).use { it.filter(Files::isRegularFile).count() }
        reloadFromDisk()
        assertEquals(backups, Files.walk(root.resolve("data/migration")).use { it.filter(Files::isRegularFile).count() })
        assertTrue("  test.new: true  # ใหม่" in root.text("user/lek.yml"))
        assertTrue(log.infos.none { it.endsWith(": unchanged") }, log.infos.toString())
    }

    @Test
    fun aNodeInAFileThatIsNotRegisteredAnyMoreIsKeptWithAWarning() {
        run("user add lek")
        root.write("user/lek.yml", root.text("user/lek.yml").replace("permissions:\n", "permissions:\n  gone.node: true\n"))

        reloadFromDisk()

        assertTrue(log.warningsWith("user/lek.yml", "gone.node", "not registered").isNotEmpty(), log.warnings.toString())
        assertTrue("  gone.node: true" in root.text("user/lek.yml"))
    }

    @Test
    fun permissionsTxtIsWrittenAtStartInTheServerLocaleWithEnglishAsFallback() {
        val txt = root.text("user/permissions.txt")

        assertTrue("Editing this file has no effect" in txt, txt)
        assertTrue("test.edit | default: false | Edit things" in txt, txt)
        assertTrue("test.view | default: true | ເບິ່ງ" in txt, txt)
    }

    // --- changes from the console ---

    @Test
    fun aHandEditMadeWhileTheServerRunsSurvivesAConsoleChange() {
        run("user add noy")
        handEdit("user/noy.yml", root.text("user/noy.yml").replace("display-name: \"noy\"", "display-name: \"ນ້ອຍ\"").replace("branches: []", "branches: [hand]"))

        run("perm noy test.view false")

        val text = root.text("user/noy.yml")
        assertTrue("display-name: \"ນ້ອຍ\"" in text && "branches: [hand]" in text, text)
        assertTrue("test.view: false" in text, text)
        assertTrue(log.warnings.any { "user/noy.yml was changed by hand" in it }, log.warnings.toString())
    }

    @Test
    fun aFileThatBecameUnreadableMeansNoChangeAndNoOverwrite() {
        run("user add noy")
        handEdit("user/noy.yml", "id: [broken\n : :")
        val broken = Files.readAllBytes(root.resolve("user/noy.yml"))

        val reply = run("op noy")

        assertTrue(reply.single().startsWith("user/noy.yml cannot be read now"), reply.toString())
        assertContentEquals(broken, Files.readAllBytes(root.resolve("user/noy.yml")))
    }

    @Test
    fun enableDisableAndListAndInfo() {
        run("user add noy --branch market")
        run("op noy")
        assertEquals(listOf("User 'noy' disabled"), run("user disable noy"))
        assertTrue("enabled: false" in root.text("user/noy.yml"))
        assertEquals(listOf("noy: disabled, op, role none, branches [market]"), run("user list"))
        assertEquals(listOf("User 'noy' enabled"), run("user enable noy"))

        val info = run("user info noy")

        assertEquals("User noy", info[0])
        assertTrue("  role: none" in info && "  password: none" in info && "  pin: none" in info && "    test.edit: true" in info, info.toString())
        assertEquals(listOf("No user 'ghost'"), run("user info ghost"))
    }

    // --- rename ---

    @Test
    fun renameKeepsTheIdMovesTheFileAndTouchesNothingElse() {
        run("user add noy --branch market")
        run("user add lek")
        val id = store.user("noy").id
        val noyBytes = Files.readAllBytes(root.resolve("user/noy.yml"))
        val lekBytes = Files.readAllBytes(root.resolve("user/lek.yml"))
        val lekTime = Files.getLastModifiedTime(root.resolve("user/lek.yml"))

        assertEquals(listOf("User 'noy' is now 'som' (same id)"), run("user rename noy som"))

        assertFalse(Files.exists(root.resolve("user/noy.yml")))
        assertContentEquals(noyBytes, Files.readAllBytes(root.resolve("user/som.yml")))
        assertContentEquals(lekBytes, Files.readAllBytes(root.resolve("user/lek.yml")))
        assertEquals(lekTime, Files.getLastModifiedTime(root.resolve("user/lek.yml")))
        assertEquals(id, store.user("som").id)
        assertEquals(listOf("lek", "som"), store.userNames())
        assertEquals(listOf("User 'lek' already exists"), run("user rename som lek"))
        assertTrue(run("user rename som role").single().startsWith("Invalid user name"))
        assertEquals(listOf("No user 'noy'"), run("user rename noy zzz"))
        // A change after the rename still works (the modified time came with the file).
        run("op som")
        assertTrue("op: true" in root.text("user/som.yml"))
        assertEquals(emptyList(), log.warnings)
    }

    // --- bad files ---

    @Test
    fun aPinOrPasswordThatIsNotAnArgon2idHashWarnsAndCountsAsUnusable() {
        run("user add noy")
        val hash = "\$argon2id\$v=19\$m=47104,t=1,p=1\$c29tZXNhbHQ\$aGFzaA"
        root.write("user/noy.yml", root.text("user/noy.yml").replace("pin: null", "pin: \"1234\"").replace("password: null", "password: \"$hash\""))

        reloadFromDisk()

        assertTrue(log.warningsWith("user/noy.yml", "pin is not an Argon2id hash").isNotEmpty(), log.warnings.toString())
        assertTrue(log.warningsWith("password").isEmpty())
        val file = root.text("user/noy.yml")
        assertTrue("pin: \"1234\"" in file && "password: \"$hash\"" in file, file)
        val info = run("user info noy")
        assertTrue("  pin: unusable (not an Argon2id hash)" in info && "  password: set" in info, info.toString())
        assertFalse(isUsableCredential("1234"))
        assertTrue(isUsableCredential(hash))
    }

    @Test
    fun oneCorruptFileIsSkippedWithAnErrorAndLeftByteIdenticalWhileTheOthersLoad() {
        run("user add lek")
        run("role create cashier")
        reloadFromDisk() // settle the files
        root.write("user/bad.yml", "id: [unclosed\n  : :\n")
        root.write("user/role/worse.yml", "- just\n- a list\n")
        val badBytes = Files.readAllBytes(root.resolve("user/bad.yml"))
        val worseBytes = Files.readAllBytes(root.resolve("user/role/worse.yml"))

        reloadFromDisk()

        assertEquals(listOf("lek"), store.userNames())
        assertEquals(listOf("cashier"), store.roleNames())
        assertTrue(log.errors.any { "user/bad.yml" in it && "skipped" in it }, log.errors.toString())
        assertTrue(log.errors.any { "user/role/worse.yml" in it }, log.errors.toString())
        assertContentEquals(badBytes, Files.readAllBytes(root.resolve("user/bad.yml")))
        assertContentEquals(worseBytes, Files.readAllBytes(root.resolve("user/role/worse.yml")))
    }

    @Test
    fun filesWithAnInvalidNameAreIgnoredWithAWarning() {
        root.write("user/Bad Name.yml", "file-version: 1\n")
        root.write("user/role.yml", "file-version: 1\n")
        root.write("user/role/none.yml", "file-version: 1\n")

        reloadFromDisk()

        assertEquals(emptyList(), store.userNames() + store.roleNames())
        assertEquals(3, log.warnings.count { "not a valid name" in it }, log.warnings.toString())
    }

    @Test
    fun aFileFromANewerServerIsSkippedAndKept() {
        run("user add lek")
        val newer = root.text("user/lek.yml").replace("file-version: 1", "file-version: 2")
        root.write("user/lek.yml", newer)

        reloadFromDisk()

        assertEquals(emptyList(), store.userNames())
        assertTrue(log.errors.any { "user/lek.yml has file-version 2" in it }, log.errors.toString())
        assertEquals(newer, root.text("user/lek.yml"))
    }

    @Test
    fun anOldVersionRunsTheMigrationStepsThroughTheFileVersionKey() {
        root.write("user/lek.yml", "file-version: 1\nname: Old Name\nrole: none\n")
        // A test-only version 2 whose step renamed `name` to `display-name`.
        val file = UserFile("lek", emptyMap(), nodes.all(), "lo", currentVersion = 2, steps = listOf(
            MigrationStep(1) { m -> m - "name" + ("display-name" to m["name"]) },
        ))

        val loaded = loadConfigFile(root, file, log, FIXED_CLOCK)

        assertEquals("Old Name", loaded.value.displayName)
        assertTrue(content("user/lek.yml").first() == "file-version: 2" && "display-name: \"Old Name\"" in root.text("user/lek.yml"))
        assertEquals(listOf("$FIXED_STAMP/user/lek.yml"), root.backups())
    }

    @Test
    fun aHandWrittenMinimalFileIsFilledInWithAnIdAndTheDefaults() {
        root.write("user/lek.yml", "role: none\n")

        reloadFromDisk()

        val text = root.text("user/lek.yml")
        assertTrue(Regex("id: [0-9a-f-]{36}").containsMatchIn(text), text)
        assertTrue("display-name: \"lek\"" in text && "enabled: true" in text && "op: false" in text, text)
        assertTrue("  test.view: true  # ใหม่" in text, text)
        assertTrue(log.warningsWith("id is missing").isNotEmpty())
    }

    @Test
    fun aMisspelledEnabledOrOpDoesNotEnableOrPromoteAnyone() {
        run("user add lek")
        root.write("user/lek.yml", root.text("user/lek.yml").replace("enabled: true", "enabled: flase").replace("op: false", "op: yes"))

        reloadFromDisk()

        assertFalse(store.user("lek").enabled)
        assertFalse(store.user("lek").op)
        assertEquals(2, log.warnings.count { "invalid value" in it })
    }

    @Test
    fun aPermissionValueThatIsNotTrueOrFalseIsDeniedInAUserFileAndWrittenBackAsFalse() {
        run("user add lek")
        root.write("user/lek.yml", root.text("user/lek.yml").replace("test.view: true", "test.view: flase"))

        reloadFromDisk()

        assertFalse(store.hasPermission("lek", "test.view"))
        assertTrue(log.warningsWith("user/lek.yml", "test.view", "flase").isNotEmpty(), log.warnings.toString())
        val text = root.text("user/lek.yml")
        assertTrue("  test.view: false" in text && "flase" !in text && "test.view: false  # ใหม่" !in text, text)
        assertEquals(listOf("$FIXED_STAMP/user/lek.yml"), root.backups())
    }

    @Test
    fun aPermissionValueThatIsNotTrueOrFalseIsDeniedInARoleFileAndSoForItsMembers() {
        run("role create cashier")
        run("user add noy --role cashier")
        root.write("user/role/cashier.yml", root.text("user/role/cashier.yml").replace("test.view: true", "test.view: flase"))

        reloadFromDisk()

        assertFalse(store.hasPermission("noy", "test.view"))
        assertTrue(log.warningsWith("user/role/cashier.yml", "test.view", "flase").isNotEmpty(), log.warnings.toString())
        for (file in listOf("user/role/cashier.yml", "user/noy.yml")) {
            val text = root.text(file)
            assertTrue("  test.view: false" in text && "flase" !in text, "$file:\n$text")
        }
    }

    @Test
    fun aPermissionNodeThatIsReallyMissingStillGetsItsDefaultMarkedNew() {
        run("user add lek")
        run("role create cashier")
        for (file in listOf("user/lek.yml", "user/role/cashier.yml")) {
            root.write(file, root.text(file).lines().filterNot { "test.view" in it }.joinToString("\n"))
        }

        reloadFromDisk()

        assertTrue(store.hasPermission("lek", "test.view"))
        for (file in listOf("user/lek.yml", "user/role/cashier.yml")) {
            assertTrue("  test.view: true  # ใหม่" in root.text(file), "$file:\n${root.text(file)}")
        }
    }

    // --- console details ---

    @Test
    fun permSearchFindsNodesByNameOrDescription() {
        assertEquals(listOf("test.edit (default false) - Edit things"), run("perm search edit"))
        assertEquals(listOf("test.view (default true) - View things"), run("perm search VIEW"))
        assertEquals(listOf("No permission node matches 'zzz'"), run("perm search zzz"))
        run("user add noy")
        assertEquals(listOf("Unknown permission node 'nope' (find one with: perm search <keyword>)"), run("perm noy nope true"))
        assertEquals(listOf("Expected true or false, not 'yes'"), run("perm noy test.edit yes"))
        assertEquals(listOf("Usage: perm <user> <node> true|false   or   perm search <keyword>"), run("perm noy"))
    }

    @Test
    fun aUserCalledSearchCanStillGetAPermission() {
        run("user add search")

        run("perm search test.edit true")

        assertTrue("test.edit: true" in root.text("user/search.yml"))
    }

    @Test
    fun tabCompletionOffersUserNamesRoleNamesAndNodeNames() {
        roleWithEdit()
        run("user add noy")
        run("user add nok")

        assertEquals(listOf("noy", "nok").sorted(), commands.complete(listOf("user", "info", "no")).sorted())
        assertEquals(listOf("cashier"), commands.complete(listOf("user", "role", "noy", "ca")))
        assertEquals(listOf("none"), commands.complete(listOf("user", "role", "noy", "no")))
        assertEquals(listOf("cashier"), commands.complete(listOf("user", "add", "zed", "--role", "c")))
        assertEquals(listOf("test.edit"), commands.complete(listOf("perm", "noy", "test.e")))
        assertEquals(listOf("true"), commands.complete(listOf("perm", "noy", "test.edit", "t")))
        assertEquals(listOf("search"), commands.complete(listOf("perm", "s")))
        assertEquals(listOf("cashier"), commands.complete(listOf("role", "perm", "c")))
        assertEquals(listOf("noy"), commands.complete(listOf("op", "noy")))
        assertEquals(listOf("users"), commands.complete(listOf("reload", "u")))
    }

    @Test
    fun reloadWithAnUnknownTargetShowsUsage() {
        assertEquals(listOf("Usage: reload [users]"), run("reload nothing"))
    }

    // --- file permissions ---

    @Test
    fun onAPosixFilesystemTheUserFolderIsOwnerOnly() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews(), "no POSIX permissions on this filesystem")
        run("role create cashier")
        run("user add noy")

        fun mode(relative: String) = PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(relative)))
        assertEquals("rwx------", mode("user"))
        assertEquals("rwx------", mode("user/role"))
        for (file in listOf("user/noy.yml", "user/role/cashier.yml", "user/permissions.txt")) assertEquals("rw-------", mode(file), file)
    }
}
