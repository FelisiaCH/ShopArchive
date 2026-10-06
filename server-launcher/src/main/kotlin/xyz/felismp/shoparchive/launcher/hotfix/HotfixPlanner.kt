package xyz.felismp.shoparchive.launcher.hotfix

import xyz.felismp.shoparchive.launcher.linkProblem
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.jar.Manifest

/** System property through which the launcher hands the core what it decided: [HotfixReport] lines. */
const val HOTFIX_REPORT_PROPERTY = "shoparchive.hotfix.report"

/** What became of one hotfix jar. The core shows these in `plugins` and `status` and logs them at every start. */
enum class HotfixState {
    /** Its patches are in the core's loader. */
    APPLIED,
    /** Every ID it fixes is in the core's `fixed-issues`: the jar can be removed. */
    FIXED_IN_CORE,
    /** `--ignore-hotfix` named one of its IDs. */
    IGNORED,
    /** A `bug` hotfix made for another build or class: not applied, the server starts. */
    SKIPPED,
    NOT_APPROVED,
    /** Not a usable hotfix (bad plugin.yml, Class-Path, shaded Kotlin ...). */
    REJECTED,
    /** `--no-patches`. */
    OFF,
    /** A `--ignore-hotfix` ID that no installed hotfix fixes (the name column is the ID). */
    UNUSED_IGNORE,
}

class HotfixLine(val state: HotfixState, val name: String, val sha256: String, val severity: String, val fixes: List<String>, val detail: String) {
    fun encode(): String = listOf(state.name, name, sha256, severity, fixes.joinToString(","), detail).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
}

/** The start must not go on: [message] says which hotfix and why, and what the admin can do. */
class HotfixFatal(message: String) : Exception(message)

class HotfixOptions(val noPatches: Boolean, val ignore: Set<String>)

/** The build number and merged fix IDs the core jar was built with (`shoparchive-build.properties`). */
class CoreBuild(val build: Int?, val fixedIssues: Set<String>)

/** A class whose bytes the core's loader defines instead of the ones in the jar; [jar] is where the original came from. */
class PatchedClass(val name: String, val bytes: ByteArray, val jar: File)

class HotfixPlan(val patched: Map<String, PatchedClass>, val extraJars: List<File>, val report: List<HotfixLine>) {
    fun encodedReport(): String = report.joinToString("\n") { it.encode() }
}

private const val BUILD_RESOURCE = "shoparchive-build.properties"
private const val MAX_YML_BYTES = 64 * 1024
private val PLUGIN_NAME = Regex("[A-Za-z0-9_-]{1,32}")
internal val FIX_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val SNAPSHOT_NAME = Regex("[0-9a-f]{64}\\.jar")

// The core's PluginManager refuses these names for plugins; a hotfix is refused here for the same reason.
private val RESERVED_NAMES = setOf("core", "console", "server", "shoparchive", "api", "plugin", "plugins")

