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
    // Not shipped with a release: a channel example and a test. The server's tests look for this name.
    archiveFileName.set("example-discord.jar")
}

tasks.test {
    useJUnitPlatform()
}
