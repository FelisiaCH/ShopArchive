package xyz.felismp.shoparchive.server.net

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.ClientConfigService
import xyz.felismp.shoparchive.api.DeviceService
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.InfoService
import xyz.felismp.shoparchive.api.IpBanService
import xyz.felismp.shoparchive.api.PairingService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.plugin.PluginRouteService
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.auth.DEVICES_REVOKE_NODE
import xyz.felismp.shoparchive.server.notify.notifyRoutes
import xyz.felismp.shoparchive.shared.CreatePairingRequest
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.SetModeRequest
import xyz.felismp.shoparchive.shared.UnlockRequest

/** Thrown by the request gate and by routes; [StatusPages] turns it into the shared error envelope. */
internal class ApiException(val status: HttpStatusCode, val code: ErrorCode, message: String, val reason: String? = null) : Exception(message)

private const val INFO_PATH = "/api/v1/info"
private const val ENTRIES_PATH = "/api/v1/entries"

/** The service of type [T] with the highest priority, looked up per request so a plugin registered later or higher wins. */
internal inline fun <reified T : Any> ServiceRegistry.require(): T =
    get(T::class.java) ?: throw ApiException(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL, "${T::class.java.simpleName} is not registered")

/**
 * Runs before routing on every request: a banned address is refused, and a request that does not name this
 * server's protocol version is refused with the version it should speak - except `GET /api/v1/info`, which is
 * how a client finds out the version.
 */
