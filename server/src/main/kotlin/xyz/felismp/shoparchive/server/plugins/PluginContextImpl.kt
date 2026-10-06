package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.ShopEvent
import xyz.felismp.shoparchive.api.ShopEventListener
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.api.plugin.PluginConfig
import xyz.felismp.shoparchive.api.plugin.PluginContext
import xyz.felismp.shoparchive.api.plugin.PluginLogger
import xyz.felismp.shoparchive.api.plugin.PluginRoutes
import xyz.felismp.shoparchive.server.Commands
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.Permissions
import xyz.felismp.shoparchive.server.Services
import xyz.felismp.shoparchive.server.config.ConfigFileException
import xyz.felismp.shoparchive.server.config.DEFAULT_SECRET_KEYS
import xyz.felismp.shoparchive.server.net.CORE_SERVICE_PRIORITY
import xyz.felismp.shoparchive.server.config.flatten
import xyz.felismp.shoparchive.server.config.parseYamlMap
import xyz.felismp.shoparchive.server.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which values of one plugin's config are secrets, and the masking of them. A key is secret when its last path
 * segment, split into words (`botToken` and `bot_token` both become `bot-token`) and lower-cased, equals one of
 * [secretKeys] or ends in `-<word>`: `token`, `bot-token`, `discordWebhook` and `API_KEY` match `token`/`webhook`/`api-key`,
 * `tokens`, `monkey` and `key-count` do not. Only the last segment counts: `mail.password` is secret, `password.min-length` is not.
 * A secret may be a list (each element is one). The values are read through [config] each time, so a reload is followed at once.
 * Masking is by value, so it also hides the secret where the plugin logs it in some other text, e.g. in a URL.
 */
internal class PluginSecrets(private val secretKeys: List<String>, private val config: () -> PluginConfig) {
    fun isSecret(path: String): Boolean {
        val segment = words(path.substringAfterLast('.'))
        return secretKeys.any { word -> segment == word || segment.endsWith("-$word") }
    }

    /** The current secret values worth hiding: not blank, at least [MIN_LENGTH] characters (a shorter one would hide ordinary text), longest first so one that contains another is hidden whole. */
    private fun values(): List<String> {
        val current = config()
        return current.keys().filter(::isSecret)
            .flatMap { key -> current.getStringList(key) + current.getString(key, "") }
            .filter { it.isNotBlank() && it.length >= MIN_LENGTH }
            .distinct().sortedByDescending { it.length }
    }

    fun mask(text: String): String = values().fold(text) { acc, secret -> acc.replace(secret, MASK) }

    /**
     * [cause] with its message, its causes' and its suppressed exceptions' messages masked; the same object if nothing needed hiding.
     * The stack traces are kept. A reference back to an exception already being masked (a cycle) is cut.
     */
    fun mask(cause: Throwable?): Throwable? = mask(cause, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))

    private fun mask(t: Throwable?, visiting: MutableSet<Throwable>): Throwable? {
        if (t == null || !visiting.add(t)) return null
        val message = t.message
        val maskedMessage = message?.let(::mask)
        val maskedCause = mask(t.cause, visiting)
        val suppressed = t.suppressed.toList()
        val maskedSuppressed = suppressed.map { mask(it, visiting) }
        visiting.remove(t)
        val unchanged = maskedMessage == message && maskedCause === t.cause && suppressed.indices.all { maskedSuppressed[it] === suppressed[it] }
        if (unchanged) return t
        return MaskedException("${t.javaClass.name}: ${maskedMessage ?: ""}", maskedCause).also { copy ->
            copy.stackTrace = t.stackTrace
            maskedSuppressed.filterNotNull().forEach(copy::addSuppressed)
        }
    }

    private fun words(segment: String) = segment.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").replace('_', '-').lowercase()

    /** A copy of an exception whose message had a secret in it; the name of the original class is in its message. */
    private class MaskedException(message: String, cause: Throwable?) : RuntimeException(message, cause)

    companion object {
        const val MASK = "***"
        const val MIN_LENGTH = 4
    }
}

