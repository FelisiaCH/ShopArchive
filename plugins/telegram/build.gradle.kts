plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":shoparchive-api"))
    testImplementation(kotlin("test"))
    testImplementation(project(":shoparchive-api"))
}

tasks.jar {
    // The file the release ships; the server's tests look for this name.
    archiveFileName.set("shoparchive-telegram.jar")
}

tasks.test {
    useJUnitPlatform()
}
