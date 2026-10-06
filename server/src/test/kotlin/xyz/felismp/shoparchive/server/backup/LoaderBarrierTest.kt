package xyz.felismp.shoparchive.server.backup

import org.junit.jupiter.api.io.TempDir
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.auth.AuthEnv
import xyz.felismp.shoparchive.server.records.login
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Loading and re-reading can write business files (create, migrate, normalize), so they are changes for the data barrier; plain reads are not. */
class LoaderBarrierTest {
    @TempDir
    lateinit var dir: Path

    private class Frozen(barrier: DataBarrier) {
        private val frozen = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val holder = thread { barrier.freeze(10_000) { frozen.countDown(); release.await(20, TimeUnit.SECONDS) } }

        init {
            assertTrue(frozen.await(10, TimeUnit.SECONDS))
        }

        fun end() {
            release.countDown()
            holder.join(10_000)
        }
    }

    private fun edit(file: Path, extra: String) {
        val before = Files.getLastModifiedTime(file)
        Files.writeString(file, Files.readString(file) + extra)
        Files.setLastModifiedTime(file, FileTime.fromMillis(before.toMillis() + 5_000))
    }

    @Test
    fun loadingAndReloadingUsersDevicesDataAndConfigWriteNothingWhileFrozen() {
        val env = AuthEnv(dir.resolve("auth"), withRecords = true)
        env.login("alice")
        val device = Files.list(env.root.resolve("data/devices")).use { it.toList() }.single()
        edit(device, "bogus-key: 1\n")
        val deviceEdited = Files.readString(device)
        val permissions = env.root.resolve("user/permissions.txt")
        val branches = env.root.resolve("data/branches.yml")
        val currencies = env.root.resolve("config/currencies.yml")
        Files.delete(permissions)
        Files.delete(branches)
        Files.delete(currencies)

        val frozen = Frozen(env.barrier)
        val done = CountDownLatch(4)
        val loaders = listOf<() -> Unit>(env.users::load, env.auth.devices::load, env.records!!::loadData, { env.settings.reload() })
            .map { load -> thread { load(); done.countDown() } }

        assertFalse(done.await(400, TimeUnit.MILLISECONDS), "a loader ran while frozen")
        assertFalse(Files.exists(permissions), "user/permissions.txt was written while frozen")
        assertFalse(Files.exists(branches), "data/branches.yml was created while frozen")
        assertFalse(Files.exists(currencies), "config/currencies.yml was created while frozen")
        assertEquals(deviceEdited, Files.readString(device), "a device file was rewritten while frozen")

        frozen.end()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        loaders.forEach { it.join(10_000) }
        assertTrue(Files.exists(permissions) && Files.exists(branches) && Files.exists(currencies))
        assertTrue("bogus-key" !in Files.readString(device))
    }

    @Test
    fun aReadThatFindsAFileChangedByHandNeitherWaitsNorWritesAndTheNextChangeNormalizesIt() {
        val env = AuthEnv(dir.resolve("auth"))
        env.login("alice")
        val userId = env.users.find("alice")!!.id
        val userFile = env.root.resolve("user/alice.yml")
        val deviceFile = Files.list(env.root.resolve("data/devices")).use { it.toList() }.single()
        val deviceId = deviceFile.fileName.toString().removeSuffix(".yml")
        edit(userFile, "bogus-key: 1\n")
        edit(deviceFile, "bogus-key: 1\n")
        val userEdited = Files.readString(userFile)
        val deviceEdited = Files.readString(deviceFile)

        val frozen = Frozen(env.barrier)
        val read = CountDownLatch(1)
        var found: Any? = null
        thread { found = env.users.findFreshById(userId); assertNotNull(env.auth.devices.get(deviceId)); read.countDown() }
        assertTrue(read.await(5, TimeUnit.SECONDS), "a read waited for the backup")
        assertNotNull(found)
        assertEquals(userEdited, Files.readString(userFile), "a read rewrote the user file")
        assertEquals(deviceEdited, Files.readString(deviceFile), "a read rewrote the device file")
        frozen.end()

        // The next change goes through the barrier and normalizes what the reads left alone.
        env.users.setOp("alice", true)
        env.auth.devices.setMode(deviceId, xyz.felismp.shoparchive.shared.DeviceMode.SHARED)
        assertTrue("bogus-key" !in Files.readString(userFile))
        assertTrue("bogus-key" !in Files.readString(deviceFile))
        assertTrue(env.users.find("alice")!!.op)
    }

    @Test
    fun reloadUsersWaitsForTheBackupWithoutHoldingTheStoreSoReadsAndPermissionChecksContinue() {
        val env = AuthEnv(dir.resolve("auth"))
        env.login("alice")
        env.users.setOp("alice", true)
        val userId = env.users.find("alice")!!.id

        val frozen = Frozen(env.barrier)
        val reloaded = CountDownLatch(1)
        val reload = thread { env.users.load(); reloaded.countDown() }
        assertFalse(reloaded.await(300, TimeUnit.MILLISECONDS), "the reload ran while frozen")

        val read = CountDownLatch(1)
        var permitted = false
        thread {
            env.users.find("alice")
            env.users.findById(userId)
            permitted = env.users.hasPermission("alice", "test.view") && env.users.hasPermissionById(userId, "test.view")
            read.countDown()
        }
        assertTrue(read.await(5, TimeUnit.SECONDS), "a read or a permission check waited behind the reload")
        assertTrue(permitted)

        frozen.end()
        assertTrue(reloaded.await(10, TimeUnit.SECONDS))
        reload.join(10_000)
        assertTrue(env.users.find("alice")!!.op)
    }
}