/** Reads the build number and `fixed-issues` from the first core jar that has them; no number if none does. */
fun readCoreBuild(coreJars: List<File>): CoreBuild {
    for (jar in coreJars) {
        try {
            JarFile(jar).use { file ->
                val entry = file.getJarEntry(BUILD_RESOURCE) ?: return@use
                val props = Properties()
                file.getInputStream(entry).use { props.load(it) }
                val fixed = props.getProperty("fixed-issues", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                return CoreBuild(props.getProperty("build")?.trim()?.toIntOrNull(), fixed)
            }
        } catch (e: IOException) {
            // an unreadable jar is the payload check's business; look in the next
        }
    }
    return CoreBuild(null, emptySet())
}

private class Candidate(
    val file: Path, val bytes: ByteArray, val sha256: String, val name: String, val severity: String,
    val fixes: List<String>, val targetBuild: Int, val multiRelease: Boolean,
)

/**
 * Decides, before the core is loaded, which hotfixes in `plugins/` apply, and builds the patched classes.
 * Hotfix jars are read once; what is approved, checked and loaded is those bytes (copied to `cache/hotfix/<sha256>.jar`).
 *
 * Throws [HotfixFatal] if a `security` hotfix is for another build or class and is not ignored, a hotfix patch cannot be made
 * (it matches 0 methods, the method is not `@HotfixTarget` ...), or two hotfixes patch the same method.
 */
fun planHotfixes(root: Path, coreJars: List<File>, core: CoreBuild, options: HotfixOptions): HotfixPlan {
    val pluginsDir = root.resolve("plugins")
    clearOldSnapshots(root)
    val report = ArrayList<HotfixLine>()
    val jars = if (Files.isDirectory(pluginsDir)) {
        Files.newDirectoryStream(pluginsDir, "*.[jJ][aA][rR]").use { s -> s.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString().lowercase() } }
    } else emptyList()
    val approved by lazy { readApproved(pluginsDir.resolve("approved.yml")) }
    val fatal = ArrayList<String>()
    val selected = ArrayList<Pair<Candidate, List<PatchSpec>>>()
    val seenNames = HashMap<String, String>()
    val used = HashSet<String>()

    for (file in jars) {
        val candidate = try {
            readCandidate(file) ?: continue
        } catch (e: HotfixJarProblem) {
            report += HotfixLine(HotfixState.REJECTED, e.name ?: file.fileName.toString(), "", "", emptyList(), e.message ?: "")
            continue
        } catch (e: IOException) {
            continue // not readable as a jar: the core reports it
        }
        fun line(state: HotfixState, detail: String = "") = report.add(HotfixLine(state, candidate.name, candidate.sha256, candidate.severity, candidate.fixes, detail))
        // An unapproved jar is dropped here, before its name is reserved: it can never affect another jar.
        if (approved[candidate.name] != candidate.sha256) {
            val why = if (candidate.name in approved) "has changed since it was approved" else "is new"
            line(HotfixState.NOT_APPROVED, "$why (SHA-256 ${candidate.sha256})")
            continue
        }
        val first = seenNames.put(candidate.name, file.fileName.toString())
        if (options.noPatches) { line(HotfixState.OFF, "--no-patches"); continue }
        if (candidate.fixes.all { it in core.fixedIssues }) { line(HotfixState.FIXED_IN_CORE, "the core already fixes ${candidate.fixes.joinToString()}; this jar can be removed"); continue }
        val ignored = candidate.fixes.filter { it in options.ignore }
        if (ignored.isNotEmpty()) { line(HotfixState.IGNORED, "--ignore-hotfix ${ignored.joinToString()}"); continue }
        if (first != null) {
            // Two approved jars for one plugin name: every approved jar must get its own check, so this is as fatal as a security mismatch
            // (and, like it, --ignore-hotfix <ID> of this jar lets the server start without it).
            line(HotfixState.REJECTED, "a second approved jar for '${candidate.name}' (the first is $first)")
            fatal += "hotfix ${candidate.name} (${file.fileName}): another approved jar ($first) has the same plugin name. Remove or revoke one of them, or start with --ignore-hotfix ${candidate.fixes.first()} (the server then runs without this fix)."
            continue
        }

        val problem = try { checkCandidate(candidate, coreJars, core) } catch (e: PatchException) {
            // The patch itself cannot be made: a broken hotfix, whatever its severity.
            fatal += "hotfix ${candidate.name} (${file.fileName}): ${e.message}. Fix or remove the jar, or start with --ignore-hotfix ${candidate.fixes.first()} (the server then runs without this fix)."
            continue
        }
        if (problem.mismatch != null) {
            if (candidate.severity == "security") {
                fatal += "security hotfix ${candidate.name} (${file.fileName}) does not match this server: ${problem.mismatch}. " +
                    "Install a hotfix made for this build, or update the server; to start without the fix (the problem stays open) use --ignore-hotfix ${candidate.fixes.first()}."
            } else line(HotfixState.SKIPPED, problem.mismatch)
            continue
        }
        val specs = problem.specs
        val clash = specs.firstOrNull { it.targetKey in used }
        if (clash != null) {
            val other = selected.first { (_, s) -> s.any { it.targetKey == clash.targetKey } }.first.name
            fatal += "hotfixes $other and ${candidate.name} both patch ${clash.targetKey}; keep one of them (or ignore one with --ignore-hotfix)"
            continue
        }
        val own = HashSet<String>()
        val dup = specs.firstOrNull { !own.add(it.targetKey) }
        if (dup != null) { fatal += "hotfix ${candidate.name} patches ${dup.targetKey} twice"; continue }
        used.addAll(own)
        selected += candidate to specs
    }

    for (id in options.ignore) {
        if (report.none { id in it.fixes }) report += HotfixLine(HotfixState.UNUSED_IGNORE, id, "", "", emptyList(), "--ignore-hotfix $id matches no hotfix in plugins/")
    }
    fatal += classCollisions(selected.map { it.first }, coreJars)
    if (fatal.isNotEmpty()) throw HotfixFatal(fatal.joinToString("\n"))
    if (selected.isEmpty()) return HotfixPlan(emptyMap(), emptyList(), report)

    // Everything checked: now the patched bytes, per class, and the jars the core's loader will also read.
    val byClass = LinkedHashMap<String, MutableList<PatchSpec>>()
    for ((_, specs) in selected) for (spec in specs) byClass.getOrPut(spec.targetClass) { ArrayList() } += spec
    val patched = LinkedHashMap<String, PatchedClass>()
    for ((className, specs) in byClass) {
        val (jar, original) = findClassBytes(coreJars, className)!!
        val bytes = try { applyPatches(original, className, specs) } catch (e: PatchException) {
            throw HotfixFatal("patching $className failed: ${e.message}")
        }
        patched[className] = PatchedClass(className, bytes, jar)
    }
    val extra = ArrayList<File>()
    for ((candidate, _) in selected) {
        extra += snapshotJar(root, candidate)
        report += HotfixLine(HotfixState.APPLIED, candidate.name, candidate.sha256, candidate.severity, candidate.fixes, "${selected.first { it.first === candidate }.second.size} patch(es)")
    }
    return HotfixPlan(patched, extra, report)
}

