package xyz.felismp.shoparchive.server.auth

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.config.RecordingLog
import xyz.felismp.shoparchive.shared.DeviceMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceStoreTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()
    private val clock = TestClock()
    private lateinit var store: DeviceStore

    @BeforeTest
    fun setUp() {
        store = DeviceStore(root, log, clock).also { it.load() }
    }

    private fun reloaded() = DeviceStore(root, RecordingLog(), clock).also { it.load() }

    private fun fileOf(id: String) = root.resolve("data/devices/$id.yml")

    private val mali = "aaaaaaaa-0000-4000-8000-000000000001"
    private val kham = "aaaaaaaa-0000-4000-8000-000000000002"

    @Test
    fun aDeviceFileHoldsTheLabelModeAndOneBlockPerUserWithOnlyHashes() {
        val id = store.create("Mali's \"phone\"", "android", DeviceMode.SHARED, "mali", mali, "the-credential")

        val text = Files.readString(fileOf(id))
        assertContains(text, "file-version: 1")
        assertContains(text, "label: \"Mali's \\\"phone\\\"\"")
        assertContains(text, "platform: \"android\"")
        assertContains(text, "mode: shared")
        assertContains(text, "created: 2026-10-03T08:00:00Z")
        assertContains(text, "  mali:\n    credential-sha256: ${sha256Hex("the-credential")}\n    user-id: $mali\n    created: 2026-10-03T08:00:00Z\n    last-used: 2026-10-03T08:00:00Z\n    pin-failures: 0")
        assertFalse("the-credential" in text)
    }

    @Test
    fun whatWasWrittenIsReadBackAfterARestart() {
        val id = store.create("Phone", "android", DeviceMode.PERSONAL, "mali", mali, "c1")
        store.putUser(id, "kham", kham, "c2")
        clock.advance(java.time.Duration.ofMinutes(5))
        store.recordFailure(id, "kham", kham, 10)

        val again = reloaded()

        assertEquals(store.get(id), again.get(id))
        assertEquals(1, again.get(id)!!.users["kham"]!!.pinFailures)
        assertTrue(again.credentialMatches(id, "mali", mali, "c1"))
        assertEquals(kham, again.ownerOf(id, "c2"))
        assertFalse(again.credentialMatches(id, "mali", mali, "c2"))
        assertFalse(again.credentialMatches(id, "mali", kham, "c1"), "the same name and credential, another account")
        assertNull(again.ownerOf(id, "nope"))
        assertEquals(1, log.infos.count { "devices loaded" in it })
    }

    @Test
    fun aSuccessResetsTheCountAndTheLastFailureRemovesTheUserAndOnlyThatUser() {
        val id = store.create("Phone", "android", DeviceMode.SHARED, "mali", mali, "c1")
        store.putUser(id, "kham", kham, "c2")

        assertFalse(store.recordFailure(id, "mali", mali, 3))
        assertFalse(store.recordFailure(id, "mali", mali, 3))
        store.recordSuccess(id, "mali", mali)
        assertEquals(0, store.get(id)!!.users["mali"]!!.pinFailures)
        assertFalse(store.recordFailure(id, "mali", mali, 3))
        assertFalse(store.recordFailure(id, "mali", mali, 3))
        assertTrue(store.recordFailure(id, "mali", mali, 3))

        assertFalse(store.hasUser(id, "mali", mali))
        assertTrue(store.hasUser(id, "kham", kham))
        assertFalse(reloaded().hasUser(id, "mali", mali))
        assertTrue(reloaded().credentialMatches(id, "kham", kham, "c2"))
    }

    @Test
    fun aFileThatCannotBeReadIsSkippedAndLeftAsItIs() {
        val bad = "00000000-0000-4000-8000-000000000001"
        Files.writeString(fileOf(bad), "file-version: 1\nlabel: [unclosed\n")
        Files.writeString(root.resolve("data/devices/not-an-id.yml"), "x: 1\n")
        val errors = RecordingLog()

        val again = DeviceStore(root, errors, clock).also { it.load() }

        assertNull(again.get(bad))
        assertEquals(0, again.count())
        assertEquals("file-version: 1\nlabel: [unclosed\n", Files.readString(fileOf(bad)))
        assertTrue(errors.errors.any { bad in it }, errors.errors.toString())
        assertTrue(errors.warnings.any { "not-an-id" in it })
    }

    @Test
    fun anEntryWithoutAValidHashIsDroppedWithAWarningSoThatUserIsOffTheDevice() {
        val id = "00000000-0000-4000-8000-000000000002"
        Files.writeString(
            fileOf(id),
            "file-version: 1\nlabel: \"x\"\nplatform: \"y\"\nmode: shared\ncreated: 2026-10-03T08:00:00Z\nusers:\n  mali:\n    credential-sha256: nonsense\n    user-id: $mali\n" +
                "  kham:\n    credential-sha256: ${sha256Hex("c")}\n    user-id: $kham\n    created: 2026-10-03T08:00:00Z\n    last-used: 2026-10-03T08:00:00Z\n    pin-failures: 2\n",
        )
        val warnings = RecordingLog()

        val again = DeviceStore(root, warnings, clock).also { it.load() }

        assertEquals(setOf("kham"), again.get(id)!!.users.keys)
        assertEquals(2, again.get(id)!!.users["kham"]!!.pinFailures)
        assertTrue(warnings.warnings.any { "users.mali" in it })
    }

    @Test
    fun aDamagedModeReadsAsPersonalSoNoMoreUsersCanBeAdded() {
        val id = "00000000-0000-4000-8000-000000000003"
        Files.writeString(fileOf(id), "file-version: 1\nlabel: \"x\"\nplatform: \"y\"\nmode: everyone\ncreated: 2026-10-03T08:00:00Z\nusers: {}\n")

        val again = DeviceStore(root, RecordingLog(), clock).also { it.load() }

        assertEquals(DeviceMode.PERSONAL, again.get(id)!!.mode)
    }

    @Test
    fun aCredentialThatIsNotShortIsNotHashed() {
        val id = store.create("Phone", "android", DeviceMode.SHARED, "mali", mali, "c1")

        assertFalse(store.credentialMatches(id, "mali", mali, "c1".padEnd(100_000, 'x')))
        assertNull(store.ownerOf(id, "c1".padEnd(100_000, 'x')))
    }

    private fun deviceWithTwoUsers(): String {
        val id = store.create("Phone", "android", DeviceMode.SHARED, "mali", mali, "c1")
        store.putUser(id, "kham", kham, "c2")
        return id
    }

    @Test
    fun aWriteGoesOnTopOfAHandEditedFileAndNeverBringsARemovedBlockBack() {
        val id = deviceWithTwoUsers()
        removeUserBlock(fileOf(id), "mali")

        store.recordSuccess(id, "kham", kham)

        assertFalse("  mali:" in Files.readString(fileOf(id)))
        assertFalse(store.hasUser(id, "mali", mali))
        assertFalse(store.credentialMatches(id, "mali", mali, "c1"))
        assertNull(store.ownerOf(id, "c1"))
        assertTrue(store.credentialMatches(id, "kham", kham, "c2"))
        assertTrue(store.putUser(id, "noy", "aaaaaaaa-0000-4000-8000-000000000003", "c3"))
        assertEquals(setOf("kham", "noy"), reloaded().get(id)!!.users.keys)
    }

    @Test
    fun aFileThatCannotBeReadNowIsADeviceWithoutUsersAndIsNeverOverwritten() {
        val id = deviceWithTwoUsers()
        val good = Files.readString(fileOf(id))
        Files.writeString(fileOf(id), "file-version: 1\nlabel: [unclosed\n")
        Files.setLastModifiedTime(fileOf(id), FileTime.fromMillis(Files.getLastModifiedTime(fileOf(id)).toMillis() + 5_000))

        assertFalse(store.hasUser(id, "kham", kham))
        assertFalse(store.credentialMatches(id, "kham", kham, "c2"))
        assertFalse(store.putUser(id, "noy", "aaaaaaaa-0000-4000-8000-000000000003", "c3"))
        store.recordSuccess(id, "kham", kham)
        assertFalse(store.recordFailure(id, "kham", kham, 1))

        assertEquals("file-version: 1\nlabel: [unclosed\n", Files.readString(fileOf(id)))
        assertEquals(1, log.errors.count { id in it }, "one error for one bad file, not one per check: ${log.errors}")
        // Fixed by hand: the device is back as the file says.
        Files.writeString(fileOf(id), good)
        // Further ahead than the bad file: two writes within one tick of the file system clock get the same time.
        Files.setLastModifiedTime(fileOf(id), FileTime.fromMillis(Files.getLastModifiedTime(fileOf(id)).toMillis() + 10_000))
        assertTrue(store.credentialMatches(id, "kham", kham, "c2"))
    }

    @Test
    fun aBlockGoneOrChangedOnDiskIsReportedAsRevokedAndOnlyThat() {
        val revoked = mutableListOf<Pair<String, String>>()
        val watching = DeviceStore(root, log, clock) { device, user -> revoked += device to user }.also { it.load() }
        val id = watching.create("Phone", "android", DeviceMode.SHARED, "mali", mali, "c1")
        watching.putUser(id, "kham", kham, "c2")
        watching.putUser(id, "noy", "aaaaaaaa-0000-4000-8000-000000000003", "c3")
        Files.writeString(
            fileOf(id),
            Files.readString(fileOf(id)).replace(Regex("(?m)^  mali:\\n(    .*\\n)+"), "").replace(sha256Hex("c2"), sha256Hex("other")),
        )
        Files.setLastModifiedTime(fileOf(id), FileTime.fromMillis(Files.getLastModifiedTime(fileOf(id)).toMillis() + 5_000))

        assertTrue(watching.hasUser(id, "noy", "aaaaaaaa-0000-4000-8000-000000000003"))

        assertEquals(setOf(id to mali, id to kham), revoked.toSet())
        assertEquals(2, revoked.size)

        Files.delete(fileOf(id))
        assertNull(watching.get(id))
        assertEquals(setOf(id to mali, id to kham, id to "aaaaaaaa-0000-4000-8000-000000000003"), revoked.toSet()) // noy's block went with the file
    }

    @Test
    fun loadingAgainFindsAnEditThatLeftTheModifiedTimeAlone() {
        val revoked = mutableListOf<String>()
        val watching = DeviceStore(root, log, clock) { _, user -> revoked += user }.also { it.load() }
        val id = watching.create("Phone", "android", DeviceMode.SHARED, "mali", mali, "c1")
        watching.putUser(id, "kham", kham, "c2")
        val before = removeUserBlock(fileOf(id), "mali")
        Files.setLastModifiedTime(fileOf(id), before) // what a restored backup or a coarse clock looks like

        assertTrue(watching.hasUser(id, "mali", mali), "no change in the modified time, no new read")
        assertEquals(emptyList(), revoked)

        watching.load()

        assertEquals(listOf(mali), revoked)
        assertFalse(watching.hasUser(id, "mali", mali))
    }

    @Test
    fun aBlockWithoutAUserIdIsDroppedWithAWarningSoThatUserIsOffTheDevice() {
        val id = "00000000-0000-4000-8000-000000000004"
        Files.writeString(
            fileOf(id),
            "file-version: 1\nlabel: \"x\"\nplatform: \"y\"\nmode: shared\ncreated: 2026-10-03T08:00:00Z\nusers:\n" +
                "  mali:\n    credential-sha256: ${sha256Hex("c1")}\n    created: 2026-10-03T08:00:00Z\n    last-used: 2026-10-03T08:00:00Z\n    pin-failures: 0\n" +
                "  kham:\n    credential-sha256: ${sha256Hex("c2")}\n    user-id: not-an-id\n" +
                "  noy:\n    credential-sha256: ${sha256Hex("c3")}\n    user-id: $kham\n",
        )
        val warnings = RecordingLog()

        val again = DeviceStore(root, warnings, clock).also { it.load() }

        assertEquals(setOf("noy"), again.get(id)!!.users.keys)
        assertFalse(again.credentialMatches(id, "mali", mali, "c1"))
        assertEquals(1, warnings.warnings.count { "users.mali" in it && "user-id" in it }, warnings.warnings.toString())
        assertTrue(warnings.warnings.any { "users.kham" in it && "user-id" in it })
    }

    @Test
    fun aRenameMovesTheBlockOfThatAccountOnEveryDeviceAndOnlyThat() {
        val first = store.create("Phone", "android", DeviceMode.SHARED, "mali", mali, "c1")
        store.putUser(first, "kham", kham, "c2")
        val second = store.create("Tablet", "android", DeviceMode.PERSONAL, "mali", mali, "c3")
        val other = store.create("PC", "pc", DeviceMode.PERSONAL, "mali", kham, "c4") // another account that has "mali" in its file

        store.renameUser(mali, "mali", "mali2")

        assertEquals(setOf("mali2", "kham"), store.get(first)!!.users.keys)
        assertEquals(setOf("mali2"), store.get(second)!!.users.keys)
        assertEquals(setOf("mali"), store.get(other)!!.users.keys)
        assertTrue(reloaded().credentialMatches(first, "mali2", mali, "c1"))
        assertFalse(store.hasUser(first, "mali", mali))
    }
}
