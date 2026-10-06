package xyz.felismp.shoparchive.api.plugin

/**
 * The base class of a plugin's main class (the `main` entry of plugin.yml). It needs a public constructor
 * without parameters: the server creates it, hands it its [PluginContext] and then calls the three lifecycle
 * methods in order. Every method does nothing by default, so a plugin overrides only what it needs.
 *
 * Why three steps: [onLoad] runs before the server reads its user files and builds its services, so it is the
 * place to register permission nodes and service overrides. [onEnable] runs once the core is built and before
 * the network starts, so [PluginContext.services] already holds the core's own services and commands can be added.
 * [onDisable] runs on shutdown, in the reverse order of loading.
 */
abstract class ShopPlugin {
    private var attached: PluginContext? = null

    /** The plugin's own name, folder, logger and registries. Only usable from [onLoad] on. */
    val context: PluginContext
        get() = attached ?: error("the plugin context is only available once the server has loaded the plugin (not in the constructor)")

    /** Called by the server, once, before [onLoad]. A plugin must not call it. */
    fun attach(context: PluginContext) {
        check(attached == null) { "the plugin context was already attached" }
        attached = context
    }

    /** Before the server reads user files and builds its services: register permission nodes and service overrides here. */
    open fun onLoad() {}

    /** After the core is built and before the network starts: register commands, start work. A throw here disables only this plugin. */
    open fun onEnable() {}

    /**
     * After the admin typed `reload` or `reload <plugin name>`, once [PluginContext.config] holds the values
     * just read from `config.yml` (if the file could not be read the previous values are still there and an error
     * was logged). Re-read whatever you copied out of the config here. A throw is logged; the plugin stays enabled.
     */
    open fun onConfigReload() {}

    /** On shutdown. Keep it short: the server waits for it only as long as `plugins.disable-timeout-ms`. */
    open fun onDisable() {}
}
