plugins {
    // Same Kotlin as the server (libs.versions.toml), on purpose, like plugins/template: the server provides the Kotlin runtime.
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // compileOnly: only the two annotations are needed, and the jar must not contain kotlin/ or kotlinx/.
    compileOnly(project(":shoparchive-api"))
}

tasks.jar {
    archiveFileName.set("hotfix-template.jar")
}
