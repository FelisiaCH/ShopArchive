package xyz.felismp.shoparchive.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    @Test
    fun theFileNameRule() {
        assertEquals(UpdateFileName(UpdatePlatform.ANDROID, AppVersion(1, 0, 1)), parseUpdateFileName("ShopArchive-1.0.1.apk"))
        assertEquals(UpdateFileName(UpdatePlatform.WINDOWS, AppVersion(1, 2, 0, 2)), parseUpdateFileName("ShopArchive-1.2.0-2.msi"))
        assertEquals(UpdatePlatform.WINDOWS, parseUpdateFileName("ShopArchive-1.2.0.MSI")?.platform)
        for (bad in listOf(
            "ShopArchive-1.0.apk", "ShopArchive-1.0.1.zip", "ShopArchive-1.0.1.apk.txt", "shoparchive-1.0.1.apk", "ShopArchive-latest.apk",
            "../ShopArchive-1.0.1.apk", "ShopArchive-1.0.1.apk/x", "ShopArchive-1.0.1-.apk", "ShopArchive-12345.0.1.apk", "ShopArchive-1.0.1-dev.apk", " ShopArchive-1.0.1.apk", "",
        )) assertNull(parseUpdateFileName(bad), bad)
    }

    @Test
    fun versionsCompareByNumbersNotText() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 0))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertTrue(AppVersion(1, 0, 1) > AppVersion(1, 0, 0, 9), "a higher version wins over any build")
        assertTrue(AppVersion(1, 0, 0, 2) > AppVersion(1, 0, 0, 1))
        assertTrue(AppVersion(1, 0, 0, 1) > AppVersion(1, 0, 0), "no build counts as 0")
        assertEquals(AppVersion(1, 0, 0, 1), AppVersion(1, 0, 0, 1))
    }

    @Test
    fun parseAndPrint() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(1, 2, 3, 4), AppVersion.parse(" 1.2.3-4 "))
        assertNull(AppVersion.parse("1.2"))
        assertNull(AppVersion.parse("1.0.0-dev"))
        assertNull(AppVersion.parse("v1.0.0"))
        assertEquals("1.2.3-4", AppVersion(1, 2, 3, 4).toString())
        assertEquals("1.2.3", AppVersion(1, 2, 3).toString())
    }

    private fun file(name: String, size: Long = 1) = parseUpdateFileName(name)!!.let {
        UpdateFile(name, it.platform, "${it.version.major}.${it.version.minor}.${it.version.patch}", it.version.build, size, "00")
    }

    @Test
    fun theNewestFileOfThisPlatformThatIsNewerThanTheApp() {
        val files = listOf(file("ShopArchive-1.0.0.apk"), file("ShopArchive-1.1.0.apk"), file("ShopArchive-1.3.0.msi"), file("ShopArchive-1.0.5.apk"))
        assertEquals("ShopArchive-1.1.0.apk", newestUpdate(files, UpdatePlatform.ANDROID, AppVersion(1, 0, 0, 1))?.file)
        assertEquals("ShopArchive-1.3.0.msi", newestUpdate(files, UpdatePlatform.WINDOWS, AppVersion(1, 0, 0, 1))?.file)
        assertNull(newestUpdate(files, UpdatePlatform.ANDROID, AppVersion(1, 1, 0)), "equal is not newer")
        assertNull(newestUpdate(files, UpdatePlatform.ANDROID, AppVersion(2, 0, 0)))
        assertNull(newestUpdate(emptyList(), UpdatePlatform.ANDROID, AppVersion(0, 0, 0)))
    }

    @Test
    fun aSameVersionFileWithoutBuildIsNotNewerThanTheInstalledBuild() {
        // The app 1.0.0 build 1 against ShopArchive-1.0.0.apk (build 0): nothing to offer; against -2 there is.
        assertNull(newestUpdate(listOf(file("ShopArchive-1.0.0.apk")), UpdatePlatform.ANDROID, AppVersion(1, 0, 0, 1)))
        assertEquals("ShopArchive-1.0.0-2.apk", newestUpdate(listOf(file("ShopArchive-1.0.0-2.apk")), UpdatePlatform.ANDROID, AppVersion(1, 0, 0, 1))?.file)
    }

    @Test
    fun aFileWhoseVersionTheAppCannotReadIsSkipped() {
        val odd = UpdateFile("x.apk", UpdatePlatform.ANDROID, "banana", 0, 1, "00")
        assertNull(newestUpdate(listOf(odd), UpdatePlatform.ANDROID, AppVersion(0, 0, 1)))
    }
}
