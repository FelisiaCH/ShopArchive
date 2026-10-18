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
        // The screens and the wiring of the platform pieces are plain JVM code that both targets share; the client core is :clientCore.
        named("jvmSharedMain").configure {
          kotlin.srcDir(generateAppBuild.flatMap { it.outputDir })
          dependencies {
            implementation(project(":clientCore"))
            implementation(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
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

// The iOS app's resources are generated, never written by hand: the text (iosApp/ShopArchive/<lang>.lproj/Localizable.strings, .stringsdict and
// InfoPlist.strings) is converted from the same strings.xml files, and iosApp/AppInfo.plist, which Xcode (project.yml) and xtool (xtool.yml) both
// read, carries the version numbers derived in the root build.gradle.kts. `generateIosResources` writes them, `checkIosResources` (wired into
// `check`) fails when the committed files differ.
abstract class IosResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: DirectoryProperty

    /** CFBundleShortVersionString: Apple allows only digits and dots, so a `-betaNNN` is left to [buildCode]. */
    @get:Input
    abstract val shortVersion: Property<String>

    /** CFBundleVersion: the `appCode` of the root build.gradle.kts. */
    @get:Input
    abstract val buildCode: Property<Int>

    // Not an input: generation writes here (and may create it), and the check reads it whole on every run.
    @get:Internal
    abstract val target: DirectoryProperty

    /** Every file of the iOS app's resources: its path under [target], and what it contains. */
    protected fun render(): Map<String, String> {
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val files = linkedMapOf<String, String>()
        var permBodyEn = ""
        var permCameraEn = ""
        for ((folder, language) in listOf("values" to "en", "values-lo" to "lo", "values-th" to "th")) {
            val strings = StringBuilder("/* Generated by :composeApp:generateIosResources from composeResources/$folder; do not edit. */\n")
            val plurals = StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<!-- Generated by :composeApp:generateIosResources from composeResources/$folder; do not edit. -->\n" +
                    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                    "<plist version=\"1.0\">\n<dict>\n",
            )
            var permBody: String? = null
            var permCamera: String? = null
            val nodes = builder.parse(File(resources.get().asFile, "$folder/strings.xml")).documentElement.childNodes
            for (node in (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()) {
                val name = node.getAttribute("name")
                when (node.tagName) {
                    "string" -> {
                        val text = iosFormat(unescapeAndroid(node.textContent))
                        strings.append("\"${name.quoted()}\" = \"${text.quoted()}\";\n")
                        if (name == "perm_body_ios") permBody = text
                        if (name == "perm_camera_ios") permCamera = text
                    }
                    "plurals" -> {
                        plurals.append("\t<key>${name.xml()}</key>\n\t<dict>\n\t\t<key>NSStringLocalizedFormatKey</key>\n\t\t<string>%#@count@</string>\n")
                        plurals.append("\t\t<key>count</key>\n\t\t<dict>\n\t\t\t<key>NSStringFormatSpecTypeKey</key>\n\t\t\t<string>NSStringPluralRuleType</string>\n")
                        plurals.append("\t\t\t<key>NSStringFormatValueTypeKey</key>\n\t\t\t<string>d</string>\n")
                        val items = node.getElementsByTagName("item")
                        for (i in 0 until items.length) {
                            val item = items.item(i) as org.w3c.dom.Element
                            // A plural has one argument, the count, so its text takes it as plain %d.
                            val text = iosFormat(unescapeAndroid(item.textContent))
                            if ("%1\$d" !in text || '%' in text.replace("%1\$d", "")) throw GradleException("plural $name in $folder must use only %1\$d: $text")
                            plurals.append("\t\t\t<key>${item.getAttribute("quantity").xml()}</key>\n\t\t\t<string>${text.replace("%1\$d", "%d").xml()}</string>\n")
                        }
                        plurals.append("\t\t</dict>\n\t</dict>\n")
                    }
                }
            }
            plurals.append("</dict>\n</plist>\n")
            files["ShopArchive/$language.lproj/Localizable.strings"] = strings.toString()
            files["ShopArchive/$language.lproj/Localizable.stringsdict"] = plurals.toString()
            if (permBody == null) throw GradleException("perm_body_ios is missing in $folder")
            if (permCamera == null) throw GradleException("perm_camera_ios is missing in $folder")
            files["ShopArchive/$language.lproj/InfoPlist.strings"] =
                "/* Generated by :composeApp:generateIosResources from composeResources/$folder; do not edit. */\n" +
                    "\"NSLocalNetworkUsageDescription\" = \"${permBody.quoted()}\";\n" +
                    "\"NSCameraUsageDescription\" = \"${permCamera.quoted()}\";\n"
            if (language == "en") {
                permBodyEn = permBody
                permCameraEn = permCamera
            }
        }
        files["AppInfo.plist"] = appInfo(permBodyEn, permCameraEn)
        // Xcode writes the version keys from these build settings over AppInfo.plist's (xtool keeps the plist's), so both carry the same numbers.
        files["Version.xcconfig"] = "// Generated by :composeApp:generateIosResources from gradle.properties; do not edit. Read by project.yml.\n" +
            "MARKETING_VERSION = ${shortVersion.get()}\nCURRENT_PROJECT_VERSION = ${buildCode.get()}\n"
        return files
    }

    // The app's own Info.plist keys; Xcode and xtool each add their standard ones (bundle id, executable). The ATS exception is for the
    // shop server: it answers on a LAN or public IP with a self-signed key, which the app pins itself.
    private fun appInfo(localNetworkText: String, cameraText: String) = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- Generated by :composeApp:generateIosResources; do not edit. Read by Xcode (INFOPLIST_FILE in project.yml) and xtool (infoPath in xtool.yml). -->
<plist version="1.0">
<dict>
	<key>CFBundleDisplayName</key>
	<string>ShopArchive</string>
	<key>CFBundleDevelopmentRegion</key>
	<string>en</string>
	<key>CFBundleLocalizations</key>
	<array>
		<string>en</string>
		<string>lo</string>
		<string>th</string>
	</array>
	<key>CFBundleShortVersionString</key>
	<string>${shortVersion.get().xml()}</string>
	<key>CFBundleVersion</key>
	<string>${buildCode.get()}</string>
	<key>UIDeviceFamily</key>
	<array>
		<integer>1</integer>
	</array>
	<key>UILaunchScreen</key>
	<dict/>
	<key>UISupportedInterfaceOrientations</key>
	<array>
		<string>UIInterfaceOrientationPortrait</string>
		<string>UIInterfaceOrientationLandscapeLeft</string>
		<string>UIInterfaceOrientationLandscapeRight</string>
	</array>
	<key>NSAppTransportSecurity</key>
	<dict>
		<key>NSAllowsArbitraryLoads</key>
		<true/>
	</dict>
	<key>NSBonjourServices</key>
	<array>
		<string>_shoparchive._tcp</string>
	</array>
	<key>NSLocalNetworkUsageDescription</key>
	<string>${localNetworkText.xml()}</string>
	<key>NSCameraUsageDescription</key>
	<string>${cameraText.xml()}</string>
</dict>
</plist>
"""

    // Android's escapes in a string resource (the XML parser has already decoded the entities and CDATA).
    private fun unescapeAndroid(text: String) = Regex("""\\(.)""").replace(text) {
        when (val c = it.groupValues[1]) {
            "n" -> "\n"
            "t" -> "\t"
            "'", "\"", "@", "?", "\\" -> c
            else -> throw GradleException("unsupported escape \\$c in '$text'")
        }
    }

    // %1$s -> %1$@ and %s -> %@; %d and %1$d are the same on both sides, and %% stays a percent sign.
    private fun iosFormat(text: String) = Regex("""%%|%(\d+\$)?([sd])""").replace(text) {
        if (it.value == "%%") it.value else "%${it.groupValues[1]}${if (it.groupValues[2] == "s") "@" else "d"}"
    }

    private fun String.quoted() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun String.xml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

@UntrackedTask(because = "writes next to the Swift sources of the iOS app")
abstract class GenerateIosResources : IosResourcesTask() {
    @TaskAction
    fun generate() {
        render().forEach { (path, text) ->
            val file = File(target.get().asFile, path)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
    }
}

abstract class CheckIosResources : IosResourcesTask() {
    @TaskAction
    fun check() {
        val stale = render().filter { (path, text) -> File(target.get().asFile, path).takeIf { it.isFile }?.readText() != text }.keys
        if (stale.isNotEmpty()) {
            throw GradleException("iOS resources are out of date: ${stale.sorted()}\nRun ./gradlew :composeApp:generateIosResources and commit the result.")
        }
    }
}

val iosApp = rootProject.layout.projectDirectory.dir("iosApp")

val generateIosResources = tasks.register<GenerateIosResources>("generateIosResources") {
    resources = layout.projectDirectory.dir("src/commonMain/composeResources")
    shortVersion = version.toString().substringBefore('-')
    buildCode = rootProject.extra["appCode"] as Int
    target = iosApp
}

val checkIosResources = tasks.register<CheckIosResources>("checkIosResources") {
    resources = layout.projectDirectory.dir("src/commonMain/composeResources")
    shortVersion = version.toString().substringBefore('-')
    buildCode = rootProject.extra["appCode"] as Int
    target = iosApp
    mustRunAfter(generateIosResources)
}

tasks.named("check") { dependsOn(checkIosResources) }
