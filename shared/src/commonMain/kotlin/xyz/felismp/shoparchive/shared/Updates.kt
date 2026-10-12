package xyz.felismp.shoparchive.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The platform an installer in the server's `downloads/` folder is for: `.apk` is Android, `.msi` is Windows. Other files are ignored. */
@Serializable
enum class UpdatePlatform {
    @SerialName("android") ANDROID,
    @SerialName("windows") WINDOWS,
}

/**
 * One installer the server offers (`GET /api/v1/updates` lists them, `GET /api/v1/updates/{file}` sends one). [version] is `x.y.z`,
 * [build] the beta number of `-betaNNN` in the file name (0 for a final), both read from [file]; the admin names the files,
 * see [parseUpdateFileName]. The field is still called `build` because apps of 0.1.0 read the number after `x.y.z-` in a name as it:
 * a final sent as build 0 is a name they accept, so they upgrade to it. [sha256] is lower-case hex of the file's bytes: the app checks it before it installs.
 */
@Serializable
data class UpdateFile(
    val file: String,
    val platform: UpdatePlatform,
    val version: String,
    val build: Int,
    val size: Long,
    val sha256: String,
) {
    /** [version] and [build] as one comparable value; null if this server sent a version this app cannot read. */
    fun appVersion(): AppVersion? = if (build in 0..MAX_BETA) AppVersion.parse(version)?.copy(beta = build) else null
}

private const val MAX_BETA = 998

/**
 * Version of an app or installer: `x.y.z`, and `-betaNNN` after it for a build that is only handed out for testing ([beta] 0 is a final).
 * A higher `x.y.z` wins; of the same `x.y.z` the final wins over any beta, and a higher beta over a lower one.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int = 0) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch, AppVersion::rank)

    private val rank get() = if (beta == 0) Int.MAX_VALUE else beta // a final is above every beta

    override fun toString() = "$major.$minor.$patch" + if (beta > 0) "-beta${beta.toString().padStart(3, '0')}" else ""

    companion object {
        private val PATTERN = Regex("""(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:-beta(\d{3}))?""")

        /** `1.2.3` or `1.2.3-beta004` (001..998); anything else is null. */
        fun parse(text: String): AppVersion? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, beta) = m.destructured
            val number = beta.toIntOrNull() ?: 0
            if (beta.isNotEmpty() && number !in 1..MAX_BETA) return null
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt(), number)
        }
    }
}

/** What a file name in `downloads/` says about the file. */
data class UpdateFileName(val platform: UpdatePlatform, val version: AppVersion)

private val UPDATE_FILE_NAME = Regex("""ShopArchive-(\d{1,4}\.\d{1,4}\.\d{1,4}(?:-beta\d{3})?)\.(apk|APK|msi|MSI)""")

/**
 * The naming rule: `ShopArchive-<x.y.z>[-betaNNN].apk` or `.msi`, e.g. `ShopArchive-1.0.1.apk`, `ShopArchive-1.2.0-beta002.msi`.
 * Anything else (another name, another extension, a path) is null: such a file is not offered.
 */
fun parseUpdateFileName(name: String): UpdateFileName? {
    val m = UPDATE_FILE_NAME.matchEntire(name) ?: return null
    val version = AppVersion.parse(m.groupValues[1]) ?: return null
    val platform = if (m.groupValues[2].equals("apk", ignoreCase = true)) UpdatePlatform.ANDROID else UpdatePlatform.WINDOWS
    return UpdateFileName(platform, version)
}

/**
 * Whether [UpdateFile.file] follows [parseUpdateFileName] and says the same platform, version and beta as the rest of this entry.
 * The server only lists such files; a client calls this on what it received before the name goes near its disk.
 */
fun UpdateFile.hasValidName(): Boolean {
    val name = parseUpdateFileName(file) ?: return false
    return name.platform == platform && name.version == appVersion()
}

/** The newest of [files] for [platform] that is newer than [current], or null. A file with a bad name or a version that cannot be read is skipped. */
fun newestUpdate(files: List<UpdateFile>, platform: UpdatePlatform, current: AppVersion): UpdateFile? =
    files.filter { it.platform == platform && it.hasValidName() }
        .mapNotNull { f -> f.appVersion()?.let { f to it } }
        .filter { (_, v) -> v > current }
        .maxByOrNull { (_, v) -> v }?.first
