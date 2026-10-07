import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.jar.JarFile
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

kotlin {
    jvmToolchain(21)
}

// The whole module (not just stage 1) compiles to Java 8 bytecode: everything it does - reading jar
// resources, SHA-256, copying files, URLClassLoader - is a Java 8 API, and this is simpler and
// strictly safer than splitting stage 1/2 into separate source sets. -Xjdk-release=1.8 makes it a
// compile error to accidentally reach for a Java 9+ API. Tests stay on the toolchain default (21).
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

// No Java sources exist, but the Java plugin's compileJava task still defaults its target to the
// toolchain (21) and Kotlin's cross-task validation fails on the resulting 21-vs-1.8 mismatch -
// align it so main is 1.8 end to end, matching compileKotlin above.
tasks.named<JavaCompile>("compileJava") {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

// The launcher itself depends on nothing but the Kotlin stdlib - this configuration only exists to
// pull in the core's jar + its full runtime dependency graph as *payload* to embed, so it must not
// extend (or feed) runtimeClasspath.
val corePayload: Configuration = configurations.create("corePayload") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    }
}

dependencies {
    corePayload(project(":server"))
    // Bytecode patching of hotfixes (P13). Bundled into the launcher jar (not into the payload): the core's loader never sees it.
    implementation(libs.asm)
    implementation(libs.asm.tree)
    testImplementation(kotlin("test"))
    // The annotations the test fixtures use (HotfixTarget / HotfixPatch); the launcher itself only knows their descriptors.
    testImplementation(project(":shoparchive-api"))
}

/** Stages the core + its libraries into `META-INF/shoparchive/...` so shadowJar can bundle them as payload. */
abstract class PreparePayload : DefaultTask() {
    @get:InputFiles
    @get:Classpath
    abstract val artifacts: ConfigurableFileCollection

    // Artifact file name -> root-relative target path (versions/<version>/<name> or libraries/<group>/<name>).
    @get:Input
    abstract val targetPaths: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val out = outputDir.get().asFile
        out.deleteRecursively() // drop anything staged by a previous run (e.g. a removed dependency)

        val targets = targetPaths.get()
        val resolved = artifacts.files
        // Both maps are keyed by file name, so two artifacts sharing one name would collapse in both
        // and go unnoticed - compare against the real artifact count instead of the keyed map's size.
        if (resolved.size != targets.size) {
            throw GradleException(
                "preparePayload: resolved ${resolved.size} artifact(s) but ${targets.size} target path(s) " +
                    "were computed (duplicate file names?) - ${resolved.map { it.name }.sorted()} vs ${targets.keys.sorted()}"
            )
        }
        val filesByName = resolved.associateBy { it.name }

        val listLines = targets.entries
            .sortedWith(compareBy({ !it.value.startsWith("versions/") }, { it.value }))
            .map { (name, target) ->
                val source = filesByName[name]
                    ?: throw GradleException("preparePayload: no resolved artifact named '$name' for target '$target'")
                val destination = out.toPath().resolve("META-INF/shoparchive/$target")
                Files.createDirectories(destination.parent)
                Files.copy(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING)
                "${sha256Hex(destination)} $target"
            }

        Files.write(out.toPath().resolve("META-INF/shoparchive/files.list"), listLines)
    }

    private fun sha256Hex(file: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

// A top-level `val` in a .kts script becomes a property of the synthetic script class, so a `.map{}`
// lambda referencing it captures the whole script object (config-cache rejects that). Routing the
// version through a real function parameter instead keeps the lambda's captures to true locals.
fun targetPathsOf(configuration: Configuration, projectVersion: String): Provider<Map<String, String>> =
    configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.associate { artifact ->
            val id = artifact.id.componentIdentifier
            val fileName = artifact.file.name
            val target = when (id) {
                is ProjectComponentIdentifier -> "versions/$projectVersion/$fileName"
                is ModuleComponentIdentifier -> "libraries/${id.group}/$fileName"
                else -> throw GradleException("preparePayload: unsupported component identifier $id for $fileName")
            }
            fileName to target
        }
    }

val corePayloadTargets: Provider<Map<String, String>> = targetPathsOf(corePayload, project.version.toString())

val preparePayload = tasks.register<PreparePayload>("preparePayload") {
    artifacts.from(corePayload)
    targetPaths.set(corePayloadTargets)
    outputDir.set(layout.buildDirectory.dir("payload"))
}

tasks.shadowJar {
    archiveFileName = "shoparchive-server.jar"
    manifest { attributes("Main-Class" to "xyz.felismp.shoparchive.launcher.Main") }
    from(preparePayload)
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    // Shadow otherwise sets Multi-Release: true because kotlin-stdlib's manifest declares it, even though
    // its only "versioned" content is a module-info.class we already exclude above - with that gone there
    // is nothing left for META-INF/versions/ to mean, and checkShadowJarBytecode's skip-that-directory
    // logic is only sound while the jar isn't multi-release.
    addMultiReleaseAttribute.set(false)
}

