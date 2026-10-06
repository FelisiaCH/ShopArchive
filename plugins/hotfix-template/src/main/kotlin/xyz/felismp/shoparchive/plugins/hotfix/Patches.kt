package xyz.felismp.shoparchive.plugins.hotfix

import xyz.felismp.shoparchive.api.hotfix.HotfixPatch

/**
 * Replaces the body of `formatUptime(long)` in the core (the line `status` prints for the uptime), only to show the mechanism.
 * The core marks the methods a hotfix may replace with `@HotfixTarget`; this one is such a method.
 *
 * - `targetClass`, `method`, `descriptor`: the JVM names, as `javap -p` prints them. A Kotlin top-level function lives in `<File>Kt`.
 * - `classSha256`: the SHA-256 of that class file in the build you made this for. Type `plugins hotfix-hash <class>` in the
 *   console of that server and paste the result. The patch is applied only while the class is exactly that one.
 * - This function is `public static` and has the same parameters and return type as the target (for an instance method the
 *   object comes first: `fun fix(self: TheClass, x: Int): String`). It replaces the whole body; the old body cannot be called.
 * - Give your hotfix its own package (this one is the template's): every hotfix jar shares the core's classloader, and a class name
 *   present in two jars (or in the core) is refused at start.
 * - It runs in the core's own classloader: it can use every core class, and nothing of this jar may be needed before it is loaded.
 */
@HotfixPatch(
    targetClass = "xyz.felismp.shoparchive.server.CoreCommandsKt",
    method = "formatUptime",
    descriptor = "(J)Ljava/lang/String;",
    classSha256 = "replace-with-the-output-of-plugins-hotfix-hash",
)
fun formatUptime(totalSeconds: Long): String = "up ${totalSeconds}s (hotfixed)"
