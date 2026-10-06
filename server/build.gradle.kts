plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":shoparchive-api"))
    implementation(libs.kaml)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.zxing.core)
    implementation(libs.fastexcel)
    implementation(libs.jmdns)
    implementation(libs.log4j.api)
    implementation(libs.log4j.core)
    implementation(libs.terminal.console.appender)
    runtimeOnly(libs.jline.terminal.jna)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.fastexcel.reader)
}

// project.version is only valid at configuration time; capture it in a local so the manifest block
// below doesn't capture the Project itself (the configuration cache requires that).
val implementationVersion = project.version.toString()

// shoparchive-build.properties: the build number and the merged fix IDs. The launcher reads it from the jar to judge hotfixes
// before the core runs; the core shows it in the boot log and `status`.
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val build: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fixedIssues: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun generate() {
        val number = build.get().trim().toIntOrNull()?.takeIf { it >= 1 }
            ?: throw GradleException("buildNumber in gradle.properties must be a whole number from 1")
        val ids = fixedIssues.get().asFile.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        val bad = ids.filter { !Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(it) }
        if (bad.isNotEmpty()) throw GradleException("server/fixed-issues.txt has lines that are not an ID: $bad")
        val out = output.get().asFile
        out.parentFile.mkdirs()
        out.writeText("build=$number\nfixed-issues=${ids.joinToString(",")}\n")
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
    build.set(providers.gradleProperty("buildNumber"))
    fixedIssues.set(layout.projectDirectory.file("fixed-issues.txt"))
    output.set(layout.buildDirectory.file("generated/buildinfo/shoparchive-build.properties"))
}
tasks.processResources { from(generateBuildInfo) }

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "ShopArchive",
            "Implementation-Version" to implementationVersion,
        )
    }
}

tasks.test {
    useJUnitPlatform()
    // Keep JUnit @TempDir (and anything else using java.io.tmpdir) inside the build dir instead of the
    // system temp dir. A local plain File, so doFirst doesn't capture the Project (configuration cache).
    val testTmpDir = layout.buildDirectory.get().asFile.resolve("test-tmp")
    systemProperty("java.io.tmpdir", testTmpDir.absolutePath)
    doFirst { testTmpDir.mkdirs() }
    // PluginTemplateTest loads the template plugin as built, so the template cannot rot unnoticed.
    dependsOn(":plugins:template:jar", ":plugins:telegram:jar", ":plugins:example-discord:jar", ":plugins:hotfix-template:jar")
    systemProperty("shoparchive.telegramJar", rootProject.file("plugins/telegram/build/libs/shoparchive-telegram.jar").absolutePath)
    systemProperty("shoparchive.discordJar", rootProject.file("plugins/example-discord/build/libs/example-discord.jar").absolutePath)
    systemProperty("shoparchive.hotfixTemplateJar", rootProject.file("plugins/hotfix-template/build/libs/hotfix-template.jar").absolutePath)
    systemProperty("shoparchive.templateJar", rootProject.file("plugins/template/build/libs/template.jar").absolutePath)
}
