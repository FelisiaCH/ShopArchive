import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Plugins are declared once here so every module shares one classloader.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.skie) apply false
}

// A failing test prints its assertion message and full stack trace in the build output, so a CI log alone says what broke.
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

// `version` in gradle.properties is `x.y.z` or `x.y.z-betaNNN`; the numbers every artifact needs are derived here once, never set by hand.
// Anything outside the limits fails the build, since a wrong number would only show later as an APK or MSI that does not upgrade.
//   appCode: the Android versionCode and the core build (hotfix target-build): (x*10000 + y*100 + z)*1000 + beta, 999 for a final.
//   msiVersion: x.y.(z*1000 + beta, 999 for a final); Windows Installer reads the first three fields, each one 255 / 255 / 65535 at most.
val versionMatch = Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,3})\.(0|[1-9]\d{0,3})(?:-beta(\d{3}))?""").matchEntire(version.toString())
val (versionX, versionY, versionZ) = versionMatch?.groupValues?.drop(1)?.take(3)?.map { it.toInt() } ?: listOf(-1, -1, -1)
val versionBeta = versionMatch?.groupValues?.get(4)?.takeIf { it.isNotEmpty() }?.toInt() // null: a final
if (versionMatch == null || versionX > 209 || versionY > 99 || versionZ > 64 || (versionBeta != null && versionBeta !in 1..998)) {
    throw GradleException(
        "version in gradle.properties must be x.y.z or x.y.z-betaNNN with x 0..209, y 0..99, z 0..64 and NNN 001..998, got '$version'"
    )
}
val versionTail = versionBeta ?: 999
extra["appCode"] = (versionX * 10000 + versionY * 100 + versionZ) * 1000 + versionTail
extra["msiVersion"] = "$versionX.$versionY.${versionZ * 1000 + versionTail}"
