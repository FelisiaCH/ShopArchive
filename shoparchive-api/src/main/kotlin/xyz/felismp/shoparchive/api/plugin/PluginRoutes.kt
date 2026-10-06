package xyz.felismp.shoparchive.api.plugin

import xyz.felismp.shoparchive.api.Principal

/**
 * What a plugin gets to serve its own HTTP endpoints, below `/api/v1/x/<plugin name>/`. Every request is
 * from a signed-in user (the server checks the token before it asks the plugin) and has the usual protocol header.
 * Ktor and the rest of the server's web layer stay out of the api on purpose: a plugin sees a request and answers with a [PluginResponse].
 *
 * Routes can be registered only in [ShopPlugin.onEnable] (that is when the core is complete and the network not yet started);
 * anywhere else this throws. They go away when the plugin fails or is disabled. A [path] starts with `/`; a segment
 * written `{name}` matches one segment of the request and is given in [PluginRequest.pathParams]; the other segments must match exactly.
 * Registering the same method and path twice throws; of two different paths that both match a request, the one registered first is used.
 * A handler runs off the server's network threads and may block, but a slow one holds a worker: keep it short.
 * What it throws becomes a 500 for the caller and a line in the log naming the plugin; the server keeps running.
 */
interface PluginRoutes {
    fun get(path: String, handler: (PluginRequest) -> PluginResponse)
    fun post(path: String, handler: (PluginRequest) -> PluginResponse)
    fun put(path: String, handler: (PluginRequest) -> PluginResponse)
    fun delete(path: String, handler: (PluginRequest) -> PluginResponse)
}

/**
 * One request to a plugin route. [path] is the part after `/api/v1/x/<plugin>` (starting with `/`, `/` alone for the plugin's root),
 * [pathParams] the `{name}` segments of the registered path, [query] the query string by name (a name may repeat), [body] the
 * request body as text (empty for GET). [userId] is the account and never changes; [userName] is for display only (see [Principal]).
 */
class PluginRequest(
    val method: String,
    val path: String,
    val pathParams: Map<String, String>,
    val query: Map<String, List<String>>,
    val body: String,
    val userId: String,
    val userName: String,
    private val permissionCheck: (String) -> Boolean,
) {
    /** Whether the caller holds the permission [node] right now (an op holds every node). The plugin decides what it needs; the server only knows who is signed in. */
    fun hasPermission(node: String): Boolean = permissionCheck(node)

    /** The first value of the query parameter [name], or null. */
    fun queryParam(name: String): String? = query[name]?.firstOrNull()
}

/** What a route answers: HTTP [status] (100 to 599), [body] text and its [contentType], JSON unless the plugin says otherwise. */
class PluginResponse(val status: Int, val body: String = "", val contentType: String = "application/json") {
    init {
        require(status in 100..599) { "HTTP status $status is not valid" }
        require(CONTENT_TYPE.matches(contentType)) { "content type '$contentType' is not valid" }
    }

    companion object {
        private val CONTENT_TYPE = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(\\s*;\\s*[A-Za-z0-9_.-]+=[^;\\r\\n]+)*")

        /** 200 with a JSON [body]. */
        fun ok(body: String = "{}") = PluginResponse(200, body)
    }
}

/**
 * Serves the requests below `/api/v1/x/`. The HTTP route only forwards to it, so a plugin can replace what happens
 * (for example to add rate limiting or auditing). The core's implementation finds the routes plugins registered through [PluginRoutes].
 */
interface PluginRouteService {
    /**
     * Answers one request from [principal] to [plugin]'s route for [method] and [segments] (the decoded path segments below the plugin's name).
     * @throws xyz.felismp.shoparchive.api.ApiError 404 if there is no such plugin or route, 500 if the plugin's handler failed
     */
    fun handle(principal: Principal, plugin: String, method: String, segments: List<String>, query: Map<String, List<String>>, body: String): PluginResponse
}
