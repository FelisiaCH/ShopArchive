package xyz.felismp.shoparchive.launcher

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Deletes everything under [tempDir] (keeping the directory itself). Best effort: a stuck native-lib
 *  file (locked DLL, etc.) shouldn't stop the server from starting - just warn once with the count. */
fun wipeTempDir(tempDir: Path) {
    if (!Files.isDirectory(tempDir)) return

    var failures = 0
    Files.walkFileTree(tempDir, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            // walkFileTree reports a Windows junction as a plain directory and would descend into its target,
            // deleting data outside the root. tempDir is a real path, so a directory whose real path differs
            // from itself was reached through a link (symlink, junction, any reparse point): remove just the
            // link and never walk into it. If the real path can't be resolved, treat it as a link too.
            if (dir == tempDir) return FileVisitResult.CONTINUE
            val isLink = try {
                dir.toRealPath() != dir
            } catch (e: IOException) {
                true
            }
            if (!isLink) return FileVisitResult.CONTINUE
            if (!tryDelete(dir)) failures++
            return FileVisitResult.SKIP_SUBTREE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            // Main refuses a link at tmp (layoutLinkProblem) before calling this, so it only matters to a
            // direct caller: walkFileTree visits a symlinked start directory as a *file*, so
            // postVisitDirectory's "don't delete the start directory" guard below never runs for it.
            // Mirror that guard here too.
            if (file != tempDir && !tryDelete(file)) failures++
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            // SimpleFileVisitor rethrows by default, which would abort startup over an unreadable
            // leftover - count it like a failed delete and keep going.
            failures++
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (dir != tempDir && !tryDelete(dir)) failures++
            return FileVisitResult.CONTINUE
        }
    })

    if (failures > 0) {
        System.err.println("[WARN] could not delete $failures item(s) under $tempDir")
    }
}

private fun tryDelete(path: Path): Boolean =
    try {
        Files.delete(path)
        true
    } catch (e: IOException) {
        false
    }
