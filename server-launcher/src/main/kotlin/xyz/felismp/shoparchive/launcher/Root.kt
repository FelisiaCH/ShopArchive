package xyz.felismp.shoparchive.launcher

import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/** root = --root if given, else the folder the launcher jar sits in (never the process working directory). */
fun resolveRoot(explicitRoot: String?): Path {
    val base = explicitRoot?.let { Paths.get(it) } ?: jarDirectory()
    return base.toAbsolutePath().normalize()
}

/**
 * Whether [dir] lies inside [realRoot] (which must already be a real path). [dir] is compared by its real path,
 * so a root reached through a link or a `subst` drive is not mistaken for another place. The part of [dir] that
 * does not exist yet (the first run, before the launcher has created it) is appended to the real path of its
 * deepest existing ancestor, so a link somewhere above it still counts.
 */
fun isInsideRoot(dir: Path, realRoot: Path): Boolean {
    var existing = dir.toAbsolutePath()
    val missing = ArrayList<Path>()
    var real: Path? = null
    while (real == null) {
        real = try {
            existing.toRealPath()
        } catch (e: IOException) {
            null
        }
        if (real == null) {
            missing.add(0, existing.fileName ?: return false)
            existing = existing.parent ?: return false
        }
    }
    return missing.fold(real) { path, name -> path.resolve(name) }.normalize().startsWith(realRoot)
}

/**
 * The refusal message for a start through start.sh/start.bat whose java.io.tmpdir is [tempDir] outside
 * [realRoot], or null if it is inside. The scripts put the temp dir and the crash log next to themselves, so
 * the JVM would write outside the root, which a script start must never do.
 */
fun startScriptTempDirProblem(tempDir: Path, realRoot: Path): String? {
    if (isInsideRoot(tempDir, realRoot)) return null
    return "java.io.tmpdir is ${tempDir.toAbsolutePath().normalize()}, outside the root $realRoot; refusing to start. " +
        "start.sh/start.bat keep temp files and crash logs next to themselves, so they only work for the root " +
        "they sit in: put the script and the jar in the root folder and start there. " +
        "--root is for running the jar directly (java -jar), without the script."
}

private fun jarDirectory(): Path {
    val location = Main::class.java.protectionDomain.codeSource.location
    return File(location.toURI()).parentFile.toPath()
}