// shoparchive-server.jar + start.bat + start.sh + service/... side by side, ready to drop in.
val dist = tasks.register<Sync>("dist") {
    from(tasks.shadowJar)
    from("scripts")
    into(layout.buildDirectory.dir("dist"))
}

evaluationDependsOn(":plugins:telegram")

// The server release: shoparchive-server-<version>.zip, unpacked into a folder of its own. Same files as `dist`, plus the
// Telegram plugin under plugins/ (the server does not load it until the admin approves it). start.sh keeps its exec bit.
val serverZip = tasks.register<Zip>("serverZip") {
    archiveFileName.set("shoparchive-server-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(tasks.shadowJar)
    from("scripts") {
        filePermissions { unix("rwxr-xr-x") }
        include("start.sh")
    }
    from("scripts") { exclude("start.sh") }
    from(project(":plugins:telegram").tasks.named("jar")) { into("plugins") }
}

// Opens the zip as built and asserts what an admin will find in it: every file, start.sh executable, and a Telegram plugin
// jar that carries only its own classes (the host's classloader supplies kotlin/ and the API; a bundled copy would shadow it).
abstract class CheckServerZip : DefaultTask() {
    @get:InputFile
    abstract val zipFile: RegularFileProperty

    @get:OutputFile
    abstract val upToDateMarker: RegularFileProperty

    @TaskAction
    fun check() {
        ZipFile(zipFile.get().asFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            val required = listOf(
                "shoparchive-server.jar", "start.sh", "start.bat", "README.md", "OPS.md",
                "service/shoparchive.service", "service/shoparchive-winsw.xml", "plugins/shoparchive-telegram.jar",
            )
            val missing = required.filter { it !in names }
            if (missing.isNotEmpty()) throw GradleException("server zip is missing $missing (has ${names.sorted()})")
            val unexpected = names.filter { it !in required && !it.endsWith("/") }
            if (unexpected.isNotEmpty()) throw GradleException("server zip has files nobody listed: $unexpected")
            if (unixModes(zipFile.get().asFile)["start.sh"]?.let { it and 0b001_000_000 } in listOf(null, 0)) {
                throw GradleException("start.sh in the server zip is not executable")
            }
            val bundled = zip.getInputStream(zip.getEntry("plugins/shoparchive-telegram.jar")).use { input ->
                val found = mutableListOf<String>()
                ZipInputStream(input).use { jar ->
                    while (true) {
                        val name = (jar.nextEntry ?: break).name
                        if (name.startsWith("kotlin/") || name.startsWith("xyz/felismp/shoparchive/api/")) found += name
                    }
                }
                found
            }
            if (bundled.isNotEmpty()) throw GradleException("the Telegram plugin jar bundles host classes: ${bundled.take(5)}")
        }
        upToDateMarker.get().asFile.writeText("ok\n")
    }

    /** Unix permission bits by entry name, read from the zip's central directory (java.util.zip does not expose them). */
    private fun unixModes(file: java.io.File): Map<String, Int> {
        val data = file.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = data.size - 22
        while (eocd >= 0 && buf.getInt(eocd) != 0x06054b50) eocd--
        if (eocd < 0) throw GradleException("not a zip file: $file")
        var pos = buf.getInt(eocd + 16)
        val count = buf.getShort(eocd + 10).toInt() and 0xFFFF
        val modes = HashMap<String, Int>()
        repeat(count) {
            if (buf.getInt(pos) != 0x02014b50) throw GradleException("broken central directory in $file")
            val nameLength = buf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLength = buf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLength = buf.getShort(pos + 32).toInt() and 0xFFFF
            modes[String(data, pos + 46, nameLength, Charsets.UTF_8)] = buf.getInt(pos + 38) ushr 16
            pos += 46 + nameLength + extraLength + commentLength
        }
        return modes
    }
}

val checkServerZip = tasks.register<CheckServerZip>("checkServerZip") {
    zipFile.set(serverZip.flatMap { it.archiveFile })
    upToDateMarker.set(layout.buildDirectory.file("checkServerZip/ok.txt"))
}

tasks.named("check") { dependsOn(checkServerZip) }

// JUnit's @TempDir lands under java.io.tmpdir - keep it in the build dir instead of the system temp dir.
// A plain File local (not a script property) so the doFirst lambda stays config-cache friendly.
// StartScriptTest runs the real scripts from a copy of dist, so tests depend on it (and re-run when it changes).
tasks.test {
    useJUnitPlatform()
    val testTmpDir = layout.buildDirectory.dir("test-tmp").get().asFile
    val distDir = layout.buildDirectory.dir("dist").get().asFile
    systemProperty("java.io.tmpdir", testTmpDir.absolutePath)
    systemProperty("shoparchive.dist", distDir.absolutePath)
    dependsOn(dist)
    inputs.dir(distDir)
    doFirst { testTmpDir.mkdirs() }
}

// Lints the whole repo's main Kotlin sources (not just this module) for direct createTempFile/
// createTempDirectory calls, which would bypass the <root>/tmp invariant the launcher enforces at
// runtime. Modelled on composeApp's CheckI18nParity: typed task inputs so :server-launcher:check
// actually re-runs when a source file changes, instead of going UP-TO-DATE over an untracked File.walk.
abstract class CheckNoTempFileFactories : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    // A verification task with no declared output is never eligible for UP-TO-DATE (Gradle always
    // reruns it) - a marker file gives it real up-to-date tracking instead, so an unrelated rebuild
    // doesn't repeat a 1000+ file scan every time, matching normal task semantics.
    @get:OutputFile
    abstract val upToDateMarker: RegularFileProperty

    @TaskAction
    fun check() {
        val pattern = Regex("""createTempFile\(|createTempDirectory\(""")
        val offenders = sources.files.sorted().flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (pattern.containsMatchIn(line)) "${file.path}:${i + 1}" else null
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "temp files must be created explicitly under <root>/tmp, not via createTempFile/createTempDirectory:\n" +
                    offenders.joinToString("\n") { "  $it" }
            )
        }
        upToDateMarker.get().asFile.writeText("ok: ${sources.files.size} file(s) scanned, no offenders\n")
    }
}

