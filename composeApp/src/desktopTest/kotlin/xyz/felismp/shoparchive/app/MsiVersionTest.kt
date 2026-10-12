package xyz.felismp.shoparchive.app

import kotlin.test.Test
import xyz.felismp.shoparchive.shared.AppVersion
import xyz.felismp.shoparchive.shared.UpdatePlatform
import xyz.felismp.shoparchive.shared.parseUpdateFileName
import kotlin.test.assertEquals

/** The MSI version Windows Installer sees must grow with every release and every beta (gradle.properties, root build.gradle.kts). */
class MsiVersionTest {
    @Test
    fun `the MSI version is x, y and z times 1000 plus the beta number, 999 for a final`() {
        val version = checkNotNull(AppVersion.parse(checkNotNull(System.getProperty("shoparchive.version"))))
        val last = version.patch * 1000 + if (version.beta > 0) version.beta else 999
        assertEquals("${version.major}.${version.minor}.$last", System.getProperty("shoparchive.msiVersion"))
    }

    @Test
    fun `the release file name is one the server and the app read back as this version`() {
        val name = checkNotNull(System.getProperty("shoparchive.releaseFileName"))
        val parsed = checkNotNull(parseUpdateFileName(name)) { "$name is not an update file name" }
        assertEquals(UpdatePlatform.WINDOWS, parsed.platform)
        assertEquals(AppVersion.parse(checkNotNull(System.getProperty("shoparchive.version"))), parsed.version)
    }
}
