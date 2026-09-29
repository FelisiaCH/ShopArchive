plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "xyz.felismp.shoparchive.spike.android"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "xyz.felismp.shoparchive.spike"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "spike-1"
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":app"))
    implementation(libs.activity.compose)
}
