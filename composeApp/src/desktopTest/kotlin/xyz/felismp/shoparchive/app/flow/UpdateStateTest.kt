package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.app.client.UpdatesApi
import xyz.felismp.shoparchive.app.flow.WindowsUpdater
import xyz.felismp.shoparchive.shared.AppVersion
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.UpdateFile
import xyz.felismp.shoparchive.shared.UpdatePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateStateTest {
    private val dir: Path = Files.createTempDirectory("sa-update")

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun file(name: String, version: String, build: Int = 0, platform: UpdatePlatform = UpdatePlatform.ANDROID, content: ByteArray = "bytes of $name".toByteArray()) =
        UpdateFile(name, platform, version, build, content.size.toLong(), sha(content))

    private class FakeApi(var listed: List<UpdateFile> = emptyList()) : UpdatesApi {
        var listError: Exception? = null
        val downloads = mutableListOf<String>()
        /** What the "server" sends for a file; defaults to the bytes the listing hashes. */
        var send: (String) -> ByteArray = { "bytes of $it".toByteArray() }
        var downloadError: Exception? = null
        override suspend fun updates(): List<UpdateFile> { listError?.let { throw it }; return listed }
        override suspend fun downloadUpdate(file: String, target: Path, onProgress: (Long) -> Unit) {
            downloadError?.let { throw it }
            downloads += file
            val bytes = send(file)
            Files.write(target, bytes)
            onProgress(bytes.size.toLong())
        }
    }

    private inner class FakeUpdater(var answer: Install = Install.Started, var failure: Exception? = null) : AppUpdater {
        override val platform = UpdatePlatform.ANDROID
        override val folder: Path get() = dir.resolve("updates")
        val installed = mutableListOf<Path>()
        override suspend fun install(file: Path): Install { failure?.let { throw it }; installed.add(file); return answer }
    }

    private val calls = object : Calls { override suspend fun <T> run(block: suspend () -> T): T = block() }

    private fun state(api: UpdatesApi, updater: AppUpdater, own: AppVersion = AppVersion(1, 0, 0, 1), checkNow: Boolean = false) =
        UpdateState(CoroutineScope(Dispatchers.Unconfined), api, calls, updater, own, Dispatchers.Unconfined, checkNow)

    @Test
    fun aNewerFileForThisPlatformIsOfferedAtStartAndOlderOrOtherOnesAreNot() {
        val api = FakeApi(listOf(file("ShopArchive-1.0.0.apk", "1.0.0"), file("ShopArchive-2.0.0.msi", "2.0.0", platform = UpdatePlatform.WINDOWS), file("ShopArchive-1.2.0.apk", "1.2.0")))
        assertEquals("ShopArchive-1.2.0.apk", assertIs<UpdateUi.Available>(state(api, FakeUpdater(), checkNow = true).ui.value).file.file)

        val none = FakeApi(listOf(file("ShopArchive-1.0.0.apk", "1.0.0"), file("ShopArchive-2.0.0.msi", "2.0.0", platform = UpdatePlatform.WINDOWS)))
        assertEquals(UpdateUi.Idle, state(none, FakeUpdater(), checkNow = true).ui.value, "the start says nothing when there is nothing")
    }

    @Test
    fun aCheckThePersonAsksForSaysNothingNewerOrWhyItFailed() {
        val api = FakeApi()
        val s = state(api, FakeUpdater())
        s.check()
        assertEquals(UpdateUi.UpToDate, s.ui.value)

        api.listError = ClientError.Unreachable()
        s.check()
        val failed = assertIs<UpdateUi.Failed>(s.ui.value)
        assertEquals(UpdateProblem.Call(Failure.Unreachable), failed.problem)
    }

    @Test
    fun aFailedCheckAtStartIsSilent() {
        val api = FakeApi().apply { listError = ClientError.Unreachable() }
        assertEquals(UpdateUi.Idle, state(api, FakeUpdater(), checkNow = true).ui.value)
    }

    @Test
    fun laterHidesTheOfferUntilTheNextCheck() {
        val api = FakeApi(listOf(file("ShopArchive-1.2.0.apk", "1.2.0")))
        val s = state(api, FakeUpdater(), checkNow = true)
        s.later()
        assertEquals(UpdateUi.Idle, s.ui.value)
        s.check()
        assertIs<UpdateUi.Available>(s.ui.value)
    }

    @Test
    fun installDownloadsChecksAndHandsTheFileToTheInstaller() {
        val api = FakeApi(listOf(file("ShopArchive-1.2.0.apk", "1.2.0")))
        val updater = FakeUpdater()
        val s = state(api, updater, checkNow = true)
        s.install()
        assertEquals(listOf("ShopArchive-1.2.0.apk"), api.downloads)
        assertEquals(listOf(dir.resolve("updates/ShopArchive-1.2.0.apk")), updater.installed)
        assertEquals("bytes of ShopArchive-1.2.0.apk", Files.readString(updater.installed.single()))
        assertEquals(UpdateUi.Idle, s.ui.value)
    }

    @Test
    fun aFileThatIsNotTheListedOneIsDeletedAndNeverInstalled() {
        val api = FakeApi(listOf(file("ShopArchive-1.2.0.apk", "1.2.0"))).apply { send = { "tampered".toByteArray() } }
        val updater = FakeUpdater()
        val s = state(api, updater, checkNow = true)
        s.install()
        assertEquals(UpdateProblem.Damaged, assertIs<UpdateUi.Failed>(s.ui.value).problem)
        assertTrue(updater.installed.isEmpty())
        assertFalse(Files.exists(dir.resolve("updates/ShopArchive-1.2.0.apk")))
    }

    @Test
    fun aRefusedPermissionKeepsTheDownloadAndTheNextInstallDoesNotFetchAgain() {
        val api = FakeApi(listOf(file("ShopArchive-1.2.0.apk", "1.2.0")))
        val updater = FakeUpdater(answer = Install.NeedsPermission)
        val s = state(api, updater, checkNow = true)
        s.install()
        assertIs<UpdateUi.NeedsPermission>(s.ui.value)
        updater.answer = Install.Started
        s.install()
        assertEquals(1, api.downloads.size, "the file was there and matched")
        assertEquals(2, updater.installed.size)
        assertEquals(UpdateUi.Idle, s.ui.value)
    }

    @Test
    fun oldDownloadsAreClearedBeforeANewOne() {
        Files.createDirectories(dir.resolve("updates"))
        Files.writeString(dir.resolve("updates/ShopArchive-1.1.0.apk"), "old")
        val api = FakeApi(listOf(file("ShopArchive-1.2.0.apk", "1.2.0")))
        val s = state(api, FakeUpdater(), checkNow = true)
        s.install()
        assertEquals(listOf("ShopArchive-1.2.0.apk"), Files.list(dir.resolve("updates")).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun aFailedDownloadOrInstallLeavesARetry() {
        val api = FakeApi(listOf(file("ShopArchive-1.2.0.apk", "1.2.0")))
        val updater = FakeUpdater()
        val s = state(api, updater, checkNow = true)
        api.downloadError = ClientError.Api(404, ErrorCode.NOT_FOUND, "gone")
        s.install()
        val failed = assertIs<UpdateUi.Failed>(s.ui.value)
        assertEquals(UpdateProblem.Call(Failure.Refused(ErrorCode.NOT_FOUND)), failed.problem)

        api.downloadError = null
        updater.failure = java.io.IOException("no room")
        s.install()
        assertEquals(UpdateProblem.CannotInstall, assertIs<UpdateUi.Failed>(s.ui.value).problem)

        updater.failure = null
        s.install()
        assertEquals(UpdateUi.Idle, s.ui.value)
        assertEquals(1, updater.installed.size)
    }

    @Test
    fun installWithNothingOfferedDoesNothing() {
        val api = FakeApi()
        val s = state(api, FakeUpdater())
        s.install()
        assertEquals(UpdateUi.Idle, s.ui.value)
        assertTrue(api.downloads.isEmpty())
    }

    @Test
    fun theWindowsInstallerRunsMsiexecOnTheFileAndThenClosesTheApp() {
        val calls = mutableListOf<String>()
        val updater = WindowsUpdater(dir.resolve("u"), start = { calls += it.joinToString(" ") }, exit = { calls += "exit" })
        val result = kotlinx.coroutines.runBlocking { updater.install(dir.resolve("ShopArchive-1.2.0.msi")) }
        assertEquals(Install.Started, result)
        assertEquals(listOf("msiexec /i ${dir.resolve("ShopArchive-1.2.0.msi")}", "exit"), calls)
        assertEquals(UpdatePlatform.WINDOWS, updater.platform)
    }

    /** Offers [listed] as the only file and tries to install it; nothing may be created or deleted for a name that is not the shared grammar. */
    private fun installRefused(listed: UpdateFile, before: (Path) -> Unit = {}): UpdateState {
        val updater = FakeUpdater()
        val outside = dir.resolve("preferences.json").also { Files.writeString(it, "mine") }
        Files.createDirectories(dir.resolve("updates"))
        val keep = dir.resolve("updates/ShopArchive-0.9.0.apk").also { Files.writeString(it, "old") }
        before(dir)
        val api = FakeApi(listOf(listed))
        val s = state(api, updater)
        s.install(listed)
        assertEquals(UpdateProblem.CannotInstall, assertIs<UpdateUi.Failed>(s.ui.value).problem)
        assertTrue(api.downloads.isEmpty() && updater.installed.isEmpty())
        assertEquals("mine", Files.readString(outside))
        assertEquals("old", Files.readString(keep), "nothing was deleted")
        // Only what the test put there (the old file and, in the link tests, the links) remains; no file was fetched or written.
        assertEquals(listOf("ShopArchive-0.9.0.apk"), Files.list(dir.resolve("updates")).use { it.filter { p -> !Files.isSymbolicLink(p) }.map { p -> p.fileName.toString() }.toList() })
        return s
    }

    @Test
    fun aNameThatLeavesTheFolderIsRefusedBeforeAnythingIsTouched() {
        installRefused(file("../preferences.json", "1.2.0"))
        installRefused(file(dir.resolve("preferences.json").toString(), "1.2.0"))
    }

    @Test
    fun aNameThatDisagreesWithItsPlatformOrMetadataIsRefused() {
        installRefused(file("ShopArchive-1.2.0.msi", "1.2.0"))
        installRefused(file("ShopArchive-1.2.0.apk", "1.3.0"))
        installRefused(file("ShopArchive-1.2.0-2.apk", "1.2.0", build = 3))
    }

    @Test
    fun aTargetThatIsASymbolicLinkIsRefused() {
        installRefused(file("ShopArchive-1.2.0.apk", "1.2.0")) { root ->
            Files.createSymbolicLink(root.resolve("updates/ShopArchive-1.2.0.apk"), root.resolve("preferences.json"))
        }
    }

    @Test
    fun aPartFileThatIsASymbolicLinkIsRefused() {
        installRefused(file("ShopArchive-1.2.0.apk", "1.2.0")) { root ->
            Files.createSymbolicLink(root.resolve("updates/ShopArchive-1.2.0.apk.part"), root.resolve("preferences.json"))
        }
    }

    @Test
    fun aListedFileWithABadNameIsNeverOffered() {
        val api = FakeApi(listOf(file("../ShopArchive-9.0.0.apk", "9.0.0"), file("ShopArchive-1.2.0.apk", "1.2.0")))
        assertEquals("ShopArchive-1.2.0.apk", assertIs<UpdateUi.Available>(state(api, FakeUpdater(), checkNow = true).ui.value).file.file)
    }
}