private fun gate(services: ServiceRegistry) = createApplicationPlugin("ShopArchiveGate") {
    onCall { call ->
        if (services.require<IpBanService>().isBanned(call.request.local.remoteAddress)) {
            throw ApiException(HttpStatusCode.Forbidden, ErrorCode.BANNED, "This address is banned from the server.")
        }
        val isInfo = call.request.httpMethod == HttpMethod.Get && call.request.path() == INFO_PATH
        if (!isInfo && call.request.header(PROTOCOL_HEADER)?.trim()?.toIntOrNull() != PROTOCOL_VERSION) {
            throw ApiException(
                HttpStatusCode.BadRequest, ErrorCode.PROTOCOL_MISMATCH,
                "This server speaks protocol $PROTOCOL_VERSION; send $PROTOCOL_HEADER: $PROTOCOL_VERSION. Update the app or the server so both match.",
            )
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: ErrorCode, message: String, passwordRequired: Boolean = false, reason: String? = null) =
    respond(status, ErrorResponse(code, message, PROTOCOL_VERSION, passwordRequired, reason))

internal val PrincipalKey = AttributeKey<Principal>("ShopArchivePrincipal")

private fun unauthorized(message: String = "Unlock the app first.") = ApiException(HttpStatusCode.Unauthorized, ErrorCode.UNAUTHORIZED, message)

/** The token in `Authorization: Bearer <token>`, or null. Which kind of token it is, is for the service to say. */
private fun ApplicationCall.bearerToken(): String? {
    val parts = request.header(HttpHeaders.Authorization)?.trim()?.split(' ', limit = 2) ?: return null
    return parts.getOrNull(1)?.trim()?.takeIf { parts[0].equals("Bearer", ignoreCase = true) && it.isNotEmpty() }
}

internal val ApplicationCall.ip: String get() = request.local.remoteAddress

/** 403 unless the caller holds [node] right now (an op holds every node). Routes call this; nothing else decides who may do what. */
internal fun ServiceRegistry.requirePermission(principal: Principal, node: String) {
    if (!require<AuthService>().hasPermission(principal, node)) {
        throw ApiException(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "This needs the permission $node.", ErrorReasons.PERMISSION_MISSING)
    }
}

/** 403 unless the caller works in [branch]: it is on their account, or they hold `shoparchive.branch.all`. For the routes of P06 on. */
internal fun ServiceRegistry.requireBranch(principal: Principal, branch: String) {
    if (!require<AuthService>().canAccessBranch(principal, branch)) {
        throw ApiException(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN, "This account does not work in that branch.", ErrorReasons.BRANCH_NOT_ALLOWED)
    }
}

/** Services hash passwords and write files; that must not hold up the threads that read requests. */
internal suspend fun <T> blocking(work: () -> T): T = withContext(Dispatchers.IO) { work() }

/** A route level that takes no part in matching the path; it only gives [signedIn] a place to hang the check. */
private class SignedInSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = "(signed in)"
}

/** A valid access token, else 401. An enrollment token is not one. */
private fun signedInCheck(services: ServiceRegistry) = createRouteScopedPlugin("ShopArchiveSignedIn") {
    onCall { call ->
        val principal = call.bearerToken()?.let { services.require<AuthService>().authenticate(it) } ?: throw unauthorized()
        call.attributes.put(PrincipalKey, principal)
    }
}

/** Every route made in [build] needs a signed-in user. (`route("")` would not do: an empty path is the same route, so the check would cover its siblings too.) */
private fun Route.signedIn(services: ServiceRegistry, build: Route.() -> Unit) {
    val child = createChild(SignedInSelector())
    child.install(signedInCheck(services))
    child.build()
}

/**
 * `/api/v1/x/<plugin>/...` for the methods a plugin can register. All it does is hand the request to the [PluginRouteService]
 * (on a worker thread: the plugin's code may block); which plugin has which route is for the service to say.
 */
private fun Route.pluginRoutes(services: ServiceRegistry) {
    val serve: suspend ApplicationCall.() -> Unit = {
        val principal = attributes[PrincipalKey]
        val segments = parameters.getAll("rest").orEmpty().filter { it.isNotEmpty() } // a trailing slash is not a segment
        val query = request.queryParameters.entries().associate { (name, values) -> name to values }
        val body = if (request.httpMethod == HttpMethod.Get) "" else receiveText()
        val method = request.httpMethod.value
        val plugin = parameters["plugin"].orEmpty()
        val answer = blocking { services.require<PluginRouteService>().handle(principal, plugin, method, segments, query, body) }
        respondText(answer.body, ContentType.parse(answer.contentType), HttpStatusCode.fromValue(answer.status))
    }
    // The plugin's root (`/x/Name`) is not matched by the tail-card route, so both are registered.
    for (path in listOf("/x/{plugin}", "/x/{plugin}/{rest...}")) {
        get(path) { call.serve() }
        post(path) { call.serve() }
        put(path) { call.serve() }
        delete(path) { call.serve() }
    }
}

private const val WS_OUTBOUND_QUEUE = 256

/** The limit per address for the endpoints anyone can call: they have no token, so the address is all there is to count. */
private val PUBLIC_LIMIT = RateLimitName("public")

/** The HTTP API. Routes only hand over to services, so a plugin can replace what they do. */
internal fun Application.apiModule(
    services: ServiceRegistry,
    maxBodyBytes: Long,
    requestTimeoutSeconds: Int = 30,
    rateLimitPerMinute: Int = 10,
    /** The largest request that carries slips (`/api/v1/entries`); the config says how many and how big they may be. */
    entryBodyBytes: () -> Long = { maxBodyBytes },
) {
    // A client newer than the server may send fields this server does not know yet; those are skipped, not refused.
    install(ContentNegotiation) { json(Json { encodeDefaults = true; ignoreUnknownKeys = true }) }
    install(WebSockets) {
        pingPeriodMillis = wsPingSeconds(requestTimeoutSeconds) * 1000L
        timeoutMillis = requestTimeoutSeconds * 1000L
        maxFrameSize = 64 * 1024
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondError(e.status, e.code, e.message ?: e.code.name, reason = e.reason) }
        exception<ApiError> { call, e ->
            e.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respondError(HttpStatusCode.fromValue(e.status), e.code, e.message ?: e.code.name, e.passwordRequired, e.reason)
        }
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, ErrorCode.INVALID_REQUEST, "The request body is missing or is not the JSON this endpoint takes.")
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondError(HttpStatusCode.PayloadTooLarge, ErrorCode.PAYLOAD_TOO_LARGE, "The request body is larger than this server accepts.")
        }
        exception<Throwable> { call, e ->
            Log.error("Request ${call.request.httpMethod.value} ${call.request.path()} failed", e)
            call.respondError(HttpStatusCode.InternalServerError, ErrorCode.INTERNAL, "The server failed to handle the request.")
        }
        status(HttpStatusCode.NotFound) { call, _ -> call.respondError(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such endpoint.") }
        // The rate limiter answers with the bare status and its Retry-After header; this gives it the shared envelope.
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respondError(HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED, "Too many requests from this address. Wait and try again.")
        }
    }
    install(RateLimit) {
        register(PUBLIC_LIMIT) {
            rateLimiter(limit = rateLimitPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteAddress }
        }
    }
    install(RequestBodyLimit) { bodyLimit { call -> if (call.request.path().startsWith(ENTRIES_PATH)) entryBodyBytes() else maxBodyBytes } }
    install(gate(services))

    routing {
        route("/api/v1") {
            get("/info") { call.respond(services.require<InfoService>().info()) }
            rateLimit(PUBLIC_LIMIT) {
                post("/pair/redeem") {
                    val body = call.receive<RedeemRequest>()
                    call.respond(blocking { services.require<PairingService>().redeem(body, call.ip) })
                }
                // The enrollment token is the credential here; the service checks it, and it works nowhere else.
                post("/enroll") {
                    val token = call.bearerToken() ?: throw unauthorized("Pair first.")
                    val body = call.receive<EnrollRequest>()
                    call.respond(blocking { services.require<AuthService>().enroll(token, body, call.ip) })
                }
                // The device credential in the body is the credential here.
                post("/unlock") {
                    val body = call.receive<UnlockRequest>()
                    call.respond(blocking { services.require<AuthService>().unlock(body, call.ip) })
                }
            }

            signedIn(services) { protectedRoutes(services) }
        }
    }
}

