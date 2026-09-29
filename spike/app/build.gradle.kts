import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

// Android needs an SDK to even configure (see settings.gradle.kts), so the target is optional.
val androidEnabled = gradle.extra["spike.androidSdk"] != null
if (androidEnabled) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    jvmToolchain(21)

    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "SpikeShared" // Swift: `import SpikeShared`
            isStatic = true
        }
    }
    if (androidEnabled) {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            namespace = "xyz.felismp.shoparchive.spike.app"
            compileSdk = 37
            minSdk = 26
        }
    }

    // OkHttp + X509TrustManager pinning is identical on Android and desktop: one source dir, two compilations.
    val okhttpPinning = layout.projectDirectory.dir("src/okhttpPinning/kotlin")
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.coroutines.core)
        }
        getByName("desktopMain") {
            kotlin.srcDir(okhttpPinning)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.coroutines.swing)
            }
        }
        getByName("desktopTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.network.tls.certificates)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        if (androidEnabled) {
            getByName("androidMain") {
                kotlin.srcDir(okhttpPinning)
                dependencies {
                    implementation(libs.ktor.client.okhttp)
                    implementation(libs.activity.compose)
                    implementation(libs.coroutines.android)
                }
            }
        }
    }
}

compose.desktop {
    application { mainClass = "xyz.felismp.shoparchive.spike.app.MainKt" }
}
