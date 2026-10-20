plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    jvm()
    android {
        namespace = "xyz.felismp.shoparchive.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common { group("jvmShared") {
            withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm || it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm }
        } }
    }

    sourceSets {
        // The client core: API client, login and trust on first use, reconnect, and the state of every screen. No UI and no platform API in
        // commonMain; what needs OkHttp, java.io or java.nio (pinning, files, in-app updates) is in jvmSharedMain.
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        named("jvmSharedMain").configure {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.okhttp)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        named("jvmTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.okhttp.tls)
                implementation(libs.okhttp.mockwebserver)
            }
        }
    }
}