private fun Route.protectedRoutes(services: ServiceRegistry) {
    post("/reauth") {
        val body = call.receive<ReauthRequest>()
        val principal = call.attributes[PrincipalKey]
        blocking { services.require<AuthService>().reauth(principal, body, call.ip) }
        call.respond(HttpStatusCode.NoContent)
    }

    get("/config") {
        val principal = call.attributes[PrincipalKey]
        call.respond(blocking { services.require<ClientConfigService>().clientConfig(principal) })
    }

    get("/devices") {
        val principal = call.attributes[PrincipalKey]
        call.respond(blocking { services.require<DeviceService>().list(principal) })
    }

    // ?user=<account id>: whose entry goes. Without it, the caller's own - which needs no permission.
    delete("/devices/{id}") {
        val principal = call.attributes[PrincipalKey]
        val userId = call.request.queryParameters["user"] ?: principal.userId
        if (userId != principal.userId) services.requirePermission(principal, DEVICES_REVOKE_NODE)
        blocking { services.require<DeviceService>().revoke(principal, call.parameters["id"].orEmpty(), userId, call.ip) }
        call.respond(HttpStatusCode.NoContent)
    }

    put("/devices/{id}/mode") {
        val body = call.receive<SetModeRequest>()
        val principal = call.attributes[PrincipalKey]
        blocking { services.require<DeviceService>().setMode(principal, call.parameters["id"].orEmpty(), body.mode, call.ip) }
        call.respond(HttpStatusCode.NoContent)
    }

    post("/pairings") {
        val body = call.receive<CreatePairingRequest>()
        val principal = call.attributes[PrincipalKey]
        call.respond(HttpStatusCode.Created, blocking { services.require<PairingService>().create(principal, body, call.ip) })
    }

    recordRoutes(services)
    commandRoutes(services)
    notifyRoutes(services)
    updateRoutes(services)
    pluginRoutes(services)

    webSocket("/ws") {
        val principal = call.attributes[PrincipalKey]
        val auth = services.require<AuthService>()
        val outbound = Channel<String>(WS_OUTBOUND_QUEUE)
        val registration = services.require<EventService>().register(principal) { text ->
            outbound.trySend(text).isSuccess.also { accepted -> if (!accepted) outbound.close() }
        }
        val sender = launch {
            for (text in outbound) send(Frame.Text(text))
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many messages waiting"))
        }
        // The token runs out, or the user is disabled or taken off the device: the socket must not outlive that.
        val watcher = launch {
            while (auth.isValid(principal)) delay(1_000)
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "The session has ended"))
        }
        try {
            // The app sends nothing the server acts on; reading keeps pongs and the close frame flowing.
            for (frame in incoming) Unit
        } finally {
            registration.close()
            outbound.close()
            sender.cancel()
            watcher.cancel()
        }
    }
}
