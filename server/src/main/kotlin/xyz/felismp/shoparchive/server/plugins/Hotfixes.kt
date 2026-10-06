package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.server.Log
import java.io.IOException
import java.util.Properties

/** The build number and merged fix IDs this core jar was built with (`shoparchive-build.properties`, made by server/build.gradle.kts). */
internal class BuildInfo(val build: Int?, val fixedIssues: Set<String>) {
    companion object {
        fun load(loader: ClassLoader = BuildInfo::class.java.classLoader): BuildInfo {
            val stream = loader.getResourceAsStream("shoparchive-build.properties") ?: return BuildInfo(null, emptySet())
            val props = Properties()
            try {
                stream.use { props.load(it) }
            } catch (e: IOException) {
                return BuildInfo(null, emptySet())
            }
            val fixed = props.getProperty("fixed-issues", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return BuildInfo(props.getProperty("build")?.trim()?.toIntOrNull(), fixed)
        }
    }
}

/** What the launcher decided for one hotfix jar; the names are the launcher's `HotfixState`. */
internal enum class HotfixState(val label: String) {
    APPLIED("active"),
    FIXED_IN_CORE("fixed in core, remove the jar"),
    IGNORED("ignored (--ignore-hotfix)"),
    SKIPPED("not applied"),
    NOT_APPROVED("not approved"),
    REJECTED("rejected"),
    OFF("off (--no-patches)"),
    UNUSED_IGNORE("--ignore-hotfix without a hotfix"),
}

internal class HotfixEntry(val state: HotfixState, val name: String, val sha256: String, val severity: String, val fixes: List<String>, val detail: String) {
    val idsText: String get() = fixes.joinToString()
}

/**
 * The launcher decides which hotfixes apply before the core is loaded and hands the result over in the system property
 * `shoparchive.hotfix.report` (one tab-separated line per hotfix jar). The core only shows it: in the log at every start,
 * in `plugins` and in `status`.
 */
internal class HotfixReport(val entries: List<HotfixEntry>) {
    fun bySha(sha256: String): HotfixEntry? = entries.firstOrNull { it.sha256 == sha256 && it.sha256.isNotEmpty() }

    private fun count(state: HotfixState) = entries.count { it.state == state }

    /** The one `status` line. */
    fun statusLine(build: BuildInfo, noPatches: Boolean): String {
        val parts = mutableListOf("${count(HotfixState.APPLIED)} active")
        count(HotfixState.FIXED_IN_CORE).takeIf { it > 0 }?.let { parts += "$it fixed in core (remove the jar)" }
        count(HotfixState.IGNORED).takeIf { it > 0 }?.let { parts += "$it ignored" }
        count(HotfixState.SKIPPED).takeIf { it > 0 }?.let { parts += "$it not applied" }
        count(HotfixState.NOT_APPROVED).takeIf { it > 0 }?.let { parts += "$it not approved" }
        val off = if (noPatches) " - OFF (--no-patches)" else ""
        return "Hotfixes: ${parts.joinToString(", ")} (core build ${build.build ?: "unknown"})$off"
    }

    /** Writes every hotfix to the log: each start repeats a warning for what is not as it should be. */
    fun log() {
        for (e in entries) {
            val what = "Hotfix ${e.name} (${e.severity}, fixes ${e.idsText})"
            when (e.state) {
                HotfixState.APPLIED -> Log.info("$what is applied (${e.detail})")
                HotfixState.FIXED_IN_CORE -> Log.warn("$what: core already fixes ${e.idsText}, the plugin can be removed")
                HotfixState.IGNORED -> Log.warn("$what is IGNORED (${e.detail}): this server runs WITHOUT that fix")
                HotfixState.SKIPPED -> Log.warn("$what is not applied: ${e.detail}. The server runs without it; get a hotfix for this build or remove the jar")
                HotfixState.NOT_APPROVED -> Log.warn("$what ${e.detail} and is not applied. A hotfix changes the server's own code: if you built or audited this jar, type: plugins approve ${e.name} (applies at the next start)")
                HotfixState.REJECTED -> Log.error("Hotfix ${e.name} is not applied: ${e.detail}")
                HotfixState.OFF -> Log.warn("$what is not applied (--no-patches)")
                HotfixState.UNUSED_IGNORE -> Log.warn(e.detail)
            }
        }
    }

    companion object {
        const val PROPERTY = "shoparchive.hotfix.report"

        val NONE = HotfixReport(emptyList())

        fun fromSystemProperty(): HotfixReport = parse(System.getProperty(PROPERTY))

        fun parse(text: String?): HotfixReport {
            if (text.isNullOrBlank()) return NONE
            val entries = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val f = line.split('\t')
                val state = HotfixState.entries.firstOrNull { it.name == f.getOrNull(0) } ?: return@mapNotNull null
                if (f.size < 6) return@mapNotNull null
                HotfixEntry(state, f[1], f[2], f[3], f[4].split(',').filter { it.isNotEmpty() }, f[5])
            }
            return HotfixReport(entries)
        }
    }
}

/** SHA-256 of a class file as the core's loader would define it (not as patched): what `@HotfixPatch(classSha256 = ...)` is pinned to. Null if the class is not there. */
internal fun classFileSha256(className: String, loader: ClassLoader = BuildInfo::class.java.classLoader): String? {
    val stream = loader.getResourceAsStream(className.replace('.', '/') + ".class") ?: return null
    return stream.use { sha256(it.readBytes()) }
}
