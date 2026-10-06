package xyz.felismp.shoparchive.api.plugin

import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.api.ServiceRegistry
import java.nio.file.Path

/** The api version this server speaks. A plugin whose plugin.yml `api-version` differs is not loaded. */
const val PLUGIN_API_VERSION = 1

/** A plugin's log: every line carries the plugin's name, so the admin can tell whose line it is. */
interface PluginLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String, cause: Throwable? = null)
    fun error(message: String, cause: Throwable? = null)
}

/**
 * Everything the server gives a plugin. The registries are the server's own, except that what the plugin
 * registers is owned by the plugin's [name] whatever owner it passes, so `plugins` can show what it overrides
 * and a failed plugin can be removed again.
 */
interface PluginContext {
    /** plugin.yml `name`. */
    val name: String

    /** plugin.yml `version`. */
    val version: String

    /** `plugins/<name>/`. The server creates it when the plugin loads; the plugin may write here and nowhere else. */
    val dataFolder: Path

    val logger: PluginLogger

    /** Register service implementations with a priority above the core's (0, so 1 or more) to replace the core's. Events are the `EventService` service. */
    val services: ServiceRegistry

    val commands: CommandRegistry

    /** HTTP endpoints below `/api/v1/x/<name>/`. Register them in [ShopPlugin.onEnable]. */
    val routes: PluginRoutes

    /** Nodes registered in [ShopPlugin.onLoad] reach `permissions.txt` and the user files. */
    val permissions: PermissionNodeRegistry

    /**
     * A one-time fix of the server's own data files, for a plugin that changes how something is stored. Callable only in
     * [ShopPlugin.onLoad], before the core reads its data; anywhere else it throws. [id] names the fix (letters, digits, `.`, `_`, `-`);
     * [files] are paths relative to the server root, inside `data/` or `record/`.
     *
     * The first time: every listed file that exists is copied to `data/datafix/<plugin>-<id>/` (kept for ever, not deleted),
     * then [fix] gets the full paths of the listed files, then a marker is written and the server logs it. Next starts see the
     * marker and do nothing (the server logs that at debug level). If [fix] throws, no marker is written, the copies stay, and the plugin
     * fails to load: the admin can restore the files from the copies. A path that leaves the allowed folders is refused.
     * [fix] must write files the way the server does (a temporary file next to it, then rename) so a crash cannot leave half a file.
     *
     * @return true if [fix] ran now, false if it had run before
     */
    fun dataFix(id: String, files: List<String>, fix: (List<Path>) -> Unit): Boolean

    /** The plugin's `config.yml` in [dataFolder], as read by the last [reloadConfig]. Empty until the file exists. */
    val config: PluginConfig

    /** Copies `config.yml` from the plugin's jar into [dataFolder] unless it is already there, then reads it. A missing resource is a no-op. */
    fun saveDefaultConfig()

    /** Reads `config.yml` again; if it cannot be read the previous values stay and an error is logged. */
    fun reloadConfig()
}

/**
 * Read access to a plugin's YAML config by dotted path (`mail.host`). A missing or wrongly typed value gives
 * the default, never an exception, so a hand-edited file cannot stop the plugin. It deliberately hides the YAML library.
 */
interface PluginConfig {
    fun contains(path: String): Boolean
    fun getString(path: String, default: String): String
    fun getInt(path: String, default: Int): Int
    fun getLong(path: String, default: Long): Long
    fun getDouble(path: String, default: Double): Double
    fun getBoolean(path: String, default: Boolean): Boolean

    /** A YAML list of scalars as text; empty if the key is missing or not a list. */
    fun getStringList(path: String): List<String>

    /** The dotted paths of all scalar and list values below [section] (all of them when [section] is empty). */
    fun keys(section: String = ""): List<String>
}