/**
 * One loader reads the core, its libraries and every selected hotfix jar, and the first jar with a class name wins: a replacement
 * class that exists twice would run the wrong implementation (or fail with NoSuchMethodError). So a class in a selected hotfix jar
 * (not anything under META-INF, not module-info; a jar with classes under META-INF/versions or Multi-Release: true is refused before this) must not be in another selected hotfix jar, in the core jars or on the launcher's classpath.
 * A collision is fatal like any other hotfix whose patch cannot be made: the message names the jars and the class.
 */
private fun classCollisions(selected: List<Candidate>, coreJars: List<File>): List<String> {
    if (selected.isEmpty()) return emptyList()
    val coreClasses = HashSet<String>()
    for (jar in coreJars) {
        try { JarFile(jar).use { f -> f.entries().asSequence().forEach { coreClasses += it.name } } } catch (e: IOException) { /* the payload check's business */ }
    }
    val launcherLoader = Candidate::class.java.classLoader
    val owners = HashMap<String, Candidate>()
    val problems = ArrayList<String>()
    for (c in selected) {
        val hint = "Rename the package of the hotfix (every hotfix needs its own) or remove the jar, or start with --ignore-hotfix ${c.fixes.first()} (the server then runs without this fix)."
        for (entry in classEntries(c)) {
            val className = entry.removeSuffix(".class").replace('/', '.')
            val other = owners.putIfAbsent(entry, c)
            if (other != null && other !== c) {
                problems += "hotfixes ${other.name} (${other.file.fileName}) and ${c.name} (${c.file.fileName}) both contain the class $className. $hint"
            } else if (entry in coreClasses || launcherLoader?.getResource(entry) != null) {
                problems += "hotfix ${c.name} (${c.file.fileName}) contains the class $className, which the server already has (core or launcher). $hint"
            }
        }
    }
    return problems
}

/** The `.class` entries of a hotfix jar that take part in loading: not anything under `META-INF`, not `module-info.class`. Multi-release jars never get here, so no versioned copy can hide a class. */
private fun classEntries(c: Candidate): List<String> {
    val names = ArrayList<String>()
    JarInputStream(ByteArrayInputStream(c.bytes)).use { input ->
        while (true) {
            val entry = input.nextJarEntry ?: break
            val n = entry.name
            if (n.endsWith(".class") && !n.startsWith("META-INF/") && n != "module-info.class") names += n
        }
    }
    return names
}

private class Checked(val specs: List<PatchSpec>, val mismatch: String?)

/** Reads the patches of [c] and checks them against the core: a mismatch (build or class) is a value, a patch that cannot be made throws. */
private fun checkCandidate(c: Candidate, coreJars: List<File>, core: CoreBuild): Checked {
    if (c.multiRelease) throw PatchException("multi-release hotfix jars are not supported (Multi-Release: true in the manifest or classes under META-INF/versions)")
    val specs = readJarPatches(c)
    if (specs.isEmpty()) throw PatchException("the jar has no @HotfixPatch method")
    if (core.build == null || c.targetBuild != core.build) {
        return Checked(specs, "it was made for core build ${c.targetBuild}, this server is build ${core.build ?: "unknown"}")
    }
    for (spec in specs) {
        val found = findClassBytes(coreJars, spec.targetClass) ?: return Checked(specs, "class ${spec.targetClass} is not in this core")
        val actual = sha256(found.second)
        if (actual != spec.classSha256) return Checked(specs, "class ${spec.targetClass} has changed (SHA-256 $actual, the patch was made for ${spec.classSha256})")
    }
    for ((cls, group) in specs.groupBy { it.targetClass }) applyPatches(findClassBytes(coreJars, cls)!!.second, cls, group) // dry run: throws if it cannot be made
    return Checked(specs, null)
}

private class HotfixJarProblem(val name: String?, message: String) : Exception(message)

