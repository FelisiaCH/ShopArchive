package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.api.RegistryConflictException
import xyz.felismp.shoparchive.api.plugin.PLUGIN_API_VERSION
import xyz.felismp.shoparchive.api.plugin.ShopPlugin
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.CORE_RELOAD_NAMES
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.PluginReloads
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.writeAtomically
import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.PluginSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.util.jar.JarFile

/**
 * The owner id a plugin's registrations carry in the registries. It is not the plugin's bare name, so no plugin
 * can be taken for (or take back the registrations of) the core, whatever name its plugin.yml gives.
 */
internal fun pluginOwner(name: String) = "plugin:$name"

/** Names a plugin may not have: they are the core's own owner ids (and words that would read like them in `plugins` and in logs). */
private val RESERVED_NAMES = setOf("core", "console", "server", "shoparchive", "api", "plugin", "plugins")

internal enum class PluginState(val label: String) {
    /** Loaded (`onLoad` ran) and waiting for [PluginManager.enableAll]. */
    LOADED("loaded"),
    ENABLED("enabled"),

    /** Stopped on shutdown. */
    DISABLED("disabled"),

    /** Threw while loading or enabling, or a plugin it depends on did; the rest of the server carries on. */
    FAILED("disabled-error"),
    NOT_APPROVED("not-approved"),

    /** A hotfix jar: the launcher decided about it before the core started ([HotfixReport]); nothing of it runs as a plugin. */
    HOTFIX("hotfix"),

    /** The jar itself cannot be loaded (bad plugin.yml, wrong api-version, shaded Kotlin, missing dependency ...). */
    REJECTED("rejected"),
}

/** One jar in `plugins/` and what became of it. */
internal class PluginEntry(val file: Path) {
    /** The private copy `cache/plugins/<sha256>.jar` that was hashed, inspected and (if it loads) run: the file in `plugins/` can change after the hash was taken. */
    var snapshot: Path? = null
    var yml: PluginYml? = null
    var sha256: String = ""
    var state = PluginState.REJECTED
    var detail: String = ""
    var context: PluginContextImpl? = null
    var instance: ShopPlugin? = null
    var loader: PluginClassLoader? = null

    /** The plugin's name, or its file name if plugin.yml could not be read. */
    val displayName: String get() = yml?.name ?: file.fileName.toString()

    /** `enabled`, `rejected:<why>` ... as `plugins` prints it. */
    val stateText: String get() = when (state) {
        PluginState.REJECTED -> "rejected:$detail"
        PluginState.FAILED -> "disabled-error ($detail)"
        PluginState.NOT_APPROVED -> "not-approved"
        PluginState.HOTFIX -> "hotfix: $detail"
        else -> state.label
    }
}

/**
 * Finds, checks, loads and stops the plugins in `plugins/`. Nothing here runs plugin code until a jar has passed
 * every check that can be done by reading it (plugin.yml, api-version, no shaded Kotlin, approval, dependencies).
 *
 * Lifecycle, driven by Main: [loadAll] (updates, scan, `onLoad`) right after the config is read and before the user
 * files and services; [enableAll] once the core is built and before the network starts; [disableAll] on shutdown.
 * A plugin that throws is disabled and its registrations are taken back, except a [RegistryConflictException], which
 * is not a plugin's own fault but a clash between two owners and stops the server from starting (the existing registry contract).
 */
