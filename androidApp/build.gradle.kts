plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "xyz.felismp.shoparchive"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.felismp.shoparchive"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
