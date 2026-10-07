package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.Log
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the console says about users and connected apps. */
class AuthConsoleTest {
    @TempDir
    lateinit var root: Path

    @AfterTest
    fun tearDown() = Log.close()

    private fun env(config: String? = null) = AuthEnv(root, config)

    @Test
    fun userAddCreatesTheUserAndTellsHowTheyLogIn() = env().run {
        val replies = console("user add noy")

        assertEquals(listOf("User 'noy' created. They open the app, type the name 'noy' and set their own PIN."), replies)
        assertTrue(users.userNames().contains("noy"))
    }

    @Test
    fun anUnknownUserSubcommandAnswersWithTheUsageLine() = env().run {
        addUser("mali")

        val usage = listOf("Usage: user add|list|info|enable|disable|unlock|reset|rename|role|branch ...")
        assertEquals(usage, console("user nonsense mali"))
        assertEquals(usage, console("user"))
        assertEquals(listOf("add"), commands.complete(listOf("user", "a")))
    }

    @Test
    fun sayWithNobodyConnectedSaysSo() = env().run {
        assertEquals(listOf("Sent to 0 connected apps"), console("say hello"))
        assertEquals(listOf("Usage: say <message>"), console("say"))
    }

    @Test
    fun theFirstRunHintNamesTheThreeSteps() {
        val env = env()

        assertTrue(env.log.infos.any { "user add <name>" in it && "op <name>" in it && "set their own PIN" in it }, env.log.infos.toString())
    }
}
