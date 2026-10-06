package xyz.felismp.shoparchive.server.plugins

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.plugin.PluginLogger
import xyz.felismp.shoparchive.api.plugin.PluginRequest
import xyz.felismp.shoparchive.api.plugin.PluginResponse
import xyz.felismp.shoparchive.api.plugin.PluginRouteService
import xyz.felismp.shoparchive.api.plugin.PluginRoutes
import xyz.felismp.shoparchive.server.net.require
import xyz.felismp.shoparchive.shared.ErrorCode

private typealias Handler = (PluginRequest) -> PluginResponse

/** One registered route: the method, the path as segments (`{name}` ones capture) and what to run. */
internal class PluginRoute(val plugin: String, val method: String, val pattern: List<String>, val handler: Handler, val logger: PluginLogger) {
    /** The captured `{name}` values if [segments] fits this route, else null. */
    fun match(segments: List<String>): Map<String, String>? {
        if (segments.size != pattern.size) return null
        val params = HashMap<String, String>()
        for ((expected, actual) in pattern.zip(segments)) {
            if (expected.startsWith("{")) params[expected.substring(1, expected.length - 1)] = actual
            else if (expected != actual) return null
        }
        return params
    }

    fun describe() = "$method /" + pattern.joinToString("/")
}

/**
 * The routes plugins registered, by plugin. Plugins register through [registrar] (only while [PluginContextImpl] says
 * registering is open); the manager takes a plugin's routes back with [unregisterOwner] when it fails or is stopped,
 * which is what makes a plugin that is not enabled serve nothing.
 */
internal class PluginRouteTable {
    private val routes = ArrayList<PluginRoute>()

    /** What one plugin sees. [isOpen] says whether registering is allowed right now (during `onEnable`). */
    fun registrar(plugin: String, logger: PluginLogger, isOpen: () -> Boolean): PluginRoutes = object : PluginRoutes {
        override fun get(path: String, handler: Handler) = add("GET", path, handler)
        override fun post(path: String, handler: Handler) = add("POST", path, handler)
        override fun put(path: String, handler: Handler) = add("PUT", path, handler)
        override fun delete(path: String, handler: Handler) = add("DELETE", path, handler)

        private fun add(method: String, path: String, handler: Handler) {
            check(isOpen()) { "routes can only be registered in onEnable ($method $path)" }
            val pattern = parsePattern(path)
            synchronized(this@PluginRouteTable) {
                require(routes.none { it.plugin == plugin && it.method == method && sameShape(it.pattern, pattern) }) { "route $method $path is registered twice" }
                routes += PluginRoute(plugin, method, pattern, handler, logger)
            }
        }
    }

    @Synchronized
    fun find(plugin: String, method: String, segments: List<String>): Pair<PluginRoute, Map<String, String>>? {
        for (route in routes) {
            if (route.plugin != plugin || route.method != method) continue
            route.match(segments)?.let { return route to it }
        }
        return null
    }

    /** `GET /items/{id}` lines of [plugin], in registration order. */
    @Synchronized
    fun ownedBy(plugin: String): List<String> = routes.filter { it.plugin == plugin }.map { it.describe() }

    @Synchronized
    fun unregisterOwner(plugin: String): Int {
        val before = routes.size
        routes.removeAll { it.plugin == plugin }
        return before - routes.size
    }

    /** `{a}` and `{b}` are the same shape: they match the same requests. */
    private fun sameShape(a: List<String>, b: List<String>) =
        a.size == b.size && a.zip(b).all { (x, y) -> x == y || (x.startsWith("{") && y.startsWith("{")) }

    private fun parsePattern(path: String): List<String> {
        require(path.startsWith("/")) { "route path '$path' must start with /" }
        val segments = path.split('/').drop(1).let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        require(segments.none { it.isEmpty() }) { "route path '$path' has an empty segment" }
        for (segment in segments) {
            require(SEGMENT.matches(segment) || PARAM.matches(segment)) { "route path '$path': segment '$segment' must be letters, digits, . _ ~ - or a {name}" }
        }
        val names = segments.filter { it.startsWith("{") }
        require(names.distinct().size == names.size) { "route path '$path' names a {parameter} twice" }
        return segments
    }

    private companion object {
        val SEGMENT = Regex("[A-Za-z0-9._~-]+")
        val PARAM = Regex("\\{[A-Za-z][A-Za-z0-9_]*}")
    }
}

/**
 * The core's [PluginRouteService]: finds the route, runs the plugin's handler and turns whatever it throws into a 500 that names no
 * internals. Permission checks for the plugin's [PluginRequest.hasPermission] go through the [AuthService] in use at the time of the request.
 */
internal class DefaultPluginRouteService(private val table: PluginRouteTable, private val services: ServiceRegistry) : PluginRouteService {
    override fun handle(principal: Principal, plugin: String, method: String, segments: List<String>, query: Map<String, List<String>>, body: String): PluginResponse {
        val (route, params) = table.find(plugin, method, segments) ?: throw ApiError(404, ErrorCode.NOT_FOUND, "No such endpoint.")
        val request = PluginRequest(
            method, "/" + segments.joinToString("/"), params, query, body, principal.userId, principal.username,
        ) { node -> services.require<AuthService>().hasPermission(principal, node) }
        try {
            return route.handler(request)
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            route.logger.error("${route.describe()} failed: ${e.javaClass.simpleName}: ${e.message}", e)
            throw ApiError(500, ErrorCode.INTERNAL, "The plugin $plugin failed to handle the request.")
        }
    }
}
