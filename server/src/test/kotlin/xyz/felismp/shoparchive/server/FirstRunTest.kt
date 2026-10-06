package xyz.felismp.shoparchive.server

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.auth.NO_BACKOFF
import xyz.felismp.shoparchive.server.auth.enroll
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the first start makes (`setup.first-branch`, `setup.first-user`) and the pairing it prints. */
class FirstRunTest {
    @TempDir
    lateinit var root: Path

    private fun env(setup: String = "") = AuthEnv(root, NO_BACKOFF + setup, withRecords = true)

    /** Runs the first-run step like a start would; returns the names it showed a pairing for. */
    private fun AuthEnv.start(): List<String> {
        val shown = mutableListOf<String>()
        firstRun(settings, users, records!!.branches, auth.devices) { _, name, _ -> shown += name }
        return shown
    }

    @Test
    fun theFirstStartMakesTheBranchAndTheOpOwnerAndPairsThemOnceAndNeverMakesThemAgain() = env().run {
        assertEquals(listOf("owner"), start())

        assertEquals(listOf("main" to "Main"), records!!.branches.all().map { it.key to it.displayName })
        assertEquals(listOf("owner"), users.userNames())
        assertTrue(users.user("owner").op)
        assertEquals(listOf("main"), users.user("owner").branches)

        // The next start: nothing new is made, but the owner has no device yet, so there is a new pairing to scan.
        assertEquals(listOf("owner"), start())
        assertEquals(1, records.branches.all().size)
        assertEquals(listOf("owner"), users.userNames())
    }

    @Test
    fun onceTheOwnerHasADeviceNothingIsMadeOrShownAtAStart() = env().run {
        start()
        enroll("owner")

        assertEquals(emptyList(), start())
        assertEquals(listOf("owner"), users.userNames())
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

        assertEquals(listOf("boss"), start())
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
        assertEquals(listOf("owner"), start())
        assertEquals(emptyList(), records!!.branches.all())
        assertEquals(emptyList(), users.user("owner").branches)
    }

    @Test
    fun aNameThatCannotBeMadeIsLoggedAndTheServerGoesOn() = env("setup:\n  first-user: \"Ab\"\n").run {
        assertEquals(emptyList(), start())
        assertEquals(emptyList(), users.userNames())
    }
}