internal class PluginManager(
    private val root: Path,
    private val settings: PluginSettings,
    private val services: Services,
    private val commands: Commands,
    private val permissions: Permissions,
    private val safeMode: Boolean = false,
    private val coreLoader: ClassLoader = PluginManager::class.java.classLoader,
    private val hostKotlin: KotlinVersion = KotlinVersion.CURRENT,
    private val apiVersion: Int = PLUGIN_API_VERSION,
    private val clock: Clock = Clock.systemUTC(),
    /** What the launcher decided about the hotfix jars before the core was loaded. */
    private val hotfixes: HotfixReport = HotfixReport.fromSystemProperty(),
    private val build: BuildInfo = BuildInfo.load(),
    /** `--no-patches` was given (the launcher applied no hotfix). */
    private val noPatches: Boolean = false,
) : PluginReloads {
    private val pluginsDir = root.resolve("plugins")

    /** Jars as they were when read; cache, so it may be deleted and is rebuilt at every start. */
    private val snapshotDir = root.resolve("cache/plugins")
    private val realRoot: Path get() = root.toRealPath()

    /** Why plugins are not loaded at all (the snapshot folder is not where it must be), or null. */
    private var blocked: String? = null
    private val approvedStore = ApprovedPlugins(pluginsDir.resolve("approved.yml"))
    private val filter = ApiFilterClassLoader(coreLoader)
    private val updates = PluginUpdates(pluginsDir, clock)
    private val dataFixes = DataFixes(root, clock)

    /** What the plugins registered as HTTP routes; Main hands it to the core's [xyz.felismp.shoparchive.api.plugin.PluginRouteService]. */
    val routes = PluginRouteTable()

    /** Every jar found at start, by file name. */
    private val found = mutableListOf<PluginEntry>()

    /** The plugins that got past the checks, in load order. */
    private val ordered = mutableListOf<PluginEntry>()

    val entries: List<PluginEntry> @Synchronized get() = found.sortedBy { it.displayName.lowercase() }

    /** The jars that are plugins (a hotfix jar is not one). */
    private val plugins: List<PluginEntry> get() = found.filter { it.yml?.hotfix != true }

    /** True when `--no-plugins` turned plugins off for this run. */
    val isSafeMode: Boolean get() = safeMode

    /** Replaces jars from `plugins/update/`, scans `plugins/`, and runs `onLoad` of every plugin that may load. */
    @Synchronized
    fun loadAll() {
        hotfixes.log()
        if (safeMode) {
            Log.info("Safe mode (--no-plugins): no plugin is loaded and plugins/update/ is left alone")
            return
        }
        Files.createDirectories(pluginsDir)
        updates.apply()
        blocked = snapshotDirProblem()
        blocked?.let {
            Log.error("No plugin is loaded: $it. Nothing was deleted or written there; fix or remove it and restart.")
            return
        }
        clearSnapshots()
        val approved = try {
            approvedStore.read()
        } catch (e: ConfigFileException) {
            Log.error("${e.message}; treating every plugin as not approved. The file is left untouched.")
            emptyMap()
        }
        val names = HashMap<String, PluginEntry>()
        for (file in jarsIn(pluginsDir)) {
            val entry = inspect(file)
            found += entry
            val yml = entry.yml
            if (yml != null && entry.state != PluginState.REJECTED) {
                val first = names.putIfAbsent(yml.name, entry)
                if (first != null) reject(entry, "a second jar for plugin '${yml.name}' (the first is ${first.file.fileName})")
            }
            yml?.unknownKeys?.takeIf { it.isNotEmpty() }?.let { Log.warn("${file.fileName}: plugin.yml has unknown keys ${it.joinToString()} (ignored)") }
            if (entry.state == PluginState.REJECTED) {
                Log.error("Plugin ${entry.displayName} (${file.fileName}) is not loaded: ${entry.detail}")
                continue
            }
            if (yml!!.hotfix) {
                showHotfix(entry)
                continue
            }
            if (settings.requireApproval && approved[yml.name] != entry.sha256) {
                entry.state = PluginState.NOT_APPROVED
                val why = if (yml.name in approved) "has changed since it was approved" else "is new"
                entry.detail = "$why (SHA-256 ${entry.sha256})"
                Log.warn(
                    "Plugin ${yml.name} (${file.fileName}) $why and is not loaded. SHA-256 ${entry.sha256}. " +
                        "A plugin has full access to this server: if you built or audited this jar, type: plugins approve ${yml.name} (applies at the next start)",
                )
                continue
            }
            entry.state = PluginState.LOADED // provisionally: still to pass the dependency check
        }

        val candidates = found.filter { it.state == PluginState.LOADED }
        ordered += resolveOrder(candidates)
        val loaded = HashMap<String, PluginEntry>()
        for (entry in ordered) {
            val yml = entry.yml!!
            val missing = yml.depend.filter { loaded[it]?.state != PluginState.LOADED }
            if (missing.isNotEmpty()) {
                fail(entry, "needs ${missing.joinToString()}, which did not load", null)
                continue
            }
            if (load(entry, (yml.depend + yml.softdepend).mapNotNull { loaded[it]?.takeIf { dep -> dep.state == PluginState.LOADED } })) loaded[yml.name] = entry
        }
        if (plugins.isNotEmpty()) Log.info("Loaded ${ordered.count { it.state == PluginState.LOADED }} of ${plugins.size} plugin(s)")
    }

    /** Hotfix jars never load as plugins (they have no main class). What became of one is what the launcher reported for exactly this jar. */
    private fun showHotfix(entry: PluginEntry) {
        val line = hotfixes.bySha(entry.sha256)
        when (line?.state) {
            null -> {
                entry.state = PluginState.HOTFIX
                entry.detail = "not seen at startup (new or replaced by plugins/update/): approve it if needed and restart to apply it"
                Log.warn("Hotfix ${entry.displayName} (${entry.file.fileName}) was not read at startup, so it is not applied: restart the server to apply it")
            }
            HotfixState.NOT_APPROVED -> {
                entry.state = PluginState.NOT_APPROVED
                entry.detail = line.detail
            }
            HotfixState.REJECTED -> reject(entry, line.detail)
            else -> {
                entry.state = PluginState.HOTFIX
                entry.detail = line.state.label
            }
        }
    }

    /** Runs `onEnable` of the loaded plugins in load order. */
    @Synchronized
    fun enableAll() {
        for (entry in ordered) {
            if (entry.state != PluginState.LOADED) continue
            val notEnabled = entry.yml!!.depend.filter { dep -> ordered.firstOrNull { it.yml!!.name == dep }?.state != PluginState.ENABLED }
            if (notEnabled.isNotEmpty()) {
                fail(entry, "needs ${notEnabled.joinToString()}, which is not enabled", null)
                continue
            }
            try {
                entry.context!!.routesOpen = true
                try {
                    entry.instance!!.onEnable()
                } finally {
                    entry.context!!.routesOpen = false
                }
                entry.state = PluginState.ENABLED
                Log.info("Enabled ${entry.displayName} ${entry.yml!!.version}")
                if (entry.displayName.lowercase() in CORE_RELOAD_NAMES) {
                    Log.warn("Plugin ${entry.displayName}: 'reload ${entry.displayName.lowercase()}' reloads the server's own ${entry.displayName.lowercase()}, not this plugin; a plain 'reload' still reloads its config")
                }
            } catch (e: RegistryConflictException) {
                throw e
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                fail(entry, "onEnable threw ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * Stops the plugins that are loaded, last loaded first. Each `onDisable` gets `plugins.disable-timeout-ms`; one
     * that does not return in time is left running on its own thread and the stop goes on.
     */
    @Synchronized
    fun disableAll() {
        for (entry in ordered.asReversed()) {
            if (entry.state != PluginState.ENABLED && entry.state != PluginState.LOADED) continue
            val timedOut = !runWithTimeout(entry)
            if (timedOut) Log.error("Plugin ${entry.displayName}: onDisable did not finish within ${settings.disableTimeoutMs} ms; stopping without it")
            entry.state = PluginState.DISABLED
            takeBack(entry)
            if (!timedOut) closeLoader(entry)
            Log.info("Disabled ${entry.displayName}")
        }
    }

    /** False if `onDisable` was still running when the time was up; its loader is then closed when it does return. */
    private fun runWithTimeout(entry: PluginEntry): Boolean {
        val lock = Any()
        var finished = false
        var abandoned = false
        val thread = Thread({
            try {
                entry.instance?.onDisable()
            } catch (e: Throwable) {
                val context = entry.context
                Log.error("Plugin ${entry.displayName}: onDisable threw ${e.javaClass.simpleName}: ${context?.maskSecrets(e.message.orEmpty()) ?: e.message}", context?.maskSecrets(e) ?: e)
            } finally {
                synchronized(lock) {
                    finished = true
                    if (abandoned) closeLoader(entry)
                }
            }
        }, "plugin-disable-${entry.displayName}")
        thread.isDaemon = true
        thread.contextClassLoader = entry.loader
        thread.start()
        thread.join(settings.disableTimeoutMs.toLong())
        synchronized(lock) {
            if (!finished) abandoned = true
            return finished
        }
    }

    override fun names(): List<String> = synchronized(this) { enabledPlugins().map { it.displayName } }

    private fun enabledPlugins() = entries.filter { it.state == PluginState.ENABLED && it.context != null }

    @Synchronized
    override fun reload(name: String): Boolean {
        val entry = enabledPlugins().firstOrNull { it.displayName == name } ?: enabledPlugins().firstOrNull { it.displayName.equals(name, ignoreCase = true) } ?: return false
        reloadConfigOf(entry)
        return true
    }

    @Synchronized
    override fun reloadAll(): List<String> = enabledPlugins().onEach(::reloadConfigOf).map { it.displayName }

    /** Re-reads the plugin's config.yml, then tells the plugin. A plugin that throws in onConfigReload stays enabled: only its reload failed. */
    private fun reloadConfigOf(entry: PluginEntry) {
        if (!entry.context!!.reload()) return // already logged; the plugin keeps the values it has, so there is nothing new to tell it
        try {
            entry.instance!!.onConfigReload()
            Log.info("Reloaded config of ${entry.displayName}")
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            val context = entry.context!!
            Log.error("Plugin ${entry.displayName}: onConfigReload threw ${e.javaClass.simpleName}: ${context.maskSecrets(e.message.orEmpty())}; the plugin stays enabled", context.maskSecrets(e))
        }
    }

    /** `plugins approve <name|file>`: approves the jar as it is on disk now. Returns the lines to show. */
    @Synchronized
    fun approve(target: String): List<String> {
        val query = target.trim()
        if (query.isEmpty()) return listOf("Usage: plugins approve <name|file>")
        val matches = jarsIn(pluginsDir).map { inspect(it) }.filter {
            val file = it.file.fileName.toString()
            file == query || file == "$query.jar" || it.yml?.name == query
        }
        if (matches.isEmpty()) return listOf("No jar in plugins/ matches '$query' (give the plugin name or the file name)")
        if (matches.size > 1) return listOf("'$query' matches ${matches.size} jars (${matches.joinToString { it.file.fileName.toString() }}); give the file name")
        val entry = matches.single()
        if (entry.state == PluginState.REJECTED) return listOf("Cannot approve ${entry.file.fileName}: ${entry.detail}")
        return try {
            approvedStore.approve(entry.yml!!.name, entry.sha256)
            Log.info("Plugin ${entry.yml!!.name} approved: SHA-256 ${entry.sha256}")
            listOf("Approved ${entry.yml!!.name} (${entry.file.fileName}), SHA-256 ${entry.sha256}", "It is loaded at the next start.")
        } catch (e: ConfigFileException) {
            listOf("${e.message}. Fix or remove plugins/approved.yml first; it was not changed.")
        }
    }

    /** `plugins revoke <name>`. Returns the lines to show. */
    @Synchronized
    fun revoke(name: String): List<String> {
        if (name.isBlank()) return listOf("Usage: plugins revoke <name>")
        return try {
            if (approvedStore.revoke(name.trim())) listOf("Approval of '${name.trim()}' removed. The plugin is not loaded from the next start on (until it is approved again).")
            else listOf("'${name.trim()}' is not approved")
        } catch (e: ConfigFileException) {
            listOf("${e.message}. Fix or remove plugins/approved.yml first; it was not changed.")
        }
    }

    /**
     * Reads [file] once, hashes exactly those bytes and keeps them as `cache/plugins/<sha256>.jar` (written atomically).
     * Approval, inspection and loading all use this copy, so what the admin approved is what runs even if `plugins/x.jar` is swapped meanwhile.
     */
    private fun snapshot(file: Path, entry: PluginEntry): Path {
        val bytes = Files.readAllBytes(file)
        entry.sha256 = sha256(bytes)
        snapshotDirProblem()?.let { throw IOException(it) }
        Files.createDirectories(snapshotDir)
        val copy = snapshotDir.resolve("${entry.sha256}.jar")
        // A copy that is already there and is the same bytes may be in use by a loaded plugin: leave it alone.
        val same = Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS) && try { sha256(Files.readAllBytes(copy)) == entry.sha256 } catch (_: IOException) { false }
        if (!same) writeAtomically(copy, bytes)
        return copy
    }

    /** The snapshots of an earlier run are not needed any more; the jars in `plugins/` are the source. */
    private fun clearSnapshots() {
        if (!Files.isDirectory(snapshotDir)) return
        // Only what this class wrote: `<64 hex>.jar` as a plain file. A link, a folder or any other name is not ours to delete.
        Files.list(snapshotDir).use { it.filter { f -> SNAPSHOT_NAME.matches(f.fileName.toString()) && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) }.toList() }.forEach {
            try { Files.delete(it) } catch (e: IOException) { Log.warn("cache/plugins/${it.fileName} could not be deleted (${e.message})") }
        }
    }

    /**
     * Null if `cache/plugins` really is `cache/plugins` below the real server root (it may not exist yet). A link anywhere on that way
     * (cache -> elsewhere, cache/plugins -> ../../plugins) would make the cleanup delete, or the snapshots land in, folders that are not ours.
     */
    private fun snapshotDirProblem(): String? {
        var existing: Path = snapshotDir.toAbsolutePath().normalize()
        val wanted = realRoot.resolve("cache/plugins")
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
        // A dangling link exists for NOFOLLOW but cannot be made real: that is a link too.
        val real = try { existing.toRealPath() } catch (_: IOException) { return "cache/plugins leads nowhere (a broken symbolic link?)" }
        val resolved = real.resolve(existing.relativize(snapshotDir.toAbsolutePath().normalize())).normalize()
        return if (resolved == wanted) null else "cache/plugins is not a plain folder of this server: it leads to $resolved (a symbolic link?)"
    }

    /** Reads the jar without running any of it; the result is REJECTED (with [PluginEntry.detail]) or LOADED (provisionally). */
    private fun inspect(file: Path): PluginEntry {
        val entry = PluginEntry(file)
        try {
            entry.snapshot = snapshot(file, entry)
            JarFile(entry.snapshot!!.toFile()).use { jar ->
                val shaded = jar.entries().asSequence().map { it.name }.firstOrNull { it.startsWith("kotlin/") || it.startsWith("kotlinx/") }
                val ymlEntry = jar.getJarEntry("plugin.yml")
                if (ymlEntry == null) return reject(entry, "the jar has no plugin.yml")
                if (ymlEntry.size > MAX_YML_BYTES) return reject(entry, "plugin.yml is larger than ${MAX_YML_BYTES / 1024} KB")
                entry.yml = try {
                    parsePluginYml(jar.getInputStream(ymlEntry).use { String(it.readNBytes(MAX_YML_BYTES), Charsets.UTF_8) })
                } catch (e: PluginYmlException) {
                    return reject(entry, e.message ?: "plugin.yml is not valid")
                }
                // URLClassLoader would follow Class-Path to jars nobody approved (it is also why the loader reads only the snapshot).
                if (jar.manifest?.mainAttributes?.getValue("Class-Path") != null) {
                    return reject(entry, "the jar's manifest has a Class-Path, which would load jars that are not approved: remove it (a plugin is one jar)")
                }
                if (shaded != null) {
                    return reject(entry, "the jar contains Kotlin classes ($shaded): do not shade kotlin or kotlinx into a plugin, the server provides them (use compileOnly)")
                }
            }
        } catch (e: IOException) {
            return reject(entry, "not a readable jar (${e.message})")
        }
        val yml = entry.yml!!
        if (yml.name.lowercase() in RESERVED_NAMES) {
            return reject(entry, "the plugin name '${yml.name}' is reserved for the server (not one of: ${RESERVED_NAMES.sorted().joinToString()})")
        }
        if (!yml.hotfix && yml.apiVersion != apiVersion) {
            return reject(entry, "built for plugin api-version ${yml.apiVersion}, this server has $apiVersion")
        }
        entry.state = PluginState.LOADED
        return entry
    }

    private fun reject(entry: PluginEntry, why: String): PluginEntry {
        entry.state = PluginState.REJECTED
        entry.detail = why
        return entry
    }

    /**
     * The plugins that can load, dependencies first, then by name. A plugin that needs one that is missing or not
     * loadable, or that is part of a `depend` cycle, is rejected here with a message naming the plugins involved.
     * `softdepend` only orders (and a cycle made of soft links is broken, not an error).
     */
    private fun resolveOrder(candidates: List<PluginEntry>): List<PluginEntry> {
        val live = candidates.associateBy { it.yml!!.name }.toSortedMap()
        val everything = found.filter { it.yml != null }.associateBy { it.yml!!.name }

        fun dependsOn(name: String) = live.getValue(name).yml!!.depend

        fun cyclePath(start: String): List<String>? {
            // Depth-first along depend links; a path that comes back to [start] is the cycle.
            val seen = HashSet<String>()
            fun walk(at: String, path: List<String>): List<String>? {
                for (next in dependsOn(at).filter { it in live }) {
                    if (next == start) return path + next
                    if (seen.add(next)) walk(next, path + next)?.let { return it }
                }
                return null
            }
            return walk(start, listOf(start))
        }

        while (true) {
            var changed = false
            for (name in live.keys.toList()) {
                val missing = dependsOn(name).filter { it !in live }
                if (missing.isEmpty()) continue
                val why = missing.joinToString("; ") { dep ->
                    val other = everything[dep]
                    if (other == null) "needs '$dep', which is not installed"
                    else "needs '$dep', which is not loaded (${other.stateText})"
                }
                reject(live.remove(name)!!, why)
                Log.error("Plugin $name is not loaded: $why")
                changed = true
            }
            if (changed) continue
            val cyclic = live.keys.mapNotNull { name -> cyclePath(name)?.let { name to it } }
            if (cyclic.isEmpty()) break
            for ((name, path) in cyclic) {
                val why = "depend cycle: ${path.joinToString(" -> ")}"
                reject(live.remove(name)!!, why)
                Log.error("Plugin $name is not loaded: $why")
            }
        }

        // Kahn's algorithm, always taking the lowest name that is ready.
        val result = mutableListOf<PluginEntry>()
        val done = HashSet<String>()
        val remaining = live.keys.toSortedSet()
        while (remaining.isNotEmpty()) {
            fun ready(name: String, soft: Boolean): Boolean {
                val yml = live.getValue(name).yml!!
                return yml.depend.all { it in done } && (!soft || yml.softdepend.filter { it in live }.all { it in done })
            }
            val next = remaining.firstOrNull { ready(it, soft = true) }
                ?: remaining.first { ready(it, soft = false) }.also {
                    Log.warn("Plugins ${remaining.joinToString()}: softdepend links form a cycle; loading $it first")
                }
            remaining.remove(next)
            done += next
            result += live.getValue(next)
        }
        return result
    }

    /** Loads one plugin's main class, checks it and runs `onLoad`. False if the plugin did not load. */
    private fun load(entry: PluginEntry, dependencies: List<PluginEntry>): Boolean {
        val yml = entry.yml!!
        var loader: PluginClassLoader? = null
        try {
            loader = PluginClassLoader(entry.snapshot!!.toUri().toURL(), filter, dependencies.mapNotNull { it.loader }, yml.name)
            entry.loader = loader
            val mainClass = try {
                Class.forName(yml.main, false, loader)
            } catch (e: ClassNotFoundException) {
                return failLoad(entry, "main class ${yml.main} is not in the jar", null)
            }
            if (!ShopPlugin::class.java.isAssignableFrom(mainClass)) {
                return failLoad(entry, "main class ${yml.main} does not extend ShopPlugin", null)
            }
            val newer = newerKotlin(mainClass)
            if (newer != null) {
                // Not run, so not "disabled-error": the jar is simply not for this server.
                reject(entry, newer)
                Log.error("Plugin ${yml.name} is not loaded: $newer")
                closeLoader(entry)
                return false
            }
            val dataFolder = pluginsDir.resolve(yml.name)
            Files.createDirectories(dataFolder)
            val context = PluginContextImpl(yml.name, yml.version, dataFolder, loader, services, commands, permissions, routes, dataFixes, settings.secretKeys)
            entry.context = context
            val plugin = mainClass.getDeclaredConstructor().newInstance() as ShopPlugin
            entry.instance = plugin
            plugin.attach(context)
            context.loading = true
            try {
                plugin.onLoad()
            } finally {
                context.loading = false
            }
            entry.state = PluginState.LOADED
            return true
        } catch (e: RegistryConflictException) {
            throw e
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            if (cause is RegistryConflictException) throw cause
            return failLoad(entry, "${cause.javaClass.simpleName}: ${cause.message}", cause)
        }
    }

    private fun failLoad(entry: PluginEntry, why: String, cause: Throwable?): Boolean {
        fail(entry, why, cause)
        return false
    }

    /** The message to reject with if [mainClass] was compiled by a newer Kotlin than this server runs, else null. */
    private fun newerKotlin(mainClass: Class<*>): String? {
        val version = mainClass.getAnnotation(Metadata::class.java)?.metadataVersion ?: return null
        if (version.size < 2) return null
        val newer = version[0] > hostKotlin.major || (version[0] == hostKotlin.major && version[1] > hostKotlin.minor)
        return if (newer) {
            "compiled with Kotlin ${version[0]}.${version[1]}, newer than this server's Kotlin ${hostKotlin.major}.${hostKotlin.minor}; rebuild it with Kotlin ${hostKotlin.major}.${hostKotlin.minor} or older"
        } else null
    }

    /** Marks [entry] failed, logs it with the stack trace, and takes back everything it registered. */
    private fun fail(entry: PluginEntry, why: String, cause: Throwable?) {
        // The message of a plugin's exception may quote its own secrets (a failed login...): nothing of it is stored or logged unmasked.
        val safeWhy = entry.context?.maskSecrets(why) ?: why
        entry.state = PluginState.FAILED
        entry.detail = safeWhy
        Log.error("Plugin ${entry.displayName} is disabled: $safeWhy", entry.context?.maskSecrets(cause) ?: cause)
        takeBack(entry)
        closeLoader(entry)
    }

    private fun takeBack(entry: PluginEntry) {
        val name = entry.yml?.name ?: return
        services.unregisterOwner(pluginOwner(name))
        commands.unregisterOwner(pluginOwner(name))
        routes.unregisterOwner(name)
        entry.context?.registeredNodes()?.let(permissions::unregister)
        entry.context?.endSubscriptions()
    }

    private fun closeLoader(entry: PluginEntry) {
        try {
            entry.loader?.close()
        } catch (_: IOException) {
            // nothing useful to do: the jar is just no longer held open
        }
    }

    /** Lines for `status`. */
    fun statusLines(): List<String> {
        val patches = hotfixes.statusLine(build, noPatches)
        if (safeMode) return listOf("Plugins: off (--no-plugins)", patches)
        if (plugins.isEmpty()) return listOf(patches)
        return listOf("Plugins: ${plugins.count { it.state == PluginState.ENABLED }} enabled of ${plugins.size}", patches)
    }

    /** The lines `plugins info <name>` prints: what the jar says, what the plugin registered, and its config with secrets as `***`. */
    @Synchronized
    fun info(name: String): List<String> {
        val entry = entries.firstOrNull { it.displayName == name.trim() } ?: entries.firstOrNull { it.displayName.equals(name.trim(), ignoreCase = true) }
            ?: return listOf("No plugin '${name.trim()}' (type plugins to list them)")
        val yml = entry.yml
        val lines = mutableListOf("${entry.displayName}${yml?.version?.let { " $it" }.orEmpty()} - ${entry.stateText}")
        lines += "  file: ${entry.file.fileName}" + if (entry.sha256.isNotEmpty()) " (SHA-256 ${entry.sha256})" else ""
        if (yml != null) {
            if (!yml.hotfix) lines += "  main: ${yml.main}, api-version ${yml.apiVersion}"
            if (yml.depend.isNotEmpty()) lines += "  depend: ${yml.depend.joinToString()}"
            if (yml.softdepend.isNotEmpty()) lines += "  softdepend: ${yml.softdepend.joinToString()}"
            if (yml.hotfix) lines += "  hotfix for ${yml.fixes.joinToString()} (${yml.severity}), made for core build ${yml.targetBuild}; this core is build ${build.build ?: "unknown"}"
        }
        val context = entry.context
        if (context != null && (entry.state == PluginState.ENABLED || entry.state == PluginState.LOADED)) {
            services.ownedBy(pluginOwner(entry.displayName)).takeIf { it.isNotEmpty() }
                ?.let { lines += "  services: ${it.joinToString { o -> "${o.type.simpleName} (priority ${o.priority}${if (o.overridesCore) ", overrides the core" else ""})" }}" }
            routes.ownedBy(entry.displayName).takeIf { it.isNotEmpty() }
                ?.let { lines += "  routes: ${it.joinToString { r -> r.substringBefore(' ') + " /api/v1/x/${entry.displayName}" + r.substringAfter(' ').removeSuffix("/") }}" }
            context.registeredNodes().takeIf { it.isNotEmpty() }?.let { lines += "  permission nodes: ${it.joinToString()}" }
            lines += "  data folder: ${context.dataFolder}"
            val config = context.describeConfig()
            lines += if (config.isEmpty()) listOf("  config: (none)") else listOf("  config (secrets shown as ***):") + config.map { "    $it" }
        }
        return lines
    }

    /** The lines `plugins` prints. */
    @Synchronized
    fun describe(): List<String> {
        if (safeMode) return listOf("Plugins are off for this run (--no-plugins)") + hotfixLines()
        blocked?.let { return listOf("No plugin is loaded: $it") + hotfixLines() }
        val hotfixSection = hotfixLines()
        if (plugins.isEmpty()) return if (hotfixSection.isEmpty()) listOf("No plugins (put jars in plugins/ and restart)") else hotfixSection
        val lines = mutableListOf("Plugins (${plugins.size}):")
        for (entry in entries.filter { it.yml?.hotfix != true }) {
            val version = entry.yml?.version?.let { " $it" }.orEmpty()
            lines += " ${entry.displayName}$version - ${entry.stateText} - ${entry.file.fileName}"
            val name = entry.yml?.name
            if (name != null && (entry.state == PluginState.ENABLED || entry.state == PluginState.LOADED)) {
                val owned = services.ownedBy(pluginOwner(name))
                owned.filter { it.overridesCore }.takeIf { it.isNotEmpty() }
                    ?.let { lines += "   overrides: ${it.joinToString { o -> "${o.type.simpleName} (priority ${o.priority})" }}" }
                owned.filter { !it.overridesCore }.takeIf { it.isNotEmpty() }
                    ?.let { lines += "   provides: ${it.joinToString { o -> "${o.type.simpleName} (priority ${o.priority})" }}" }
            }
        }
        return lines + hotfixSection
    }

    /** What `plugins` says about hotfixes: the launcher's decision per jar, and jars it did not see at startup. */
    private fun hotfixLines(): List<String> {
        val shown = hotfixes.entries.filter { it.state != HotfixState.UNUSED_IGNORE }
        val unseen = found.filter { it.yml?.hotfix == true && hotfixes.bySha(it.sha256) == null }
        if (shown.isEmpty() && unseen.isEmpty()) return emptyList()
        val lines = mutableListOf("Hotfixes (core build ${build.build ?: "unknown"}):")
        for (e in shown) {
            val why = if (e.state in setOf(HotfixState.SKIPPED, HotfixState.NOT_APPROVED, HotfixState.REJECTED)) " (${e.detail})" else ""
            lines += " ${e.name} [${e.severity.ifEmpty { "?" }}: ${e.idsText}] - ${e.state.label}$why"
        }
        for (e in unseen) lines += " ${e.displayName} - not seen at startup: restart the server to apply it"
        return lines
    }

    companion object {
        private const val MAX_YML_BYTES = 64 * 1024
        private val SNAPSHOT_NAME = Regex("[0-9a-f]{64}\\.jar")
    }
}

/** The `.jar` files directly in [dir], by file name ignoring case; none if [dir] does not exist. */
internal fun jarsIn(dir: Path): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()
    return Files.list(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase().endsWith(".jar") }
            .sorted(compareBy({ it.fileName.toString().lowercase() }, { it.fileName.toString() }))
            .toList()
    }
}
