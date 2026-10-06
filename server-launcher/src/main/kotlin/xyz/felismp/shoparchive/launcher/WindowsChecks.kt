package xyz.felismp.shoparchive.launcher

import java.nio.file.Path

private fun isWindows(): Boolean = System.getProperty("os.name", "").startsWith("Windows")

/** JDK-8195129: on Windows, System.load() can't load a native library from a unicode path. */
fun warnIfNonAsciiRootOnWindows(root: Path) {
    val text = root.toString()
    if (isWindows() && text.any { it.code > 0x7F }) {
        System.err.println(
            "[WARN] root path has non-ASCII characters: $text\n" +
                "On Windows this can break native library loading (JDK-8195129, e.g. JNA/Netty). " +
                "Prefer a plain-ASCII path."
        )
    }
}
