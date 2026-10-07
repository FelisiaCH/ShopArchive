package xyz.felismp.shoparchive.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.TEST_PIN
import xyz.felismp.shoparchive.server.auth.api
import xyz.felismp.shoparchive.server.auth.authService
import xyz.felismp.shoparchive.server.auth.errorCode
import xyz.felismp.shoparchive.server.auth.errorReason
import xyz.felismp.shoparchive.server.auth.parsed
import xyz.felismp.shoparchive.server.auth.postJson
import xyz.felismp.shoparchive.server.records.login
import xyz.felismp.shoparchive.shared.CommandRequest
import xyz.felismp.shoparchive.shared.CommandResponse
import xyz.felismp.shoparchive.shared.CompleteRequest
import xyz.felismp.shoparchive.shared.CompleteResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.ReauthRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The console in the app: `POST /api/v1/command` and `/command/complete`. */
class CommandServiceTest {
    @TempDir
    lateinit var root: Path

    private fun env(config: String = NO_BACKOFF) = AuthEnv(root, config)

    /** A user in the app as the command service sees one, for what is still shown to such a sender without a command. */
    private class AppUser(override val principal: Principal) : RemoteSender {
        val lines = mutableListOf<String>()
        override val ip = "127.0.0.1"
        override val name get() = principal.username
        override fun sendMessage(message: String) {
            lines += message
        }
    }

    private fun AuthEnv.appUser(token: String) = AppUser(authService().authenticate(token)!!)

    private suspend fun ApplicationTestBuilder.runLine(line: String, token: String) =
        postJson("/api/v1/command", CommandRequest.serializer(), CommandRequest(line), token)

    private suspend fun ApplicationTestBuilder.completeLine(line: String, token: String) =
        postJson("/api/v1/command/complete", CompleteRequest.serializer(), CompleteRequest(line), token)

    private suspend fun ApplicationTestBuilder.candidates(line: String, token: String) =
        completeLine(line, token).parsed(CompleteResponse.serializer()).candidates

    @Test
    fun aUserWithTheNodeOfACommandRunsItAndGetsWhatItSaid() = env().run {
        val noy = login("noy", grant = listOf("shoparchive.command.version"))
        api {
            val answer = runLine("version", noy)

            assertEquals(HttpStatusCode.OK, answer.status)
            assertEquals(listOf("ShopArchive ${coreVersion()}"), answer.parsed(CommandResponse.serializer()).lines)
        }
    }

    @Test
    fun aUserWithoutTheNodeIsRefusedAndTheCommandDoesNotRun() = env().run {
        val noy = login("noy", grant = listOf("shoparchive.command.version"))
        api {
            val refused = runLine("user add zed", noy)

            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals(ErrorCode.FORBIDDEN, refused.errorCode())
            assertEquals(ErrorReasons.PERMISSION_MISSING, refused.errorReason())
            assertNull(users.find("zed"))
        }
    }

    @Test
    fun anOpHoldsEveryCommandNodeButOpAndDeopAreNeverRunOverHttp() = env().run {
        val boss = login("boss", op = true)
        addUser("noy")
        api {
            for (line in listOf("op noy", "deop boss", "OP noy")) {
                val refused = runLine(line, boss)
                assertEquals(HttpStatusCode.Forbidden, refused.status, line)
                assertEquals(ErrorReasons.COMMAND_CONSOLE_ONLY, refused.errorReason(), line)
            }
            assertFalse(users.user("noy").op)
            assertTrue(users.user("boss").op)
            assertEquals(emptyList(), candidates("deo", boss), "deop is not offered")
            assertEquals(HttpStatusCode.OK, runLine("user add zed", boss).status)
            assertNotNull(users.find("zed"))
            assertFalse(audit().contains("result=op_noy"), "a refused line is not a run")
        }
    }

    @Test
    fun everyRunIsWrittenToTheAuditLogWithTheUserAndTheLine() = env().run {
        val noy = login("noy", grant = listOf("shoparchive.command.help"))
        api {
            runLine("help", noy)
            runLine("version", noy) // refused: not a run

            val lines = audit().lines().filter { it.contains("command.run") }
            assertEquals(1, lines.size, lines.toString())
            assertContains(lines.single(), " command.run user=noy device=")
            assertTrue(lines.single().endsWith("result=help"), lines.single())
        }
    }

    @Test
    fun theCommandNeedsAPinEnteredRecentlyLikeAnExport() = env().run {
        val noy = login("noy", grant = listOf("shoparchive.command.version"))
        api {
            clock.advance(Duration.ofMinutes(6))
            val asked = runLine("version", noy)
            assertEquals(HttpStatusCode.Unauthorized, asked.status)
            assertEquals(ErrorCode.REAUTH_REQUIRED, asked.errorCode())
            assertEquals(HttpStatusCode.Unauthorized, completeLine("ve", noy).status)
            assertFalse("command.run" in audit())

            postJson("/api/v1/reauth", ReauthRequest.serializer(), ReauthRequest(pin = TEST_PIN), noy)
            assertEquals(HttpStatusCode.OK, runLine("version", noy).status)
        }
    }

