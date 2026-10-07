package xyz.felismp.shoparchive.app.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.shared.CloseSessionRequest
import xyz.felismp.shoparchive.shared.CommandRequest
import xyz.felismp.shoparchive.shared.CommandResponse
import xyz.felismp.shoparchive.shared.CompleteRequest
import xyz.felismp.shoparchive.shared.CompleteResponse
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.SessionPreview
import xyz.felismp.shoparchive.shared.MoveEntryRequest
import xyz.felismp.shoparchive.shared.UpdateEntryRequest
import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.DashboardDto
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.OpenSessionRequest
import xyz.felismp.shoparchive.shared.OpenSessionResponse
import xyz.felismp.shoparchive.shared.SessionDto
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorResponse
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.PROTOCOL_HEADER
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import xyz.felismp.shoparchive.shared.UpdateFile
import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@PublishedApi
internal val clientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Talks to one server. [pin] is the certificate pin (see [pinnedHttpClient]), [endpoints] are `host:port`.
 * The access token lives only in this object's memory. On a connection failure (never on an HTTP error) the
 * next endpoint is tried and the one that worked is kept first, so the token survives a change of address.
 */
class ApiClient(
    val serverId: String,
    pin: String,
    endpoints: List<String>,
    connectTimeoutMs: Long = 5_000,
) : ServerApi {
    private val http: HttpClient = pinnedHttpClient(pin, connectTimeoutMs)

    @Volatile
    override var endpoints: List<String> = orderEndpoints(endpoints)
        private set

    @Volatile
    private var token: String? = null

    override val isUnlocked: Boolean get() = token != null

    /** Forgets the access token (lock). */
    override fun lock() {
        token = null
    }

    override fun close() = http.close()

    // ---- calls ----

    /** `GET /api/v1/info`: needs no token and no matching protocol, so it tells a first-time client which server it reached. */
    override suspend fun info(): InfoResponse = decode(send(HttpMethod.Get, "/api/v1/info", null, Auth.None))

    /** The name and PIN in the body are the credential: no token is sent, so a 401 is a refusal ([ClientError.Api]), never [ClientError.Locked]. */
    override suspend fun login(request: LoginRequest): LoginResponse = post("/api/v1/login", request, auth = Auth.None)

    /** Keeps the access token in memory. */
    override suspend fun unlock(request: UnlockRequest): UnlockResponse =
        post<UnlockRequest, UnlockResponse>("/api/v1/unlock", request, auth = Auth.None).also { token = it.accessToken }

    override suspend fun reauth(request: ReauthRequest) {
        post<ReauthRequest, Unit>(REAUTH_PATH, request, auth = Auth.Token)
    }

    /** Also adopts the endpoints the server lists now; the one in use stays first, listed or not. */
    override suspend fun config(): ConfigResponse = get<ConfigResponse>("/api/v1/config").also { cfg ->
        if (cfg.endpoints.isNotEmpty()) {
            val active = endpoints.first()
            val fresh = orderEndpoints(cfg.endpoints)
            // The address that just answered stays first even when the server does not list it (e.g. found by mDNS while the server still names its old IP).
            endpoints = (listOf(active) + fresh).distinct().take(MAX_STORED_ENDPOINTS)
        }
    }

    override suspend fun devices(): List<DeviceInfo> = get("/api/v1/devices")

    override suspend fun revokeDevice(id: String) {
        decode<Unit>(send(HttpMethod.Delete, "/api/v1/devices/${id.encodeURLPath()}", null, Auth.Token))
    }

    override suspend fun updates(): List<UpdateFile> = get("/api/v1/updates")

    /** Streams the file into [target] (through `<target>.part`, then moved into place), reporting the bytes so far; the file is never held in memory. */
    override suspend fun downloadUpdate(file: String, target: Path, onProgress: (Long) -> Unit) {
        val bearer = token ?: throw ClientError.Locked()
        val path = "/api/v1/updates/${file.encodeURLPath()}"
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        try {
            viaEndpoints { endpoint ->
                http.prepareGet("https://$endpoint$path") {
                    header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                }.execute { response ->
                    if (!response.status.isSuccess()) raise(response.status.value, response.errorText(), response.headers[HttpHeaders.RetryAfter], bearer, path)
                    val channel = response.bodyAsChannel()
                    Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = channel.readAvailable(buffer, 0, buffer.size)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            total += n
                            onProgress(total)
                        }
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    // ---- records ----

    override suspend fun currentSession(branch: String): SessionDto = get("/api/v1/sessions/${branch.encodeURLPath()}/current")

    override suspend fun sessionsOn(branch: String, date: String): List<SessionDto> =
        get("/api/v1/sessions/${branch.encodeURLPath()}?from=$date&to=$date")

    override suspend fun openSession(branch: String, request: OpenSessionRequest): OpenSessionResponse =
        post("/api/v1/sessions/${branch.encodeURLPath()}/open", request)

    override suspend fun dashboard(from: String, to: String, branch: String): DashboardDto =
        get("/api/v1/dashboard?from=$from&to=$to&branch=${branch.encodeURLPath()}")

    override suspend fun recentItems(category: String?): List<String> =
        get("/api/v1/items/recent" + (category?.let { "?category=${it.encodeURLPath()}" } ?: ""))

    override suspend fun createEntry(request: CreateEntryRequest, slips: List<ByteArray>): CreatedEntry {
        val reply = exchange(HttpMethod.Post, "/api/v1/entries", Auth.Token) { multipart(clientJson.encodeToString(request), slips) }
        return CreatedEntry(clientJson.decodeFromString<EntryDto>(reply.text), created = reply.status == 201)
    }

    /** Multipart: the part `entry` is the JSON, `slip-N` are the images; the same body is rebuilt for each endpoint tried. */
    private fun multipart(json: String, slips: List<ByteArray>) = MultiPartFormDataContent(formData {
        append("entry", json, Headers.build { append(HttpHeaders.ContentType, "application/json") })
        slips.forEachIndexed { i, bytes ->
            append("slip-${i + 1}", bytes, Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"slip-${i + 1}.jpg\"")
            })
        }
    })

    private fun entryPath(date: String, id: String) = "/api/v1/entries/${date.encodeURLPath()}/${id.encodeURLPath()}"

    override suspend fun entries(filter: EntryFilter): List<EntryDto> = get("/api/v1/entries?" + filter.query())

    override suspend fun entry(date: String, id: String): EntryDto = get(entryPath(date, id))

    override suspend fun updateEntry(date: String, id: String, request: UpdateEntryRequest, slips: List<ByteArray>): EntryDto =
        clientJson.decodeFromString(exchange(HttpMethod.Put, entryPath(date, id), Auth.Token) { multipart(clientJson.encodeToString(request), slips) }.text)

    override suspend fun deleteEntry(date: String, id: String): EntryDto = decode(send(HttpMethod.Delete, entryPath(date, id), null, Auth.Token))

    override suspend fun moveEntry(date: String, id: String, to: String): EntryDto = post(entryPath(date, id) + "/move", MoveEntryRequest(to))

    override suspend fun slip(date: String, id: String, n: Int): ByteArray =
        exchange(HttpMethod.Get, entryPath(date, id) + "/slips/$n", Auth.Token) { null }.bytes

    override suspend fun history(date: String, id: String): List<HistoryItem> = get(entryPath(date, id) + "/history")

    override suspend fun closeSession(branch: String, id: String, request: CloseSessionRequest): SessionDto =
        post("/api/v1/sessions/${branch.encodeURLPath()}/${id.encodeURLPath()}/close", request)

    override suspend fun sessionPreview(branch: String, id: String): SessionPreview =
        get("/api/v1/sessions/${branch.encodeURLPath()}/${id.encodeURLPath()}/preview")

    override suspend fun notifications(limit: Int): List<NotificationDto> = get("/api/v1/notifications?limit=$limit")

    override suspend fun resendNotification(id: String): NotificationDto =
        decode(send(HttpMethod.Post, "/api/v1/notifications/${id.encodeURLPath()}/resend", null, Auth.Token))

    override suspend fun notifyDay(branch: String, sessionId: String): NotificationDto =
        decode(send(HttpMethod.Post, "/api/v1/sessions/${branch.encodeURLPath()}/${sessionId.encodeURLPath()}/notify", null, Auth.Token))

    override suspend fun export(from: String, to: String, branch: String, format: String): ExportFile {
        val query = EntryFilter(from, to, branch).query() + "&format=" + format
        val reply = exchange(HttpMethod.Get, "/api/v1/export?$query", Auth.Token) { null }
        val name = reply.disposition?.let { ContentDisposition.parse(it).parameter(ContentDisposition.Parameters.FileName) }
        return ExportFile(name ?: "shoparchive-$from-$to.$format", reply.bytes)
    }

    override suspend fun runCommand(line: String): List<String> = post<CommandRequest, CommandResponse>("/api/v1/command", CommandRequest(line)).lines

    override suspend fun completeCommand(line: String): List<String> =
        post<CompleteRequest, CompleteResponse>("/api/v1/command/complete", CompleteRequest(line)).candidates

    /** Authorized GET, decoded as [R]. */
    suspend inline fun <reified R> get(path: String): R = decode(send(HttpMethod.Get, path, null, Auth.Token))

    /** Authorized POST of [body], decoded as [R] (use [Unit] for an empty answer). */
    suspend inline fun <reified B, reified R> post(path: String, body: B): R = post(path, body, Auth.Token)

    @PublishedApi
    internal enum class Auth { None, Token }

    @PublishedApi
    internal suspend inline fun <reified B, reified R> post(path: String, body: B, auth: Auth): R =
        decode(send(HttpMethod.Post, path, clientJson.encodeToString(body), auth))

    @PublishedApi
    internal inline fun <reified R> decode(text: String): R =
        if (R::class == Unit::class) Unit as R else clientJson.decodeFromString(text)

    @PublishedApi
    internal suspend fun send(method: HttpMethod, path: String, body: String?, auth: Auth): String =
        exchange(method, path, auth) { body?.let { TextContent(it, ContentType.Application.Json) } }.text

    private suspend fun exchange(method: HttpMethod, path: String, auth: Auth, content: () -> OutgoingContent?): Reply =
        exchange(method, path, if (auth == Auth.Token) token ?: throw ClientError.Locked() else null, content)

    /** Sends one request and returns the status and body of a success; [content] is built again for each endpoint tried. */
    private suspend fun exchange(method: HttpMethod, path: String, bearer: String?, content: () -> OutgoingContent?): Reply {
        val response = viaEndpoints { endpoint ->
            http.request("https://$endpoint$path") {
                this.method = method
                header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
                bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                content()?.let { setBody(it) }
            }
        }
        val bytes = response.readRawBytes()
        if (response.status.isSuccess()) return Reply(response.status.value, bytes, response.headers[HttpHeaders.ContentDisposition])
        raise(response.status.value, bytes.decodeToString(), response.headers[HttpHeaders.RetryAfter], bearer, path)
    }

    /** The start of an error body, at most [MAX_ERROR_BYTES]; the rest is never read, so a server cannot make a failed download fill memory. */
    private suspend fun HttpResponse.errorText(): String {
        val channel = bodyAsChannel()
        val buffer = ByteArray(MAX_ERROR_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = channel.readAvailable(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return buffer.decodeToString(0, read)
    }

    /** Turns an error answer into the [ClientError] it stands for; a 401 on a call with a token forgets the token. */
    private fun raise(status: Int, text: String, retryAfter: String?, bearer: String?, path: String): Nothing {
        val error = try {
            clientJson.decodeFromString<ErrorResponse>(text)
        } catch (_: SerializationException) {
            null
        }
        if (error?.code == ErrorCode.PROTOCOL_MISMATCH) throw ClientError.ProtocolMismatch(error.protocol, error.message)
        // A 401 means the token is gone, except where it says something else: a wrong PIN on /reauth, or "enter the PIN again".
        if (status == 401 && bearer != null && path != REAUTH_PATH && error?.code != ErrorCode.REAUTH_REQUIRED) {
            token = null
            throw ClientError.Locked()
        }
        throw ClientError.Api(
            status, error?.code ?: ErrorCode.INTERNAL, error?.message ?: "HTTP $status",
            error?.passwordRequired ?: false, retryAfter?.toIntOrNull(), error?.reason,
        )
    }

    /** A success answer: the raw body ([text] reads it as UTF-8) and the `Content-Disposition` header if there was one. */
    private class Reply(val status: Int, val bytes: ByteArray, val disposition: String?) {
        val text: String get() = bytes.decodeToString()
    }

    private companion object {
        const val REAUTH_PATH = "/api/v1/reauth"
        const val MAX_ERROR_BYTES = 64 * 1024
    }

    // ---- WebSocket ----

    /**
     * Opens `/api/v1/ws` with the token and runs until it closes: [onOpen] when the upgrade succeeded, [onText] per
     * text frame. Returns normally when an open socket ends; throws if it could not be opened.
     */
    override suspend fun webSocketSession(onOpen: () -> Unit, onText: (String) -> Unit) {
        val bearer = token ?: throw ClientError.Locked()
        viaEndpoints { endpoint ->
            var opened = false
            try {
                http.webSocket("wss://$endpoint/api/v1/ws", request = {
                    header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
                    header(HttpHeaders.Authorization, "Bearer $bearer")
                }) {
                    opened = true
                    onOpen()
                    for (frame in incoming) if (frame is Frame.Text) onText(frame.readText())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (opened) return@viaEndpoints // the socket dropped; the caller reconnects
                if ("401" in e.message.orEmpty()) {
                    token = null
                    throw ClientError.Locked()
                }
                throw e
            }
        }
    }

    // ---- endpoint fallback ----

    private suspend fun <T> viaEndpoints(block: suspend (String) -> T): T {
        var last: Exception? = null
        for (endpoint in endpoints) {
            try {
                return block(endpoint).also { promote(endpoint) }
            } catch (e: Exception) {
                when {
                    e is CancellationException || e is ClientError -> throw e
                    isPinFailure(e) -> throw ClientError.PinMismatch(e, endpoint)
                    e is IOException -> last = e
                    else -> throw e
                }
            }
        }
        throw ClientError.Unreachable(last)
    }

    @Synchronized
    override fun useEndpoint(endpoint: String) = promote(endpoint)

    @Synchronized
    private fun promote(endpoint: String) {
        if (endpoints.first() != endpoint) endpoints = listOf(endpoint) + (endpoints - endpoint)
    }
}
