plugins {
    // Same Kotlin as the server (libs.versions.toml), on purpose: the server refuses a plugin built with a newer Kotlin
    // than its own, and provides the Kotlin runtime itself, so the jar must not contain it.
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // compileOnly: the server brings the api (and Kotlin) when it loads the plugin. Never implementation/shadow it:
    // a jar with kotlin/ or kotlinx/ inside is rejected.
    compileOnly(project(":shoparchive-api"))
}

tasks.jar {
    // A fixed name so the server's tests can find the jar; rename it when you copy this folder.
    archiveFileName.set("template.jar")
}
