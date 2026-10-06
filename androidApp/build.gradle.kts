import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

// Release signing comes from `keystore.properties` in the repository root (gitignored) or, for CI, from the environment:
// SHOPARCHIVE_KEYSTORE_FILE, SHOPARCHIVE_KEYSTORE_PASSWORD, SHOPARCHIVE_KEY_ALIAS, SHOPARCHIVE_KEY_PASSWORD. Without either the
// release build still succeeds but is unsigned (the debug key is never used for a release). How to make the key: SIGNING.md.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.reader(Charsets.UTF_8).use { load(it) }
}
fun signingValue(property: String, environment: String): String? =
    (keystoreProps.getProperty(property) ?: providers.environmentVariable(environment).orNull)?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("storeFile", "SHOPARCHIVE_KEYSTORE_FILE")?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("storePassword", "SHOPARCHIVE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SHOPARCHIVE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SHOPARCHIVE_KEY_PASSWORD") ?: releaseStorePassword
val canSignRelease = releaseKeystore != null && releaseKeystore.isFile && releaseStorePassword != null && releaseKeyAlias != null

android {
    namespace = "xyz.felismp.shoparchive"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.felismp.shoparchive"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("buildNumber").get().toInt()
        versionName = project.version.toString()
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // Minify stays off for v1: R8 would need keep rules for kotlinx.serialization, Ktor and OkHttp, and the app is
        // sideloaded, so its size is no concern. Turn it on only together with those rules and a device test.
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

if (!canSignRelease) {
    // Printed when a release is actually built, not at every configuration.
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
        doFirst {
            logger.warn(
                "WARNING: no release signing key (keystore.properties or SHOPARCHIVE_KEYSTORE_* variables, see androidApp/SIGNING.md): " +
                    "the release APK/AAB is UNSIGNED and cannot be installed until it is signed."
            )
        }
    }
}

// `build/release/ShopArchive-<version>-<build>.apk`: the release APK (signed or not), named for the server's `downloads/` folder
// (`ShopArchive-<x.y.z>-<buildNumber>`, the same name composeApp's `releaseMsi` builds); copy it there unchanged.
val releaseApkName = "ShopArchive-${project.version.toString().substringBefore('-')}-${providers.gradleProperty("buildNumber").get()}.apk"
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        tasks.register<Sync>("releaseApk") {
            from(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)) { include("*.apk") }
            rename(".*", releaseApkName)
            into(layout.buildDirectory.dir("release"))
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
