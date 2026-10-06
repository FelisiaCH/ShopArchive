package xyz.felismp.shoparchive.app

import kotlin.test.Test
import xyz.felismp.shoparchive.shared.AppVersion
import xyz.felismp.shoparchive.shared.UpdatePlatform
import xyz.felismp.shoparchive.shared.parseUpdateFileName
import kotlin.test.assertEquals

/** The MSI version Windows Installer sees must grow with every build, also when `version` stays (gradle.properties, build.gradle.kts). */
class MsiVersionTest {
    @Test
    fun `the MSI version is major, minor and the build number`() {
        val version = checkNotNull(System.getProperty("shoparchive.version"))
        val build = checkNotNull(System.getProperty("shoparchive.buildNumber"))
        val (major, minor) = version.substringBefore('-').split('.')
        assertEquals("$major.$minor.$build", System.getProperty("shoparchive.msiVersion"))
    }

    @Test
    fun `the release file name is one the server and the app read back as this version and build`() {
        val name = checkNotNull(System.getProperty("shoparchive.releaseFileName"))
        val parsed = checkNotNull(parseUpdateFileName(name)) { "$name is not an update file name" }
        val version = checkNotNull(System.getProperty("shoparchive.version")).substringBefore('-')
        val build = checkNotNull(System.getProperty("shoparchive.buildNumber")).toInt()
        assertEquals(UpdatePlatform.WINDOWS, parsed.platform)
        assertEquals(checkNotNull(AppVersion.parse(version)).copy(build = build), parsed.version)
    }
}
