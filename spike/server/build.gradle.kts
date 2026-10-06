import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j2)
    implementation(libs.terminalconsole)
    implementation(libs.jline.reader)
    implementation(libs.jline.terminal)
    implementation(libs.jline.terminal.jna)
    implementation(libs.jmdns)
}

tasks.shadowJar {
    archiveFileName = "spike-server.jar"
    manifest { attributes("Main-Class" to "xyz.felismp.shoparchive.spike.server.MainKt", "Multi-Release" to "true") }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE // let the merge transformers below see every duplicate
    mergeServiceFiles()
    transform(Log4j2PluginsCacheFileTransformer::class.java)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

// spike-server.jar + start.bat + start.sh side by side (T3/T4 copy this folder).
tasks.register<Sync>("dist") {
    from(tasks.shadowJar)
    from("scripts")
    into(layout.buildDirectory.dir("dist"))
}