/** The server's log with the plugin's name in front of every line, and the plugin's secrets (see [PluginSecrets]) left out of it. */
internal class PrefixedLogger(private val name: String, private val secrets: PluginSecrets? = null) : PluginLogger {
    private fun text(message: String) = "[$name] " + (secrets?.mask(message) ?: message)

    override fun debug(message: String) = Log.debug(text(message))
    override fun info(message: String) = Log.info(text(message))
    override fun warn(message: String, cause: Throwable?) = Log.warn(text(message), secrets?.mask(cause) ?: cause)
    override fun error(message: String, cause: Throwable?) = Log.error(text(message), secrets?.mask(cause) ?: cause)
}

/** A plugin's `config.yml` read once into dotted paths; scalars stay text and are converted on read, so a wrong type gives the default. */
internal class FlatPluginConfig(private val values: Map<String, Any?>) : PluginConfig {
    override fun contains(path: String) = values[path] != null
    override fun getString(path: String, default: String) = (values[path] as? String) ?: default
    override fun getInt(path: String, default: Int) = (values[path] as? String)?.trim()?.toIntOrNull() ?: default
    override fun getLong(path: String, default: Long) = (values[path] as? String)?.trim()?.toLongOrNull() ?: default
    override fun getDouble(path: String, default: Double) = (values[path] as? String)?.trim()?.toDoubleOrNull() ?: default
    override fun getBoolean(path: String, default: Boolean) = when ((values[path] as? String)?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

    override fun getStringList(path: String) = (values[path] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    override fun keys(section: String): List<String> =
        if (section.isEmpty()) values.keys.toList() else values.keys.filter { it.startsWith("$section.") }

    companion object {
        val EMPTY = FlatPluginConfig(emptyMap())
    }
}

/**
 * [ShopEvents] as a plugin sees it: what it subscribes is remembered in [subscriptions] so the server can end it when the plugin is
 * disabled or fails, and a listener that throws is logged in the plugin's own log (secrets masked) instead of the core's.
 */
private class OwnedEvents(
    private val events: ShopEvents,
    private val subscriptions: MutableList<AutoCloseable>,
    private val logger: PluginLogger,
) : ShopEvents {
    override fun subscribe(type: String, listener: ShopEventListener): AutoCloseable {
        val subscription = events.subscribe(type) { event ->
            try {
                listener.onEvent(event)
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                logger.error("a listener of $type threw ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
        subscriptions += subscription
        return subscription
    }

    override fun publish(event: ShopEvent) = events.publish(event)
}

/** The service registry as a plugin sees it: whatever owner it passes, the registration is owned by the plugin. */
private class OwnedServices(
    private val owner: String,
    private val services: Services,
    private val subscriptions: MutableList<AutoCloseable>,
    private val logger: () -> PluginLogger,
) : ServiceRegistry {
    override fun <T : Any> register(type: Class<T>, implementation: T, priority: Int, owner: String) {
        // At the core's own priority a plugin would clash with the core's service whenever that one is built, which
        // is later than the plugin's onLoad; saying so here keeps the error with the plugin that caused it.
        require(priority > CORE_SERVICE_PRIORITY) { "a plugin service needs a priority above the core's ($CORE_SERVICE_PRIORITY), not $priority" }
        services.register(type, implementation, priority, this.owner)
    }

    override fun <T : Any> get(type: Class<T>): T? {
        val found = services.get(type) ?: return null
        return if (found is ShopEvents) type.cast(OwnedEvents(found, subscriptions, logger())) else found
    }
}

private class OwnedCommands(private val owner: String, private val commands: Commands) : CommandRegistry {
    override fun register(command: Command, owner: String) = commands.register(command, this.owner)
    override fun all() = commands.all()
    override fun find(name: String) = commands.find(name)
}

private class OwnedPermissions(private val permissions: Permissions, private val registered: MutableList<String>) : PermissionNodeRegistry {
    override fun register(node: PermissionNode) {
        permissions.register(node)
        registered += node.node
    }

    override fun all() = permissions.all()
}

/** What one loaded plugin is given. Registrations are tracked by owner so [PluginManager] can take them back if the plugin fails. */
internal class PluginContextImpl(
    override val name: String,
    override val version: String,
    override val dataFolder: Path,
    private val loader: PluginClassLoader,
    coreServices: Services,
    coreCommands: Commands,
    corePermissions: Permissions,
    routeTable: PluginRouteTable,
    private val dataFixes: DataFixes,
    secretKeys: List<String> = DEFAULT_SECRET_KEYS,
) : PluginContext {
    /** True while [PluginManager] runs `onEnable`: the only time routes may be registered. */
    @Volatile
    var routesOpen = false

    /** True while [PluginManager] runs `onLoad`: the only time the core has not read its data yet. */
    @Volatile
    var loading = false

    private val registeredNodes = CopyOnWriteArrayList<String>()
    private val eventSubscriptions = CopyOnWriteArrayList<AutoCloseable>()

    private val secrets = PluginSecrets(secretKeys) { config }

    override val logger: PluginLogger = PrefixedLogger(name, secrets)
    override val services: ServiceRegistry = OwnedServices(pluginOwner(name), coreServices, eventSubscriptions) { logger }
    override val routes: PluginRoutes = routeTable.registrar(name, logger) { routesOpen }
    override val commands: CommandRegistry = OwnedCommands(pluginOwner(name), coreCommands)
    override val permissions: PermissionNodeRegistry = OwnedPermissions(corePermissions, registeredNodes)

    @Volatile
    override var config: PluginConfig = FlatPluginConfig.EMPTY
        private set

    private val configFile: Path get() = dataFolder.resolve("config.yml")

    /** [text] without this plugin's secret config values: for everything the server itself says about a failure of this plugin's code. */
    fun maskSecrets(text: String): String = secrets.mask(text)

    /** [cause] with its messages masked (see [PluginSecrets.mask]). */
    fun maskSecrets(cause: Throwable?): Throwable? = secrets.mask(cause)

    /** The config as `key: value` lines for `plugins info`, secrets as `***`. */
    fun describeConfig(): List<String> = config.keys().sorted().map { key ->
        val list = config.getStringList(key)
        val value = if (list.isNotEmpty()) list.joinToString(", ", "[", "]") else config.getString(key, "").ifEmpty { "(empty)" }
        "$key: ${if (secrets.isSecret(key) && value != "(empty)") PluginSecrets.MASK else value}"
    }

    /** Ends every event subscription this plugin made and did not close; returns how many were still open. */
    fun endSubscriptions(): Int {
        val open = eventSubscriptions.toList()
        eventSubscriptions.clear()
        open.forEach { it.close() }
        return open.size
    }

    /** The permission nodes this plugin registered, for taking them back. */
    fun registeredNodes(): List<String> = registeredNodes.toList()

    override fun dataFix(id: String, files: List<String>, fix: (List<Path>) -> Unit): Boolean {
        check(loading) { "dataFix can only be called in onLoad, before the server reads its data" }
        return dataFixes.run(name, id, files, logger, fix)
    }

    override fun saveDefaultConfig() {
        if (!Files.exists(configFile)) {
            val resource = loader.ownResource("config.yml")
            if (resource == null) {
                logger.debug("the jar has no config.yml to copy")
                return
            }
            Files.createDirectories(dataFolder)
            writeAtomically(configFile, resource.openStream().use { it.readBytes() })
        }
        reloadConfig()
    }

    override fun reloadConfig() {
        reload()
    }

    /** [reloadConfig], saying whether the file was read (a missing file counts: it means "no values"). */
    fun reload(): Boolean {
        if (!Files.exists(configFile)) {
            config = FlatPluginConfig.EMPTY
            return true
        }
        return try {
            config = FlatPluginConfig(flatten(parseYamlMap("plugins/$name/config.yml", Files.readString(configFile))))
            true
        } catch (e: ConfigFileException) {
            logger.error("${e.message}; keeping the previous values")
            false
        } catch (e: java.io.IOException) {
            logger.error("config.yml cannot be read (${e.message}); keeping the previous values")
            false
        }
    }
}
