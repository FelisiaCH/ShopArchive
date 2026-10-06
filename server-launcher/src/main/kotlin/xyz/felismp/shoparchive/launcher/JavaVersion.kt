package xyz.felismp.shoparchive.launcher

private const val REQUIRED_MAJOR = 21

/**
 * Parses `java.specification.version` ("1.8", "17", "21", "25", ...) into a major version number.
 * Total: returns null instead of throwing on a format we don't recognize - this exists so an old JVM
 * gets stage 1's readable error, so parsing the property that names that JVM must itself never crash.
 */
fun parseJavaMajorVersion(specVersion: String): Int? {
    val normalized = if (specVersion.startsWith("1.")) specVersion.substring(2) else specVersion
    return normalized.substringBefore('.').toIntOrNull()
}

fun isJavaVersionSupported(major: Int?): Boolean = major != null && major >= REQUIRED_MAJOR
