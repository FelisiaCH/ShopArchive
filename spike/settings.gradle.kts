rootProject.name = "shoparchive-spike"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// The Android modules need an SDK even just to configure. Same lookup order AGP uses;
// without an SDK the build is desktop + server only (iOS klibs need a Mac anyway).
val sdkFromLocalProperties = file("local.properties").takeIf { it.isFile }
    ?.let { f -> java.util.Properties().also { p -> f.reader().use(p::load) }.getProperty("sdk.dir") }
val androidSdk = listOfNotNull(sdkFromLocalProperties, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
    .firstOrNull { File(it).isDirectory }
gradle.extra["spike.androidSdk"] = androidSdk

include(":server", ":app")
if (androidSdk != null) include(":androidApp")
