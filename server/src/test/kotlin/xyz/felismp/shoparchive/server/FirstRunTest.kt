package xyz.felismp.shoparchive.server

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.server.users.isUsableCredential
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.LoginRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the first start makes (`setup.first-branch`, `setup.first-user`) and the owner's name and PIN it prints. */
class FirstRunTest {
    @TempDir
    lateinit var root: Path

    /** The shipped password rule (the PIN alone), no backoff unless [backoff] says so, and [setup] after it. */
    private fun env(setup: String = "", backoff: Int = 0, pinLength: Int = 6) = AuthEnv(
        root, "config-version: 1\nauth:\n  password:\n    required-for: []\n  pin:\n    length: $pinLength\n  backoff:\n    start-seconds: $backoff\n$setup", withRecords = true,
    )

    /** Runs the first-run steps like a start would; returns the lines it printed. */
    private fun AuthEnv.start(out: (String) -> Unit = {}): List<String> {
        val printed = mutableListOf<String>()
        printFirstRun(firstRunSetup(settings, users, records!!.branches, auth.devices, auth.hasher, auth.sessions)) { printed += it; out(it) }
        return printed
    }

    /** The name and PIN in what a start printed; null if it printed nothing. */
    private fun List<String>.ownerLogin(): Pair<String, String>? {
        if (isEmpty()) return null
        val match = assertNotNull(Regex("Owner login: user '(\\S+)'  PIN (\\d+)").find(joinToString("\n")), toString())
        return match.groupValues[1] to match.groupValues[2]
    }

    private suspend fun ApplicationTestBuilder.login(name: String, pin: String? = null, newPin: String? = null): HttpResponse =
        postJson("/api/v1/login", LoginRequest.serializer(), LoginRequest(name, "Phone of $name", "android", DeviceMode.PERSONAL, pin = pin, newPin = newPin))

