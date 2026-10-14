package xyz.felismp.shoparchive.app.client

import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.LoginResponse
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import xyz.felismp.shoparchive.shared.UpdateFile

/** The calls the screens make; [ApiClient] is the real one, tests use a fake. */
interface ServerApi : RecordsApi, UpdatesApi {
    val isUnlocked: Boolean
    /** The `host:port` addresses in order of trying; the one that worked last is first. */
    val endpoints: List<String>
    /** Tries [endpoint] before the others from now on (an address found by discovery, once its server is confirmed). */
    fun useEndpoint(endpoint: String)
    suspend fun info(): InfoResponse
    /** Puts a user on this device by name and PIN; answers the device id and the user's device credential. */
    suspend fun login(request: LoginRequest): LoginResponse
    suspend fun unlock(request: UnlockRequest): UnlockResponse
    suspend fun config(): ConfigResponse
    /** Enters the PIN or password again for an action that asked for it. */
    suspend fun reauth(request: ReauthRequest)
    /** The signed-in user's own devices. */
    suspend fun devices(): List<DeviceInfo>
    /** Takes the signed-in user off device [id]; needs a recent [reauth]. */
    suspend fun revokeDevice(id: String)
    /** Forgets the access token. */
    fun lock()
    suspend fun webSocketSession(onOpen: () -> Unit, onText: (String) -> Unit)
    fun close()
}

/** The installers the server offers for the app (`downloads/` on the server). */
interface UpdatesApi {
    /** The installers of every platform, newest first. */
    suspend fun updates(): List<UpdateFile>

    /**
     * Fetches [file] (a name from [updates]) into the sink that [open] makes once the server has answered with a success (again for each address
     * tried), and closes it; [onProgress] gets the bytes received so far. Where a file lands is up to the platform (see `downloadUpdate` to a path).
     */
    suspend fun downloadUpdate(file: String, open: () -> UpdateSink, onProgress: (Long) -> Unit)
}

/** Where the bytes of a download are put; closed when the download ends or fails. */
interface UpdateSink : AutoCloseable {
    fun write(bytes: ByteArray, count: Int)
}
