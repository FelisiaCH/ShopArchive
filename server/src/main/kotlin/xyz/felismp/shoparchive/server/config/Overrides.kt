package xyz.felismp.shoparchive.server.config

/** A command-line flag the core cannot accept: unknown, without a value, given twice, or with a value outside what the key allows. */
internal class OverrideException(message: String) : Exception(message)

/**
 * Values from the command line, which win over `server.properties` for this process only: they are put on
 * top of the loaded values and never reach the file template.
 */
internal class Overrides private constructor(private val byKey: Map<Key<*>, Any>, val noPlugins: Boolean = false, val noPatches: Boolean = false, val ignoreHotfix: List<String> = emptyList()) {
    fun applyTo(values: Values): Values = values.withAll(byKey)

    /** The flags that were given, for the boot log. */
    fun flags(): List<String> = FLAGS.filterValues { it in byKey }.keys.toList() + listOfNotNull(NO_PLUGINS.takeIf { noPlugins }, NO_PATCHES.takeIf { noPatches }) + ignoreHotfix.map { "$IGNORE_HOTFIX $it" }

    companion object {
        val NONE = Overrides(emptyMap())

        /** Safe mode: the one flag without a value. No plugin is loaded, and `plugins/update/` is left alone. */
        const val NO_PLUGINS = "--no-plugins"

        /** The launcher applied no hotfix this run. The launcher acts on it; the core shows it. */
        const val NO_PATCHES = "--no-patches"

        /** `--ignore-hotfix <ID>` (repeatable): the launcher started although that hotfix is not applied; the core warns about it at every start. */
        const val IGNORE_HOTFIX = "--ignore-hotfix"

        private val FIX_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        private val FLAGS: Map<String, Key<*>> = linkedMapOf(
            "--port" to ServerProperties.port,
            "--bind-address" to ServerProperties.bindAddress,
            "--log-level" to ServerProperties.logLevel,
        )

        /** Unlike a file value, an out-of-range value is refused here, not clamped: a typo on the command line should not start the server on another port. */
        fun parse(args: Array<String>): Overrides {
            val found = LinkedHashMap<Key<*>, Any>()
            var noPlugins = false
            var noPatches = false
            val ignore = LinkedHashSet<String>()
            var i = 0
            while (i < args.size) {
                val flag = args[i]
                if (flag == NO_PLUGINS) {
                    if (noPlugins) throw OverrideException("$flag was given twice")
                    noPlugins = true
                    i += 1
                    continue
                }
                if (flag == NO_PATCHES) {
                    if (noPatches) throw OverrideException("$flag was given twice")
                    noPatches = true
                    i += 1
                    continue
                }
                if (flag == IGNORE_HOTFIX) {
                    val id = args.getOrNull(i + 1)?.takeIf { FIX_ID.matches(it) } ?: throw OverrideException("$flag needs a hotfix ID")
                    ignore += id
                    i += 2
                    continue
                }
                val key = FLAGS[flag] ?: throw OverrideException("unknown argument '$flag' (known: ${(FLAGS.keys + NO_PLUGINS + NO_PATCHES + IGNORE_HOTFIX).joinToString()})")
                if (i + 1 >= args.size) throw OverrideException("$flag needs a value")
                val raw = args[i + 1]
                val value = key.parseExact(raw.trim())
                    ?: throw OverrideException("invalid value '$raw' for $flag (expected ${key.allowed})")
                if (found.put(key, value) != null) throw OverrideException("$flag was given twice")
                i += 2
            }
            return Overrides(found, noPlugins, noPatches, ignore.toList())
        }
    }
}
