package xyz.felismp.shoparchive.launcher

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A symlink to a directory, or on Windows without the symlink privilege a junction; skips the test if neither works. */
fun dirLinkOrSkip(link: Path, target: Path) {
    assumeTrue(symlink(link, target) || junction(link, target), "cannot create a symlink or junction here")
}

/** A genuine symbolic link to a file (a junction cannot point at one, so there is no fallback); skips the test if not allowed. */
fun fileSymlinkOrSkip(link: Path, target: Path) =
    assumeTrue(symlink(link, target), "cannot create a file symlink here (Windows needs the symlink privilege or developer mode)")

private fun symlink(link: Path, target: Path): Boolean =
    try {
        Files.createSymbolicLink(link, target)
        true
    } catch (e: IOException) {
        false
    } catch (e: UnsupportedOperationException) {
        false
    }

/** A Windows junction specifically (never a symlink); skips the test anywhere else. */
fun junctionOrSkip(link: Path, target: Path) =
    assumeTrue(junction(link, target), "cannot create a junction here")

private fun junction(link: Path, target: Path): Boolean {
    if (!System.getProperty("os.name", "").startsWith("Windows")) return false
    return try {
        ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
            .also { it.inputStream.readBytes() }
            .waitFor() == 0
    } catch (e: IOException) {
        false
    }
}
