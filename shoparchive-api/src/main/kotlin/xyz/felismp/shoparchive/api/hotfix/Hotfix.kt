package xyz.felismp.shoparchive.api.hotfix

/**
 * Marks a core method that a hotfix (plugin.yml `hotfix: true`) may replace. Only marked methods can be patched;
 * the launcher refuses a patch for any other method.
 *
 * Rules for a marked method, all about keeping its JVM name and descriptor stable between builds:
 * - not `inline` (callers have a copy of the body, a patch would not reach them), not `suspend`, and not a constructor;
 * - a name that Kotlin does not mangle: if the signature has an inline/value class, give it `@JvmName("fixedName")`;
 * - changing the name, the parameters or the return type of a marked method is a change to what hotfixes rely on.
 *
 * The annotation is in the class file only (not at run time).
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class HotfixTarget

/**
 * Put on a `public static` method of a hotfix jar: the body of the core method it names is replaced by a call to this method.
 *
 * The patched method and this one have the same parameters and return type; for an instance method this one takes the
 * object as its first parameter. The patch only applies while the target class is byte for byte the class [classSha256] was taken
 * from (see `plugins hotfix-hash <class>` in the server console), so a core update never meets a patch made for another build.
 *
 * In Kotlin use a top-level function, or `@JvmStatic` in an `object`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class HotfixPatch(
    /** Binary name of the core class, e.g. `xyz.felismp.shoparchive.server.Console`. */
    val targetClass: String,
    /** JVM name of the method (what `@JvmName` says, if it has one). */
    val method: String,
    /** JVM descriptor of the target method, e.g. `(Ljava/lang/String;)Ljava/lang/String;`. */
    val descriptor: String,
    /** SHA-256 (lower-case hex) of the target class file in the core build this was made for. */
    val classSha256: String,
)
