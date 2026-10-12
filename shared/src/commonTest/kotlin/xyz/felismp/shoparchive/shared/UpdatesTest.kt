package xyz.felismp.shoparchive.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    @Test
    fun theFileNameRule() {
        assertEquals(UpdateFileName(UpdatePlatform.ANDROID, AppVersion(1, 0, 1)), parseUpdateFileName("ShopArchive-1.0.1.apk"))
        assertEquals(UpdateFileName(UpdatePlatform.WINDOWS, AppVersion(1, 2, 0, 2)), parseUpdateFileName("ShopArchive-1.2.0-beta002.msi"))
        assertEquals(UpdatePlatform.WINDOWS, parseUpdateFileName("ShopArchive-1.2.0.MSI")?.platform)
        for (bad in listOf(
            "ShopArchive-1.0.apk", "ShopArchive-1.0.1.zip", "ShopArchive-1.0.1.apk.txt", "shoparchive-1.0.1.apk", "ShopArchive-latest.apk",
            "../ShopArchive-1.0.1.apk", "ShopArchive-1.0.1.apk/x", "ShopArchive-1.0.1-.apk", "ShopArchive-12345.0.1.apk", "ShopArchive-1.0.1-dev.apk", " ShopArchive-1.0.1.apk", "",
            "ShopArchive-1.0.1-4.apk", "ShopArchive-1.2.0-2.msi", "ShopArchive-1.0.1-beta1.apk", "ShopArchive-1.0.1-beta0000.apk", "ShopArchive-1.0.1-beta000.apk",
            "ShopArchive-1.0.1-beta999.apk",
        )) assertNull(parseUpdateFileName(bad), bad)
    }

    @Test
    fun versionsCompareByNumbersNotText() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 0))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertTrue(AppVersion(1, 0, 1) > AppVersion(1, 0, 0, 9), "a higher version wins over any beta")
        assertTrue(AppVersion(1, 0, 0, 2) > AppVersion(1, 0, 0, 1))
        assertEquals(AppVersion(1, 0, 0, 1), AppVersion(1, 0, 0, 1))
    }

    @Test
    fun aBetaIsOlderThanTheFinalOfTheSameVersionAndNewerThanThePreviousFinal() {
        val beta1 = AppVersion(0, 1, 1, 1)
        val beta2 = AppVersion(0, 1, 1, 2)
        val final = AppVersion(0, 1, 1)
        assertTrue(beta1 < beta2)
        assertTrue(beta2 < final)
        assertTrue(beta1 < final)
        assertTrue(AppVersion(0, 1, 0) < beta1)
        assertTrue(AppVersion(0, 1, 1, 998) < final)
        val shuffled = listOf(final, AppVersion(0, 1, 2, 1), beta2, AppVersion(0, 1, 0), beta1)
        assertEquals(listOf(AppVersion(0, 1, 0), beta1, beta2, final, AppVersion(0, 1, 2, 1)), shuffled.sorted())
    }

    @Test
    fun parseAndPrint() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(1, 2, 3, 4), AppVersion.parse(" 1.2.3-beta004 "))
        assertEquals(AppVersion(0, 1, 1, 998), AppVersion.parse("0.1.1-beta998"))
        assertNull(AppVersion.parse("1.2"))
        assertNull(AppVersion.parse("1.0.0-dev"))
        assertNull(AppVersion.parse("v1.0.0"))
        for (bad in listOf("1.2.3-4", "1.2.3-beta1", "1.2.3-beta0000", "1.2.3-beta000", "1.2.3-beta999", "1.2.3-Beta001")) assertNull(AppVersion.parse(bad), bad)
        assertEquals("1.2.3-beta004", AppVersion(1, 2, 3, 4).toString())
        assertEquals("1.2.3", AppVersion(1, 2, 3).toString())
        for (text in listOf("0.1.1", "0.1.1-beta001", "209.99.64-beta998")) assertEquals(text, AppVersion.parse(text).toString())
    }

    private fun file(name: String, size: Long = 1) = parseUpdateFileName(name)!!.let {
        UpdateFile(name, it.platform, "${it.version.major}.${it.version.minor}.${it.version.patch}", it.version.beta, size, "00")
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
    fun theFinalIsOfferedOverItsBetasAndABetaOverThePreviousFinal() {
        val files = listOf(file("ShopArchive-0.1.0.apk"), file("ShopArchive-0.1.1-beta001.apk"), file("ShopArchive-0.1.1-beta002.apk"), file("ShopArchive-0.1.1.apk"))
        assertEquals("ShopArchive-0.1.1.apk", newestUpdate(files, UpdatePlatform.ANDROID, AppVersion(0, 1, 0))?.file)
        assertEquals("ShopArchive-0.1.1.apk", newestUpdate(files, UpdatePlatform.ANDROID, AppVersion(0, 1, 1, 2))?.file, "the final is newer than the app's own beta")
        assertNull(newestUpdate(files, UpdatePlatform.ANDROID, AppVersion(0, 1, 1)))
        val betas = files.filter { it.build > 0 }
        assertEquals("ShopArchive-0.1.1-beta002.apk", newestUpdate(betas, UpdatePlatform.ANDROID, AppVersion(0, 1, 0))?.file)
        assertEquals("ShopArchive-0.1.1-beta002.apk", newestUpdate(betas, UpdatePlatform.ANDROID, AppVersion(0, 1, 1, 1))?.file)
        assertNull(newestUpdate(betas, UpdatePlatform.ANDROID, AppVersion(0, 1, 1, 2)))
    }

    @Test
    fun aFinalGoesOverTheWireAsBuild0SoAppsOf010AcceptIt() {
        val json = Json.encodeToString(UpdateFile.serializer(), file("ShopArchive-0.1.1.apk"))
        assertTrue("\"version\":\"0.1.1\"" in json && "\"build\":0" in json, json)
        assertEquals(1, file("ShopArchive-0.1.1-beta001.apk").build)
        assertEquals("0.1.1", file("ShopArchive-0.1.1-beta001.apk").version)
    }

    @Test
    fun aFileWhoseNameDisagreesWithItsVersionOrBetaIsSkipped() {
        val wrongBeta = file("ShopArchive-0.1.1-beta001.apk").copy(build = 2)
        val noBeta = file("ShopArchive-0.1.1-beta001.apk").copy(build = 0)
        assertNull(newestUpdate(listOf(wrongBeta, noBeta), UpdatePlatform.ANDROID, AppVersion(0, 1, 0)))
        assertNull(file("ShopArchive-0.1.1.apk").copy(build = 999).appVersion())
    }

    @Test
    fun aFileWhoseVersionTheAppCannotReadIsSkipped() {
        val odd = UpdateFile("x.apk", UpdatePlatform.ANDROID, "banana", 0, 1, "00")
        assertNull(newestUpdate(listOf(odd), UpdatePlatform.ANDROID, AppVersion(0, 0, 1)))
    }
}