val checkNoTempFileFactories = tasks.register<CheckNoTempFileFactories>("checkNoTempFileFactories") {
    sources.from(
        rootProject.fileTree(rootProject.projectDir) {
            include("**/src/main/**/*.kt", "**/src/*Main/**/*.kt")
            exclude("**/build/**", "spike/**", ".*/**", "run/**")
        }
    )
    upToDateMarker.set(layout.buildDirectory.file("checkNoTempFileFactories/ok.txt"))
}

tasks.named("check") { dependsOn(checkNoTempFileFactories) }

// shadowJar bundles kotlin-stdlib (and the rest of :server's runtime graph) as payload inside
// META-INF/shoparchive/ - compileKotlin's -Xjdk-release=1.8 check above only covers this module's own
// classes, so nothing else verifies that *those* bundled classes are also Java-8-loadable. Checks the
// actual shipped artifact instead.
abstract class CheckShadowJarBytecode : DefaultTask() {
    @get:InputFile
    abstract val jarFile: RegularFileProperty

    @TaskAction
    fun check() {
        JarFile(jarFile.get().asFile).use { jar ->
            val manifest = jar.manifest ?: throw GradleException("shadow jar has no MANIFEST.MF")
            val mainClass = manifest.mainAttributes.getValue("Main-Class")
            if (mainClass.isNullOrBlank()) {
                throw GradleException("shadow jar manifest has no Main-Class")
            }
            // Skipping META-INF/versions/ below is only sound while the jar is NOT multi-release - that
            // assumption must be enforced, not assumed.
            if (manifest.mainAttributes.getValue("Multi-Release")?.toBoolean() == true) {
                throw GradleException(
                    "shadow jar manifest declares Multi-Release: true - checkShadowJarBytecode assumes " +
                        "it is not, so it can skip META-INF/versions/; that assumption no longer holds"
                )
            }

            val offenders = Collections.list(jar.entries())
                .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") }
                .mapNotNull { entry ->
                    val major = jar.getInputStream(entry).use { input -> readClassMajorVersion(input) }
                    if (major > JAVA_8_CLASS_MAJOR_VERSION) "${entry.name} (major $major)" else null
                }

            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "shadow jar has classes newer than Java 8 (major > $JAVA_8_CLASS_MAJOR_VERSION) " +
                        "outside META-INF/versions/ - stage 1 would crash with UnsupportedClassVersionError " +
                        "on Java < 21 instead of printing its readable error:\n" +
                        offenders.joinToString("\n") { "  $it" }
                )
            }
        }
    }

    // Java class file layout: magic[4] + minor[2] + major[2], so the major version starts at byte offset 6.
    private fun readClassMajorVersion(input: java.io.InputStream): Int {
        val header = ByteArray(8)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read < 0) break
            offset += read
        }
        return ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
    }

    companion object {
        private const val JAVA_8_CLASS_MAJOR_VERSION = 52
    }
}

val checkShadowJarBytecode = tasks.register<CheckShadowJarBytecode>("checkShadowJarBytecode") {
    jarFile.set(tasks.shadowJar.flatMap { it.archiveFile })
}

tasks.named("check") { dependsOn(checkShadowJarBytecode) }
