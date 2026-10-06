import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// The version this app was built as (gradle.properties), compiled in so the app can compare itself with the installers the server offers.
abstract class GenerateAppBuild : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val buildNumber: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("xyz/felismp/shoparchive/app")
        dir.mkdirs()
        dir.resolve("AppBuild.kt").writeText(
            "package xyz.felismp.shoparchive.app\n\n" +
                "import xyz.felismp.shoparchive.shared.AppVersion\n\n" +
                "/** Generated from gradle.properties: the version and build number this app was built as. */\n" +
                "object AppBuild {\n" +
                "    const val VERSION = \"${version.get()}\"\n" +
                "    const val NUMBER = ${buildNumber.get()}\n" +
                "    val version: AppVersion = checkNotNull(AppVersion.parse(\"\$VERSION-\$NUMBER\")) { \"version \$VERSION is not x.y.z\" }\n" +
                "}\n",
        )
    }
}

val generateAppBuild = tasks.register<GenerateAppBuild>("generateAppBuild") {
    version.set(project.version.toString())
    buildNumber.set(providers.gradleProperty("buildNumber").map { it.toInt() })
    outputDir.set(layout.buildDirectory.dir("generated/appBuild"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    android {
        namespace = "xyz.felismp.shoparchive.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true // required for Compose resources (Res) on this target
    }
    jvm("desktop")

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common { group("jvmShared") {
            withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm || it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm }
        } }
    }

    sourceSets {
        // The client core (pinned OkHttp, Ktor, credential store plumbing) is plain JVM code that both targets share.
        named("jvmSharedMain").configure {
          kotlin.srcDir(generateAppBuild.flatMap { it.outputDir })
          dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.websockets)
            implementation(libs.okhttp)
            implementation(libs.zxing.core)
          }
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.compose.material3)
            implementation(libs.androidx.core)
            implementation(libs.androidx.activity.compose)
        }
        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna.platform)
                implementation(libs.jmdns)
            }
        }
        named("desktopTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.okhttp.tls)
                implementation(libs.okhttp.mockwebserver)
            }
        }
    }
}

// Windows Installer only upgrades (jpackage's major upgrade) when the first three version fields grow, and it ignores a fourth, so the
// MSI is `<major>.<minor>.<buildNumber>`: buildNumber only grows over every release, which makes a new version and a same-version
// rebuild both an upgrade. This is internal to Windows Installer; the app itself compares `version` + `buildNumber`.
val msiVersion: String = run {
    val parts = project.version.toString().substringBefore('-').split('.')
    val major = parts.getOrNull(0)?.toIntOrNull()
    val minor = parts.getOrNull(1)?.toIntOrNull()
    val build = providers.gradleProperty("buildNumber").orNull?.toIntOrNull()
    check(parts.size == 3 && major != null && minor != null) { "version ${project.version} in gradle.properties is not x.y.z" }
    check(major in 0..255 && minor in 0..255) { "MSI major and minor must be 0..255 (Windows Installer), got ${project.version}" }
    check(build != null && build in 1..65535) { "buildNumber in gradle.properties must be 1..65535 (Windows Installer), got ${providers.gradleProperty("buildNumber").orNull}" }
    "$major.$minor.$build"
}

// The name an installer must have in the server's `downloads/` folder: `ShopArchive-<x.y.z>-<buildNumber>`, always with the build
// (the grammar of `parseUpdateFileName` in shared). androidApp's `releaseApk` builds the same name.
val releaseFileName = "ShopArchive-${project.version.toString().substringBefore('-')}-${providers.gradleProperty("buildNumber").get()}.msi"

// `build/release/ShopArchive-<version>-<build>.msi`: the MSI from `packageMsi`, named for `downloads/`; copy it there unchanged.
tasks.register<Sync>("releaseMsi") {
    from(tasks.named("packageMsi")) { include("*.msi") }
    rename(".*", releaseFileName)
    into(layout.buildDirectory.dir("release"))
}

tasks.withType<Test>().configureEach {
    systemProperty("shoparchive.releaseFileName", releaseFileName)
    systemProperty("shoparchive.msiVersion", msiVersion)
    systemProperty("shoparchive.version", project.version.toString())
    systemProperty("shoparchive.buildNumber", providers.gradleProperty("buildNumber").get())
}

compose.desktop {
    application {
        mainClass = "xyz.felismp.shoparchive.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "ShopArchive"
            packageVersion = msiVersion
        }
    }
}

compose.resources {
    packageOfResClass = "xyz.felismp.shoparchive.app.resources"
}

// Fails `check` when lo / th and the default (en) `strings.xml` files disagree on their keys.
abstract class CheckI18nParity : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: DirectoryProperty

    @TaskAction
    fun check() {
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()

        fun keys(folder: String): Set<String> =
            File(resources.get().asFile, folder).listFiles { file -> file.extension == "xml" }.orEmpty()
                .flatMap { file ->
                    val nodes = builder.parse(file).documentElement.childNodes
                    (0 until nodes.length).map { nodes.item(it) }
                        .filterIsInstance<org.w3c.dom.Element>()
                        .map { "${it.tagName}:${it.getAttribute("name")}" }
                }.toSet()

        val reference = keys("values")
        val problems = listOf("values-lo", "values-th").flatMap { folder ->
            val found = keys(folder)
            listOfNotNull(
                (reference - found).takeIf { it.isNotEmpty() }?.let { "$folder is missing ${it.sorted()}" },
                (found - reference).takeIf { it.isNotEmpty() }?.let { "$folder has extra ${it.sorted()}" },
            )
        }
        if (problems.isNotEmpty()) {
            throw GradleException("i18n parity failed (reference: values)\n" + problems.joinToString("\n") { "  $it" })
        }
    }
}

val checkI18nParity = tasks.register<CheckI18nParity>("checkI18nParity") {
    resources = layout.projectDirectory.dir("src/commonMain/composeResources")
}

tasks.named("check") { dependsOn(checkI18nParity) }