    @Test
    fun completionOffersOnlyTheCommandsTheUserMayRunAndTheirOwnWords() = env().run {
        val noy = login("noy", grant = listOf("shoparchive.command.help", "shoparchive.command.version"))
        api {
            assertEquals(listOf("help", "version"), candidates("", noy))
            assertEquals(listOf("version"), candidates("ve", noy))
            assertEquals(HttpStatusCode.Forbidden, completeLine("perm ", noy).status)
        }
    }

    @Test
    fun theCommandsThatManageAccountsNeedAnOpWhateverNodeTheUserHolds() = env().run {
        val owner = login("owner", op = true)
        val noy = login("noy", grant = listOf("user", "perm", "role", "devices").map { "shoparchive.command.$it" })
        addUser("mali")
        api {
            val before = filesUnder("data").filterKeys { "audit" !in it }
            for (line in listOf("user reset owner", "user disable owner", "perm noy shoparchive.command.stop true", "role list", "devices list owner")) {
                val refused = runLine(line, noy)
                assertEquals(HttpStatusCode.Forbidden, refused.status, line)
                assertEquals(ErrorReasons.PERMISSION_MISSING, refused.errorReason(), line)
            }
            assertEquals(HttpStatusCode.Forbidden, completeLine("user info ", noy).status)
            assertEquals(emptyList(), candidates("", noy), "not offered to a non-op")
            assertEquals(0, auth.pairing.pendingCount())
            assertEquals(1, auth.devices.devicesOf(users.user("owner").id).size, "the owner's device is still there")
            assertEquals(before, filesUnder("data").filterKeys { "audit" !in it }, "nothing changed")
            assertFalse("command.run" in audit())
            login("owner", op = true) // still signs in with the old password
            assertTrue("user" in candidates("", owner))
        }
    }

    @Test
    fun userPairFromTheAppIsGoneAndMakesNoPairing() = env().run {
        val boss = login("boss", op = true)
        addUser("mali")
        api {
            val lines = runLine("user pair mali", boss).parsed(CommandResponse.serializer()).lines

            assertEquals(listOf("Usage: user add|list|info|enable|disable|unlock|reset|rename|role|branch ..."), lines)
            assertEquals(0, auth.pairing.pendingCount())
            assertTrue(terminal.isEmpty())
            assertFalse("pair" in candidates("user ", boss))
        }
    }

    @Test
    fun aPairingShownToAUserInTheAppAnswersWithTheLinkAndTheCodeAndTheLogAndTheTerminalHoldNeither() = env().run {
        val boss = appUser(login("boss", op = true))
        addUser("mali")

        pairingConsole.show(boss, "mali", png = false)

        assertTrue(boss.lines.any { it.startsWith("Link: shoparchive://pair?d=") }, boss.lines.toString())
        assertTrue(boss.lines.any { it.startsWith("Manual code: ") })
        assertTrue(terminal.isEmpty(), "nothing is printed on the server's own terminal")
        assertFalse("shoparchive://" in audit() || "Manual" in audit())
        assertEquals(1, auth.pairing.pendingCount())
        assertContains(audit(), "source=admin,by=boss")
    }

    @Test
    fun anOpCannotResetTheirOwnAccountFromTheAppAndSoLockThemselvesOut() = env().run {
        val boss = login("boss", op = true)
        api {
            val lines = runLine("user reset boss", boss).parsed(CommandResponse.serializer()).lines

            assertEquals(listOf("Your own account can only be reset on the server console."), lines)
            assertEquals(1, auth.devices.devicesOf(users.user("boss").id).size, "the device is still there")
            assertEquals(HttpStatusCode.OK, runLine("help", boss).status, "the session still works")
        }
    }

    @Test
    fun aPairingShownToAUserInTheAppFollowsThePairingSourcesSettingLikeThePairingRoute() = env("config-version: 1\nauth:\n  pairing:\n    sources: [console]\n").run {
        val boss = appUser(login("boss", op = true))
        addUser("mali")

        pairingConsole.show(boss, "mali", png = false)

        assertEquals(listOf("Pairing from the admin is turned off (auth.pairing.sources)."), boss.lines)
        assertEquals(0, auth.pairing.pendingCount())
        assertEquals(1, showPairing("mali").size, "the console is still allowed")
        assertEquals(1, auth.pairing.pendingCount())
    }

    @Test
    fun aCommandThatThrowsAnswersThatItFailedAndTheServerCarriesOn() = env().run {
        val noy = login("noy", grant = listOf("shoparchive.command.version"))
        commands.register(object : Command {
            override val name = "boom"
            override val description = "Always fails"
            override val permission = "shoparchive.command.version"
            override fun execute(sender: CommandSender, args: List<String>): Unit = throw IllegalStateException("no")
        }, "test")
        api {
            assertEquals(listOf("The command failed; the server log says why."), runLine("boom", noy).parsed(CommandResponse.serializer()).lines)
            assertEquals(HttpStatusCode.OK, runLine("version", noy).status)
        }
    }

    @Test
    fun everyCoreCommandAnAppUserMayRunHasItsNodeRegisteredAndOffByDefault() = env().run {
        val missing = commands.all().filter { it.name !in CONSOLE_ONLY_COMMANDS }.filter { command -> nodes.all().none { it.node == command.permission } }
        assertEquals(emptyList(), missing.map { it.name })
        assertTrue(nodes.all().filter { it.node.startsWith("shoparchive.command.") }.none { it.default })
        assertEquals("shoparchive.command.user", commands.find("user")!!.permission)
    }
}
