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

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("xyz/felismp/shoparchive/app")
        dir.mkdirs()
        dir.resolve("AppBuild.kt").writeText(
            "package xyz.felismp.shoparchive.app\n\n" +
                "import xyz.felismp.shoparchive.shared.AppVersion\n\n" +
                "/** Generated from gradle.properties: the version this app was built as. */\n" +
                "object AppBuild {\n" +
                "    const val VERSION = \"${version.get()}\"\n" +
                "    val version: AppVersion = checkNotNull(AppVersion.parse(VERSION)) { \"version \$VERSION is not x.y.z or x.y.z-betaNNN\" }\n" +
                "}\n",
        )
    }
}

val generateAppBuild = tasks.register<GenerateAppBuild>("generateAppBuild") {
    version.set(project.version.toString())
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
            implementation(libs.mlkit.barcode.scanning)
            implementation(libs.mlkit.text.recognition)
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
// MSI is `<x>.<y>.<z*1000 + beta>` (beta 999 for a final), worked out in the root build.gradle.kts: every release and every beta is an upgrade.
// This is internal to Windows Installer; the app itself compares `version`.
val msiVersion = rootProject.extra["msiVersion"] as String

// The name an installer must have in the server's `downloads/` folder: `ShopArchive-<version>` (the grammar of `parseUpdateFileName`
// in shared). androidApp's `releaseApk` builds the same name.
val releaseFileName = "ShopArchive-${project.version}.msi"

// The MSI that is handed out. jpackage packs the app image `createDistributable` made, with `packaging/windows` as its resource
// directory: its main.wxs (jpackage's own from JDK 21, plus an uninstall that also removes the app's data) replaces jpackage's.
// Compose's `packageMsi` cannot take one (it empties the resource directory it gives jpackage), so it is not used for releases.
// The options match what `packageMsi` passes, so the UpgradeCode, and with it upgrading an installed copy, stays the same.
val msiDir = layout.buildDirectory.dir("compose/binaries/main/msi-release")
// The WiX that `packageMsi` uses too: WIX_PATH when it is set (Compose then downloads none), else the one Compose unzips.
val wixPath: String? = System.getenv("WIX_PATH")
val wixDir = wixPath?.let { File(it) } ?: rootProject.layout.buildDirectory.dir("wix311").get().asFile
val windowsPackaging = layout.projectDirectory.dir("packaging/windows")
val msi = tasks.register<Exec>("msi") {
    dependsOn("createDistributable")
    if (wixPath == null) dependsOn(":unzipWix")
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/ShopArchive")
    inputs.dir(appImage)
    inputs.dir(windowsPackaging)
    outputs.dir(msiDir)
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage.exe").path
    executable = jpackage
    args(
        "--type", "msi", "--app-image", appImage.get().asFile.path, "--name", "ShopArchive", "--app-version", msiVersion,
        "--icon", project.file("icons/shoparchive.ico").path, "--resource-dir", windowsPackaging.asFile.path,
        "--win-dir-chooser", "--win-shortcut", "--win-shortcut-prompt", "--win-menu", "--win-menu-group", "ShopArchive", "--dest", msiDir.get().asFile.path,
    )
    environment("PATH", wixDir.path + File.pathSeparator + System.getenv("PATH"))
    val uninstallIcon = project.file("icons/shoparchive-uninstall.ico")
    inputs.file(uninstallIcon)
    environment("SHOPARCHIVE_UNINSTALL_ICON", uninstallIcon.path) // read by main.wxs
    val out = msiDir.get().asFile
    doFirst { out.deleteRecursively() } // jpackage refuses to overwrite
}

// `build/release/ShopArchive-<version>.msi`: the MSI from `msi`, named for `downloads/`; copy it there unchanged.
tasks.register<Sync>("releaseMsi") {
    from(msi) { include("*.msi") }
    rename(".*", releaseFileName)
    into(layout.buildDirectory.dir("release"))
}

tasks.withType<Test>().configureEach {
    systemProperty("shoparchive.releaseFileName", releaseFileName)
    systemProperty("shoparchive.msiVersion", msiVersion)
    systemProperty("shoparchive.version", project.version.toString())
}

compose.desktop {
    application {
        mainClass = "xyz.felismp.shoparchive.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "ShopArchive"
            packageVersion = msiVersion
            windows {
                iconFile.set(project.file("icons/shoparchive.ico"))
                // Found where Windows apps are: on the desktop and in the Start menu, whose entry also offers Uninstall (as Settings > Apps does).
                shortcut = true
                menu = true
                menuGroup = "ShopArchive"
            }
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
