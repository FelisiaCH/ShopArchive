package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.shared.UpdatePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsUpdaterTest {
    private val dir: Path = Files.createTempDirectory("sa-update")

    @Test
    fun theWindowsInstallerRunsMsiexecOnTheFileAndThenClosesTheApp() {
        val calls = mutableListOf<String>()
        val updater = WindowsUpdater(dir.resolve("u"), start = { calls += it.joinToString(" ") }, exit = { calls += "exit" })
        val result = kotlinx.coroutines.runBlocking { updater.install(dir.resolve("ShopArchive-1.2.0.msi")) }
        assertEquals(Install.Started, result)
        assertEquals(listOf("msiexec /i ${dir.resolve("ShopArchive-1.2.0.msi")}", "exit"), calls)
        assertEquals(UpdatePlatform.WINDOWS, updater.platform)
    }
}
