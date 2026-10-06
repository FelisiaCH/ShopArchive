package xyz.felismp.shoparchive.server.backup

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    @TempDir
    lateinit var dir: Path

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun BackupEnv.ok(): BackupOutcome.Ok = assertIs<BackupOutcome.Ok>(service.run())

    @Test
    fun theZipHoldsTheDataAndNotWhatIsLeftOut() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val ok = env.ok()

        val names = env.root.resolve("backups/${ok.zip}").zipNames()
        assertEquals(
            setOf(
                "config/extra.yml", "config/shoparchive.yml", "config/currencies.yml", "user/alice.yml", "data/branches.yml", "data/server-id",
                "record/2026/10/02/e1/entry.yml", "record/2026/10/02/e1/history.yml", "certs/keystore.p12", "plugins/approved.yml",
                "crash-reports/crash-1.txt", MANIFEST_NAME,
            ),
            names.filter { it != "server.properties" }.toSet(),
            "got $names",
        )
        for (left in listOf("cache/", "logs/", "tmp/", "libraries/", "versions/", "exports/", "downloads/", "backups/", "start.sh", "shoparchive-server.jar", "data/session.lock")) {
            assertTrue(names.none { it.startsWith(left) || it == left }, "$left is in the zip")
        }
        assertTrue(names.none { it.contains("slip-") }, "a slip is in the zip")
    }

    @Test
    fun theManifestListsEveryFileWithItsSizeAndChecksumAndTheSlipsWithTheirMirror() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val ok = env.ok()
        val zip = env.root.resolve("backups/${ok.zip}")
        val manifest = zip.manifest()

        assertEquals("9.9.9", manifest.serverVersion)
        assertEquals(1, manifest.manifestVersion)
        assertNotNull(Instant.parse(manifest.createdAt))
        assertEquals(zip.zipNames() - MANIFEST_NAME, manifest.files.map { it.path }.toSet())
        val user = manifest.files.single { it.path == "user/alice.yml" }
        assertEquals("name: alice\n".length.toLong(), user.size)
        assertEquals(sha("name: alice\n".toByteArray()), user.sha256)

        val jpg = ByteArray(100) { it.toByte() }
        val png = ByteArray(50) { (it * 3).toByte() }
        assertEquals(
            listOf(
                Triple("record/2026/10/02/e1/slip-1.jpg", sha(jpg), "${sha(jpg)}.jpg"),
                Triple("record/2026/10/02/e1/slip-2.png", sha(png), "${sha(png)}.png"),
            ),
            manifest.slips.sortedBy { it.path }.map { Triple(it.path, it.sha256, it.mirror) },
        )
    }

    @Test
    fun aSlipIsMirroredOnceByItsChecksumAndLaterBackupsSkipIt() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val jpg = ByteArray(100) { it.toByte() }

        val first = env.ok()
        assertEquals(2, first.newSlips)
        val mirrored = env.root.resolve("backups/slips/${sha(jpg)}.jpg")
        assertContentEquals(jpg, Files.readAllBytes(mirrored))
        val mirroredTime = Files.getLastModifiedTime(mirrored)

        // The same picture under another entry is the same file in the mirror; a new picture adds one.
        env.put("record/2026/10/03/e2/slip-1.jpg", jpg)
        env.put("record/2026/10/03/e2/slip-2.jpg", ByteArray(10) { 7 })
        val second = env.ok()
        assertEquals(1, second.newSlips)
        assertEquals(3, Files.list(env.root.resolve("backups/slips")).use { it.count() })
        assertEquals(mirroredTime, Files.getLastModifiedTime(mirrored))
        assertEquals(4, env.root.resolve("backups/${second.zip}").manifest().slips.size)

        assertEquals(0, env.ok().newSlips)
    }

    @Test
    fun aMirrorFileThatWentMissingIsMirroredAgain() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        env.ok()
        val jpg = ByteArray(100) { it.toByte() }
        Files.delete(env.root.resolve("backups/slips/${sha(jpg)}.jpg"))

        assertEquals(1, env.ok().newSlips)
        assertTrue(Files.exists(env.root.resolve("backups/slips/${sha(jpg)}.jpg")))
    }

    @Test
    fun aWriteDuringTheBackupWaitsAndSucceeds() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = BackupService(env.root, { env.config.backup }, { java.time.ZoneOffset.UTC }, { block ->
            env.writer.run {
                held.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                block()
            }
        }, env.log, env.clock, "9.9.9", SERVER_ID)
        var outcome: BackupOutcome? = null
        val backup = thread { outcome = service.run() }
        assertTrue(held.await(10, TimeUnit.SECONDS))

        val written = CountDownLatch(1)
        val writing = thread {
            env.writer.run { env.put("record/2026/10/04/e3/entry.yml", "id: e3\n") }
            written.countDown()
        }
        assertFalse(written.await(300, TimeUnit.MILLISECONDS), "the write did not wait for the backup")
        assertFalse(Files.exists(env.root.resolve("record/2026/10/04/e3/entry.yml")))

        release.countDown()
        backup.join(10_000)
        writing.join(10_000)

        assertTrue(written.await(10, TimeUnit.SECONDS))
        assertEquals("id: e3\n", Files.readString(env.root.resolve("record/2026/10/04/e3/entry.yml")))
        val ok = assertIs<BackupOutcome.Ok>(outcome)
        // The write was queued behind the snapshot: it is in the next backup, not half in this one.
        assertTrue("record/2026/10/04/e3/entry.yml" !in env.root.resolve("backups/${ok.zip}").zipNames())
        assertTrue("record/2026/10/04/e3/entry.yml" in env.ok().let { env.root.resolve("backups/${it.zip}").zipNames() })
    }

    @Test
    fun aBackupThatFailsLeavesNoZipAndNoPartAndTheNextOneWorks() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val failing = BackupService(env.root, { env.config.backup }, { java.time.ZoneOffset.UTC }, { _ -> throw java.io.IOException("disk full") }, env.log, env.clock, serverId = SERVER_ID)

        val failed = assertIs<BackupOutcome.Failed>(failing.run())

        assertTrue("disk full" in failed.reason, failed.reason)
        assertEquals(emptyList(), env.root.zips())
        assertEquals(emptyList(), env.root.parts())
        assertTrue(env.log.errors.single().contains("Backup failed"))
        assertIs<BackupOutcome.Failed>(failing.lastOutcome())

        assertIs<BackupOutcome.Ok>(env.service.run())
        assertEquals(1, env.root.zips().size)
    }

    @Test
    fun aLeftOverPartFromACrashIsRemoved() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        env.put("backups/20200101-000000.zip.part", "half")
        env.put("backups/slips/abc.jpg.part", "half")

        env.ok()

        assertEquals(emptyList(), env.root.parts())
    }

    @Test
    fun onlyTheNewestKeepZipsAreKeptAndSlipsAreNeverPruned() {
        val clock = TestClock(Instant.parse("2026-10-02T03:00:00Z"))
        val env = BackupEnv(dir.resolve("root"), "  keep: 2\n", clock).also { it.populate() }
        env.put("backups/notes.zip", "mine")
        env.put("backups/readme.txt", "mine")
        val names = (1..4).map {
            clock.advance(86_400)
            env.ok().zip
        }
        // A slip only an old backup refers to stays in the mirror.
        Files.delete(env.root.resolve("record/2026/10/02/e1/slip-1.jpg"))

        clock.advance(86_400)
        env.ok()

        assertEquals(listOf(names[3], "20261007-030000.zip"), env.root.zips())
        assertTrue(Files.exists(env.root.resolve("backups/notes.zip")))
        assertTrue(Files.exists(env.root.resolve("backups/readme.txt")))
        assertEquals(2, Files.list(env.root.resolve("backups/slips")).use { it.count() })
    }

    @Test
    fun twoBackupsInOneSecondAreBothKeptAndSortByTime() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val a = env.ok().zip
        val b = env.ok().zip
        val c = env.ok().zip

        assertEquals(listOf("20261002-030405.zip", "20261002-030405-2.zip", "20261002-030405-3.zip"), listOf(a, b, c))
        assertEquals(listOf(a, b, c), env.root.zips())
    }

    @Test
    fun aSecondBackupWhileOneRunsIsRefused() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = BackupService(env.root, { env.config.backup }, { java.time.ZoneOffset.UTC }, { block ->
            held.countDown()
            release.await(10, TimeUnit.SECONDS)
            block()
        }, env.log, env.clock, serverId = SERVER_ID)
        val first = thread { service.run() }
        assertTrue(held.await(10, TimeUnit.SECONDS))

        assertTrue(service.isRunning)
        assertNull(service.run())

        release.countDown()
        first.join(10_000)
        assertFalse(service.isRunning)
        assertEquals(1, env.root.zips().size)
    }

    @Test
    fun aLinkIsNotFollowedOutOfTheRoot() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val outside = dir.resolve("outside").also { Files.createDirectories(it) }
        Files.writeString(outside.resolve("secret.txt"), "secret")
        try {
            Files.createSymbolicLink(env.root.resolve("data/linked"), outside)
            Files.createSymbolicLink(env.root.resolve("data/linked.txt"), outside.resolve("secret.txt"))
        } catch (e: Exception) {
            return // no symbolic links here (e.g. Windows without the right)
        }

        val names = env.root.resolve("backups/${env.ok().zip}").zipNames()

        assertTrue(names.none { "secret" in it || it.startsWith("data/linked") }, "$names")
        assertEquals(2, env.log.warnings.count { "is a link" in it })
    }

    @Test
    fun onAPosixFilesystemTheZipsAndTheFolderTheyAreInAreOwnerOnly() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews(), "no POSIX permissions on this filesystem")
        val copy = Files.createDirectories(dir.resolve("usb"))
        val env = BackupEnv(dir.resolve("root"), "  copy-to: \"${copy.toString().replace("\\", "\\\\")}\"\n").also { it.populate() }

        val ok = env.ok()

        fun mode(path: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
        assertEquals("rwx------", mode(env.root.resolve("backups")))
        assertEquals("rw-------", mode(env.root.resolve("backups/${ok.zip}")))
        assertEquals("rw-------", mode(copy.copyDir().resolve(ok.zip)))
    }

    @Test
    fun copyToGetsTheZipsAndTheSlips() {
        val copy = Files.createDirectories(dir.resolve("usb"))
        val env = BackupEnv(dir.resolve("root"), "  copy-to: \"${copy.toString().replace("\\", "\\\\")}\"\n").also { it.populate() }

        val ok = env.ok()

        assertEquals(CopyOutcome.Done(1, 2), ok.copy)
        // Everything goes into a folder of its own, not among the other files of the copy-to folder.
        assertEquals(listOf("ShopArchive-$SERVER_ID"), Files.list(copy).use { l -> l.map { it.fileName.toString() }.toList() })
        val mine = copy.copyDir()
        assertEquals(listOf(ok.zip), listZips(mine))
        assertContentEquals(Files.readAllBytes(env.root.resolve("backups/${ok.zip}")), Files.readAllBytes(mine.resolve(ok.zip)))
        assertEquals(Files.list(env.root.resolve("backups/slips")).use { l -> l.map { it.fileName.toString() }.sorted().toList() }, Files.list(mine.resolve("slips")).use { l -> l.map { it.fileName.toString() }.sorted().toList() })
        assertEquals(emptyList(), env.log.warnings)

        // Next time only what is new goes over.
        env.put("record/2026/10/03/e2/slip-1.jpg", ByteArray(5))
        assertEquals(CopyOutcome.Done(1, 1), env.ok().copy)
    }

    @Test
    fun aMissingCopyToFolderIsAWarningTheBackupStillWorksAndTheNextRunCatchesUp() {
        val usb = dir.resolve("usb")
        val env = BackupEnv(dir.resolve("root"), "  copy-to: \"${usb.toString().replace("\\", "\\\\")}\"\n").also { it.populate() }

        val ok = env.ok()

        assertIs<CopyOutcome.Failed>(ok.copy)
        assertTrue(Files.exists(env.root.resolve("backups/${ok.zip}")))
        assertFalse(Files.exists(usb), "the missing folder must not be made")
        assertEquals(1, env.log.warningsWith("could not copy", "tries again").size, env.log.warnings.toString())
        assertTrue(env.service.statusLines().any { "copy-to: FAILED" in it })

        Files.createDirectories(usb)
        val next = env.ok()
        assertEquals(CopyOutcome.Done(2, 2), next.copy)
        assertEquals(2, listZips(usb.copyDir()).size)
    }

    @Test
    fun copyToThatCannotBeWrittenIsAWarningToo() {
        val file = dir.resolve("not-a-folder").also { Files.writeString(it, "x") }
        val env = BackupEnv(dir.resolve("root"), "  copy-to: \"${file.toString().replace("\\", "\\\\")}\"\n").also { it.populate() }

        assertIs<CopyOutcome.Failed>(env.ok().copy)
        assertEquals(1, env.log.warningsWith("could not copy").size)
    }

    @Test
    fun keepAlsoPrunesTheCopyButOnlyTheZipsOfThisServer() {
        val copy = Files.createDirectories(dir.resolve("usb"))
        val clock = TestClock(Instant.parse("2026-10-02T03:00:00Z"))
        val env = BackupEnv(dir.resolve("root"), "  keep: 2\n  copy-to: \"${copy.toString().replace("\\", "\\\\")}\"\n", clock).also { it.populate() }
        // Zips that only have a name like ours: in the copy-to folder itself, in our folder as a zip of another server, as no zip at all.
        Files.writeString(copy.resolve("20200101-000000.zip"), "someone else's")
        val mine = Files.createDirectories(copy.copyDir())
        zip(mine.resolve("20200101-000001.zip"), MANIFEST_NAME to manifestJson.encodeToString(Manifest.serializer(), Manifest(1, "1", "t", "another-server", emptyList(), emptyList())))
        zip(mine.resolve("20200101-000002.zip"), "other.txt" to "no manifest")
        Files.writeString(mine.resolve("20200101-000003.zip"), "not a zip")
        Files.writeString(mine.resolve("note.zip"), "x")
        repeat(4) { clock.advance(86_400); env.ok() }

        assertEquals(env.root.zips(), listZips(mine).filter { it.startsWith("2026") })
        assertEquals(2, env.root.zips().size)
        for (kept in listOf("20200101-000001.zip", "20200101-000002.zip", "20200101-000003.zip", "note.zip")) assertTrue(Files.exists(mine.resolve(kept)), kept)
        assertTrue(Files.exists(copy.resolve("20200101-000000.zip")))
        assertTrue(env.log.warnings.any { "20200101-000003.zip" in it && "kept" in it }, env.log.warnings.toString())
    }

    @Test
    fun aLocalZipOfAnotherServerIsNotPruned() {
        val clock = TestClock(Instant.parse("2026-10-02T03:00:00Z"))
        val env = BackupEnv(dir.resolve("root"), "  keep: 1\n", clock).also { it.populate() }
        zip(env.root.resolve("backups/20200101-000001.zip"), MANIFEST_NAME to manifestJson.encodeToString(Manifest.serializer(), Manifest(1, "1", "t", "another-server", emptyList(), emptyList())))
        repeat(3) { clock.advance(86_400); env.ok() }

        assertEquals(2, env.root.zips().size)
        assertTrue(Files.exists(env.root.resolve("backups/20200101-000001.zip")))
    }

    private fun zip(path: Path, vararg entries: Pair<String, String>) {
        java.util.zip.ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(java.util.zip.ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun statusShowsTheLastBackupItsSizeAndCopyResult() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        assertEquals("Backup: none yet", env.service.statusLines().first())

        val ok = env.ok()
        val lines = env.service.statusLines()

        assertTrue(lines[0].startsWith("Backup: last 2026-10-02 03:04 ok, ") && ok.zip in lines[0], lines.toString())
        assertEquals("Backup copy-to: not set", lines[1])
        assertEquals("Backup: daily at 03:00, keep 14", lines[2])
    }

    @Test
    fun theLastBackupIsFoundOnDiskAfterARestart() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val ok = env.ok()
        val fresh = BackupService(env.root, { env.config.backup }, { java.time.ZoneOffset.UTC }, { b -> env.writer.run(b) }, env.log, env.clock, serverId = SERVER_ID)

        assertEquals(Instant.parse("2026-10-02T03:04:05Z"), fresh.lastSuccess())
        assertEquals(ok.zip, assertIs<BackupOutcome.Ok>(fresh.lastOutcome()).zip)
    }

    @Test
    fun restoreRoundTripPutsEveryFileAndSlipBack() {
        val old = BackupEnv(dir.resolve("old")).also { it.populate() }
        val ok = old.ok()

        // The new root: only what the unzip brings, then the slips from the mirror.
        val new = dir.resolve("new").also { Files.createDirectories(it) }
        java.util.zip.ZipFile(old.root.resolve("backups/${ok.zip}").toFile()).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val target = new.resolve(entry.name)
                Files.createDirectories(target.parent)
                Files.write(target, zip.getInputStream(entry).readBytes())
            }
        }
        val restorer = BackupEnv(new)
        val report = restorer.service.restoreSlips(old.root.resolve("backups/slips"))

        assertEquals(2, report.restored)
        assertEquals(emptyList(), report.problems)
        for (relative in listOf("record/2026/10/02/e1/slip-1.jpg", "record/2026/10/02/e1/slip-2.png", "record/2026/10/02/e1/entry.yml", "user/alice.yml", "data/branches.yml", "certs/keystore.p12")) {
            assertContentEquals(Files.readAllBytes(old.root.resolve(relative)), Files.readAllBytes(new.resolve(relative)), relative)
        }
        // Run again: everything is there already.
        val again = restorer.service.restoreSlips(old.root.resolve("backups/slips"))
        assertEquals(0, again.restored)
        assertEquals(2, again.alreadyThere)
        // And the new root's own backup does not carry the old manifest as a file.
        val next = assertIs<BackupOutcome.Ok>(restorer.service.run())
        assertEquals(1, new.resolve("backups/${next.zip}").zipNames().count { it == MANIFEST_NAME })
        assertEquals(2, new.resolve("backups/${next.zip}").manifest().slips.size)
    }

    @Test
    fun restoreReportsAMissingOrChangedMirrorFileAndNeverWritesOutsideRecord() {
        val old = BackupEnv(dir.resolve("old")).also { it.populate() }
        val ok = old.ok()
        val new = dir.resolve("new").also { Files.createDirectories(it) }
        val manifestText = old.root.resolve("backups/${ok.zip}").zipText(MANIFEST_NAME)
        Files.writeString(new.resolve(MANIFEST_NAME), manifestText.replace("record/2026/10/02/e1/slip-2.png", "../escape/slip-2.png"))
        val jpg = ByteArray(100) { it.toByte() }
        // slip-1's mirror file is damaged.
        val mirror = Files.createDirectories(dir.resolve("mirror"))
        Files.write(mirror.resolve("${sha(jpg)}.jpg"), ByteArray(3))
        val restorer = BackupEnv(new)

        val report = restorer.service.restoreSlips(mirror)

        assertEquals(0, report.restored)
        assertEquals(2, report.problems.size, report.problems.toString())
        assertTrue(report.problems.any { "does not match its checksum" in it })
        assertTrue(report.problems.any { "not a slip path" in it })
        assertFalse(Files.exists(dir.resolve("escape")))
        assertFalse(Files.exists(new.resolve("record/2026/10/02/e1/slip-1.jpg")))
    }

    @Test
    fun restoreWithoutAManifestSaysSo() {
        val env = BackupEnv(dir.resolve("root"))
        val e = kotlin.test.assertFailsWith<BackupException> { env.service.restoreSlips(dir) }
        assertTrue(MANIFEST_NAME in e.message!!)
    }

    private fun link(from: Path, to: Path): Boolean = try {
        Files.createSymbolicLink(from, to)
        true
    } catch (e: Exception) {
        false // no symbolic links here (e.g. Windows without the right)
    }

    @Test
    fun aLinkedSlipMirrorIsNotWrittenThrough() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val outside = Files.createDirectories(dir.resolve("outside"))
        if (!link(env.root.resolve("backups/slips"), outside)) return

        val failed = assertIs<BackupOutcome.Failed>(env.service.run())

        assertTrue("link" in failed.reason, failed.reason)
        assertEquals(0, Files.list(outside).use { it.count() })
        assertEquals(emptyList(), env.root.zips())
    }

    @Test
    fun aLinkedBackupsFolderIsNotWrittenThrough() {
        val env = BackupEnv(dir.resolve("root")).also { it.populate() }
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.delete(env.root.resolve("backups"))
        if (!link(env.root.resolve("backups"), outside)) return

        assertIs<BackupOutcome.Failed>(env.service.run())
        assertEquals(0, Files.list(outside).use { it.count() })
    }

    @Test
    fun aLinkedSlipsFolderInCopyToIsNotWrittenThrough() {
        val usb = Files.createDirectories(dir.resolve("usb"))
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.createDirectories(usb.copyDir())
        if (!link(usb.copyDir().resolve("slips"), outside)) return
        val env = BackupEnv(dir.resolve("root"), "  copy-to: \"${usb.toString().replace("\\", "\\\\")}\"\n").also { it.populate() }

        val ok = env.ok()

        assertIs<CopyOutcome.Failed>(ok.copy)
        assertEquals(0, Files.list(outside).use { it.count() })
    }

    @Test
    fun restoreDoesNotWriteThroughALinkedFolderUnderRecord() {
        val old = BackupEnv(dir.resolve("old")).also { it.populate() }
        val ok = old.ok()
        val new = dir.resolve("new").also { Files.createDirectories(it) }
        Files.writeString(new.resolve(MANIFEST_NAME), old.root.resolve("backups/${ok.zip}").zipText(MANIFEST_NAME))
        val restorer = BackupEnv(new)
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.createDirectories(new.resolve("record/2026/10"))
        if (!link(new.resolve("record/2026/10/02"), outside)) return

        val report = restorer.service.restoreSlips(old.root.resolve("backups/slips"))

        assertEquals(0, report.restored)
        assertEquals(2, report.problems.size, report.problems.toString())
        assertEquals(0, Files.list(outside).use { it.count() })
    }

    @Test
    fun aSlipThatAnEditWroteJustBeforeIsAConflictAndNotReplaced() {
        val old = BackupEnv(dir.resolve("old")).also { it.populate() }
        val ok = old.ok()
        val new = dir.resolve("new").also { Files.createDirectories(it) }
        Files.writeString(new.resolve(MANIFEST_NAME), old.root.resolve("backups/${ok.zip}").zipText(MANIFEST_NAME))
        val restorer = BackupEnv(new)
        var pauses = 0
        // An edit of the entry gets in line first and saves its new slip under the name that is to come back.
        val service = BackupService(new, { restorer.config.backup }, { java.time.ZoneOffset.UTC }, { block ->
            restorer.writer.run {
                pauses++
                if (pauses == 1) restorer.put("record/2026/10/02/e1/slip-1.jpg", "the new slip")
                block()
            }
        }, restorer.log, restorer.clock, "9.9.9", SERVER_ID)

        val report = service.restoreSlips(old.root.resolve("backups/slips"))

        assertTrue(pauses >= 2, "restore did not run on the record writer")
        assertEquals("the new slip", Files.readString(new.resolve("record/2026/10/02/e1/slip-1.jpg")))
        assertEquals(1, report.restored)
        assertEquals(1, report.problems.size, report.problems.toString())
        assertTrue("conflict" in report.problems.single())
        assertEquals(emptyList(), new.parts())
        assertFalse(Files.exists(new.resolve("record/2026/10/02/e1/slip-1.jpg.part")))
    }
}
