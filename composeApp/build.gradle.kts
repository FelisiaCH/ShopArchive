import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "xyz.felismp.shoparchive.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true // required for Compose resources (Res) on this target
    }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.components.resources)
        }
        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "xyz.felismp.shoparchive.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "ShopArchive"
            packageVersion = "1.0.0"
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
