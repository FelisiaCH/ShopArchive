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

// The iOS app's text (iosApp/ShopArchive/<lang>.lproj/Localizable.strings and .stringsdict) is converted from the same strings.xml files,
// never written by hand: `generateIosStrings` writes it, `checkIosStrings` (wired into `check`) fails when the committed files differ.
abstract class IosStringsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resources: DirectoryProperty

    // Not an input: generation writes here (and may create it), and the check reads it whole on every run.
    @get:Internal
    abstract val target: DirectoryProperty

    /** Every file the iOS app's text consists of: its path under [target], and what it contains. */
    protected fun render(): Map<String, String> {
        val builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val files = linkedMapOf<String, String>()
        for ((folder, language) in listOf("values" to "en", "values-lo" to "lo", "values-th" to "th")) {
            val strings = StringBuilder("/* Generated by :composeApp:generateIosStrings from composeResources/$folder; do not edit. */\n")
            val plurals = StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<!-- Generated by :composeApp:generateIosStrings from composeResources/$folder; do not edit. -->\n" +
                    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                    "<plist version=\"1.0\">\n<dict>\n",
            )
            val nodes = builder.parse(File(resources.get().asFile, "$folder/strings.xml")).documentElement.childNodes
            for (node in (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()) {
                val name = node.getAttribute("name")
                when (node.tagName) {
                    "string" -> strings.append("\"${name.quoted()}\" = \"${iosFormat(unescapeAndroid(node.textContent)).quoted()}\";\n")
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
            files["$language.lproj/Localizable.strings"] = strings.toString()
            files["$language.lproj/Localizable.stringsdict"] = plurals.toString()
        }
        return files
    }

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
abstract class GenerateIosStrings : IosStringsTask() {
    @TaskAction
    fun generate() {
        render().forEach { (path, text) ->
            val file = File(target.get().asFile, path)
            file.parentFile.mkdirs()
            file.writeText(text)
        }
    }
}

abstract class CheckIosStrings : IosStringsTask() {
    @TaskAction
    fun check() {
        val stale = render().filter { (path, text) -> File(target.get().asFile, path).takeIf { it.isFile }?.readText() != text }.keys
        if (stale.isNotEmpty()) {
            throw GradleException("iOS strings are out of date: ${stale.sorted()}\nRun ./gradlew :composeApp:generateIosStrings and commit the result.")
        }
    }
}

val iosStrings = rootProject.layout.projectDirectory.dir("iosApp/ShopArchive")

val generateIosStrings = tasks.register<GenerateIosStrings>("generateIosStrings") {
    resources = layout.projectDirectory.dir("src/commonMain/composeResources")
    target = iosStrings
}

val checkIosStrings = tasks.register<CheckIosStrings>("checkIosStrings") {
    resources = layout.projectDirectory.dir("src/commonMain/composeResources")
    target = iosStrings
    mustRunAfter(generateIosStrings)
}

tasks.named("check") { dependsOn(checkIosStrings) }