/** The jar as a hotfix candidate, or null if it is not a hotfix (or cannot be read as a jar at all). */
private fun readCandidate(file: Path): Candidate? {
    val bytes = Files.readAllBytes(file)
    var yml: String? = null
    var manifest: Manifest? = null
    var shaded: String? = null
    var versioned = false
    JarInputStream(ByteArrayInputStream(bytes)).use { input ->
        manifest = input.manifest
        while (true) {
            val entry = input.nextJarEntry ?: break
            if (entry.name.startsWith("META-INF/versions/") && entry.name.endsWith(".class")) versioned = true
            if (entry.name == "plugin.yml") yml = String(readLimited(input), Charsets.UTF_8)
            else if (shaded == null && (entry.name.startsWith("kotlin/") || entry.name.startsWith("kotlinx/"))) shaded = entry.name
        }
    }
    val map = parseTopLevel(yml ?: return null)
    if ((map["hotfix"] as? String)?.equals("true", ignoreCase = true) != true) return null
    val name = (map["name"] as? String)?.takeIf { PLUGIN_NAME.matches(it) } ?: throw HotfixJarProblem(null, "plugin.yml has no valid name")
    if (name.lowercase() in RESERVED_NAMES) throw HotfixJarProblem(name, "the name '$name' is reserved for the server")
    val severity = (map["severity"] as? String)?.trim()
    if (severity != "security" && severity != "bug") throw HotfixJarProblem(name, "severity must be security or bug")
    val fixes = (map["fixes"] as? List<*>)?.map { it.toString() }?.takeIf { it.isNotEmpty() } ?: throw HotfixJarProblem(name, "fixes must be a list of IDs")
    if (fixes.any { !FIX_ID.matches(it) }) throw HotfixJarProblem(name, "fixes has an ID that is not letters, digits, . _ - (1 to 64 characters)")
    val build = (map["target-build"] as? String)?.trim()?.toIntOrNull()?.takeIf { it >= 1 } ?: throw HotfixJarProblem(name, "target-build must be a whole number from 1")
    if (manifest?.mainAttributes?.getValue("Class-Path") != null) throw HotfixJarProblem(name, "the jar's manifest has a Class-Path")
    if (shaded != null) throw HotfixJarProblem(name, "the jar contains Kotlin classes ($shaded): the server provides them")
    return Candidate(file, bytes, sha256(bytes), name, severity, fixes.distinct(), build,
        versioned || manifest?.mainAttributes?.getValue("Multi-Release")?.trim().equals("true", ignoreCase = true))
}

private fun readJarPatches(c: Candidate): List<PatchSpec> {
    val specs = ArrayList<PatchSpec>()
    JarInputStream(ByteArrayInputStream(c.bytes)).use { input ->
        while (true) {
            val entry = input.nextJarEntry ?: break
            if (!entry.name.endsWith(".class") || entry.name.endsWith("module-info.class")) continue
            val classBytes = readLimited(input, 8 * 1024 * 1024)
            // A cheap look first: only classes that mention the annotation are read in full.
            if (String(classBytes, Charsets.ISO_8859_1).indexOf(HOTFIX_PATCH_DESC) < 0) continue
            specs += readPatchSpecs(classBytes)
        }
    }
    return specs
}

private fun readLimited(input: java.io.InputStream, max: Int = MAX_YML_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        if (out.size() > max) throw IOException("an entry is larger than ${max / 1024} KB")
    }
    return out.toByteArray()
}

/** First core jar that has the class, with its bytes: the same one the loader would define. */
internal fun findClassBytes(coreJars: List<File>, className: String): Pair<File, ByteArray>? {
    val path = className.replace('.', '/') + ".class"
    for (jar in coreJars) {
        try {
            JarFile(jar).use { file ->
                val entry = file.getJarEntry(path) ?: return@use
                return jar to file.getInputStream(entry).use { it.readBytes() }
            }
        } catch (e: IOException) {
            // next jar
        }
    }
    return null
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Writes the approved bytes to `cache/hotfix/<sha256>.jar` (atomically; the folder must be a plain one) and returns it. */
private fun snapshotJar(root: Path, c: Candidate): File {
    linkProblem(root, "cache/hotfix")?.let { throw HotfixFatal(it) }
    val dir = root.resolve("cache/hotfix")
    Files.createDirectories(dir)
    val target = dir.resolve("${c.sha256}.jar")
    val staging = root.resolve("tmp").also { Files.createDirectories(it) }.resolve("hotfix-${c.sha256}.jar")
    Files.write(staging, c.bytes)
    try {
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
    }
    return target.toFile()
}

/** The snapshots of an earlier run are not needed: only what this launcher wrote (`<64 hex>.jar`, a plain file) is deleted, and never through a link. */
private fun clearOldSnapshots(root: Path) {
    val dir = root.resolve("cache/hotfix")
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || linkProblem(root, "cache/hotfix") != null) return
    Files.newDirectoryStream(dir).use { s -> s.filter { SNAPSHOT_NAME.matches(it.fileName.toString()) && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } }
        .forEach { try { Files.delete(it) } catch (e: IOException) { /* still in use: kept */ } }
}
