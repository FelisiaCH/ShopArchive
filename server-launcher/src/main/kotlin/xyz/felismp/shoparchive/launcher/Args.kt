package xyz.felismp.shoparchive.launcher


import xyz.felismp.shoparchive.launcher.hotfix.FIX_ID
import kotlin.system.exitProcess

/** [root] is the launcher's own `--root`; [coreArgs] are the other flags with their values, handed to the core unchanged. */
class ParsedArgs(
    val root: String?,
    val coreArgs: Array<String>,
    /** `--no-patches`: no hotfix is applied. The core is given the flag too, to show it. */
    val noPatches: Boolean = false,
    /** The IDs of `--ignore-hotfix <ID>` (repeatable). The core is given them too, to show them. */
    val ignoreHotfix: Set<String> = emptySet(),
)

/** A command line the launcher refuses; [message] is what to print. */
internal class ArgsException(message: String) : Exception(message)

private const val USAGE =
    "Usage: java -jar shoparchive-server.jar [--root <dir>] [--port <n>] [--bind-address <addr>] [--log-level <level>] [--no-plugins] [--no-patches] [--ignore-hotfix <ID>]..."

// The core owns the meaning of these three (it knows their allowed values); the launcher only checks the shape.
private val CORE_FLAGS = setOf("--port", "--bind-address", "--log-level")

// The one flag without a value: safe mode. Also the core's to interpret; the launcher only passes it on.
private const val NO_PLUGINS = "--no-plugins"

private const val NO_PATCHES = "--no-patches"

// The one flag that may be given more than once: each ID names a hotfix whose problem the admin accepts (see the hotfix planner).
private const val IGNORE_HOTFIX = "--ignore-hotfix"

/** Prints the problem and exits 2 on a command line the launcher refuses. */
fun parseArgs(args: Array<String>): ParsedArgs =
    try {
        parseArgsOrThrow(args)
    } catch (e: ArgsException) {
        System.err.println(e.message)
        exitProcess(2)
    }

/** Each of the flags at most once, in any order, each with a value (except --no-plugins, which has none); anything else is a usage error. */
internal fun parseArgsOrThrow(args: Array<String>): ParsedArgs {
    var root: String? = null
    val coreArgs = ArrayList<String>()
    val seen = HashSet<String>()
    val ignore = LinkedHashSet<String>()
    var i = 0
    while (i < args.size) {
        val flag = args[i]
        if (flag == NO_PLUGINS || flag == NO_PATCHES) {
            if (!seen.add(flag)) throw ArgsException(USAGE)
            coreArgs.add(flag)
            i += 1
            continue
        }
        if (flag == IGNORE_HOTFIX) {
            if (i + 1 >= args.size || !FIX_ID.matches(args[i + 1])) {
                throw ArgsException("$USAGE\n$IGNORE_HOTFIX needs a hotfix ID (letters, digits, . _ -, up to 64 characters)")
            }
            if (ignore.add(args[i + 1])) {
                coreArgs.add(flag)
                coreArgs.add(args[i + 1])
            }
            i += 2
            continue
        }
        if ((flag != "--root" && flag !in CORE_FLAGS) || i + 1 >= args.size || !seen.add(flag)) throw ArgsException(USAGE)
        val value = args[i + 1]
        if (flag == "--root") {
            checkRootValue(value)
            root = value
        } else {
            coreArgs.add(flag)
            coreArgs.add(value)
        }
        i += 2
    }
    return ParsedArgs(root, coreArgs.toTypedArray(), NO_PATCHES in seen, ignore)
}

// Windows java.exe replaces every char it can't represent in the console codepage with '?' in argv,
// so by the time we see a '?' here the real path is already gone - there's nothing left to recover.
private fun checkRootValue(value: String) {
    if (value.contains('?')) {
        throw ArgsException(
            "[ERROR] --root value contains '?': $value\n" +
                "On Windows, java.exe replaces every character it can't show in the console's codepage " +
                "with '?' in argv, so this path is already corrupted. Run start.bat from the server " +
                "folder instead of passing --root."
        )
    }
}