    @Test
    fun theFirstStartMakesTheBranchAndTheOpOwnerAndPrintsAPinThatLogsIn() = env().run {
        val printed = start()

        assertEquals(listOf("main" to "Main"), records!!.branches.all().map { it.key to it.displayName })
        assertEquals(listOf("owner"), users.userNames())
        assertTrue(users.user("owner").op)
        assertEquals(listOf("main"), users.user("owner").branches)
        val (name, pin) = assertNotNull(printed.ownerLogin())
        assertEquals("owner", name)
        assertTrue(Regex("\\d{6}").matches(pin), pin)
        assertEquals(1, Regex("PIN \\d").findAll(printed.joinToString("\n")).count(), printed.toString())
        assertEquals(
            listOf(
                "Owner login: user 'owner'  PIN $pin",
                "Open the app, pick this server and log in with these. A new PIN is made at every start until the owner has logged in.",
            ),
            printed,
        )
        api {
            val response = login("owner", pin = pin)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
    }

    @Test
    fun thePinHasTheConfiguredLength() = env(pinLength = 9).run {
        val (_, pin) = assertNotNull(start().ownerLogin())

        assertTrue(Regex("\\d{9}").matches(pin), pin)
    }

    @Test
    fun theOwnerHasAPinBeforeAnyRequestCouldComeIn() = env().run {
        // The setup alone, as Main runs it before the network starts: nobody can claim the account by setting a PIN of their own.
        val owner = assertNotNull(firstRunSetup(settings, users, records!!.branches, auth.devices, auth.hasher, auth.sessions))

        assertEquals("owner", owner.name)
        assertTrue(isUsableCredential(users.user("owner").pin))
        api {
            assertEquals(HttpStatusCode.Unauthorized, login("owner", newPin = "123456").status)
            assertEquals(HttpStatusCode.OK, login("owner", pin = owner.pin).status)
        }
    }

    @Test
    fun everyStartBeforeTheOwnerLogsInMakesANewPinAndTheOldOneStopsWorking() = env().run {
        val (_, first) = assertNotNull(start().ownerLogin())

        // The next start: nothing new is made, but the owner has no device yet, so there is a new PIN.
        val (_, second) = assertNotNull(start().ownerLogin())

        assertEquals(1, records!!.branches.all().size)
        assertEquals(listOf("owner"), users.userNames())
        api {
            if (first != second) assertEquals(HttpStatusCode.Unauthorized, login("owner", pin = first).status)
            assertEquals(HttpStatusCode.OK, login("owner", pin = second).status)
        }
    }

    @Test
    fun onceTheOwnerHasLoggedInNothingIsMadeOrPrintedAndThePinStays() = env().run {
        val (_, pin) = assertNotNull(start().ownerLogin())
        api { assertEquals(HttpStatusCode.OK, login("owner", pin = pin).status) }
        val stored = users.user("owner").pin

        assertEquals(emptyList(), start())
        assertEquals(stored, users.user("owner").pin)
        assertEquals(listOf("owner"), users.userNames())
    }

    @Test
    fun aLockedOwnerCanLogInWithTheNewPinRightAfterARestart() = env(backoff = 30).run {
        val (_, old) = assertNotNull(start().ownerLogin())
        val wrong = if (old == "000000") "111111" else "000000"
        api {
            assertEquals(HttpStatusCode.Unauthorized, login("owner", pin = wrong).status)
            assertEquals(HttpStatusCode.TooManyRequests, login("owner", pin = old).status)
        }

        val (_, pin) = assertNotNull(start().ownerLogin())

        assertEquals(0, users.user("owner").failedLogins)
        assertNull(users.user("owner").lockedUntil)
        api { assertEquals(HttpStatusCode.OK, login("owner", pin = pin).status) }
    }

    @Test
    fun withUsersAlreadyThereNothingIsMadeAndAnOwnerThatIsMissingIsNotMadeLater() = env().run {
        addUser("mali")

        assertEquals(emptyList(), start())
        assertNull(users.find("owner"))
        assertEquals(emptyList(), records!!.branches.all())
    }

    @Test
    fun theNamesComeFromTheConfigAndABranchThatExistsIsKept() = env("setup:\n  first-branch: shop\n  first-user: boss\n").run {
        records!!.branches.add("shop", "My Shop")

        assertEquals("boss", start().ownerLogin()?.first)
        assertEquals(listOf("shop" to "My Shop"), records.branches.all().map { it.key to it.displayName })
        assertEquals(listOf("shop"), users.user("boss").branches)
    }

    @Test
    fun anEmptyNameMeansDoNothing() {
        env("setup:\n  first-user: \"\"\n").run {
            assertEquals(emptyList(), start())
            assertEquals(emptyList(), users.userNames())
            assertEquals(emptyList(), records!!.branches.all())
        }
    }

    @Test
    fun anEmptyBranchMakesTheOwnerWithoutOne() = env("setup:\n  first-branch: \"\"\n").run {
        assertEquals("owner", start().ownerLogin()?.first)
        assertEquals(emptyList(), records!!.branches.all())
        assertEquals(emptyList(), users.user("owner").branches)
    }

    /** A start in Main's order: the loads without the empty-server hints, the setup, then the hints for what is still missing; returns those hints. */
    private fun AuthEnv.hintsOfAStart(): List<String> {
        log.infos.clear()
        users.loadWithoutHint()
        records!!.loadWithoutHint()
        start()
        users.hintIfEmpty()
        records.hintIfEmpty()
        return log.infos.filter { it.startsWith("No users yet") || it.startsWith("No branches yet") }
    }

    @Test
    fun aFirstStartThatMakesTheOwnerAndTheBranchDoesNotSayHowToMakeThem() = env().run {
        assertEquals(emptyList(), hintsOfAStart())
    }

    @Test
    fun aFirstStartWithTheSetupOffStillSaysHowToMakeTheFirstUserAndBranch() = env("setup:\n  first-user: \"\"\n  first-branch: \"\"\n").run {
        val hints = hintsOfAStart()

        assertEquals(2, hints.size, hints.toString())
        assertTrue(hints[0].startsWith("No users yet. Create the first one with: user add <name>"), hints.toString())
        assertEquals("No branches yet. Add the first one with: branch add main Main", hints[1])
    }

    @Test
    fun aFirstStartWithoutAFirstBranchStillSaysHowToAddOne() = env("setup:\n  first-branch: \"\"\n").run {
        assertEquals(listOf("No branches yet. Add the first one with: branch add main Main"), hintsOfAStart())
    }

    @Test
    fun aNameThatCannotBeMadeIsLoggedAndTheServerGoesOn() = env("setup:\n  first-user: \"Ab\"\n").run {
        assertEquals(emptyList(), start())
        assertEquals(emptyList(), users.userNames())
    }

    @Test
    fun thePinIsNeverWrittenToLogs() = env(pinLength = 12).run {
        Log.start(root)
        val (_, pin) = try {
            // The real printer, with a copy kept here to know what the PIN was.
            assertNotNull(start(Log::terminalOnly).ownerLogin()).also { Log.info("a line after the first run") }
        } finally {
            Log.close()
        }

        val logs = Files.list(root.resolve("logs")).use { files ->
            files.toList().joinToString("\n") { f ->
                String(if (f.fileName.toString().endsWith(".gz")) GZIPInputStream(Files.newInputStream(f)).use { it.readBytes() } else Files.readAllBytes(f))
            }
        }
        assertTrue("a line after the first run" in logs, logs)
        assertTrue("First start: user 'owner' made" in logs, logs)
        assertFalse(pin in logs || "Owner login" in logs, logs)
        assertNotEquals(pin, users.user("owner").pin)
    }
}
