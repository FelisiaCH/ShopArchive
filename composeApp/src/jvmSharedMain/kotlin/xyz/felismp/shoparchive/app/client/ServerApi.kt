package xyz.felismp.shoparchive.app.client

import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.RedeemResponse
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import xyz.felismp.shoparchive.shared.UpdateFile
import java.nio.file.Path

/** The calls the screens make; [ApiClient] is the real one, tests use a fake. */
interface ServerApi : RecordsApi, UpdatesApi {
    val isUnlocked: Boolean
    /** The `host:port` addresses in order of trying; the one that worked last is first. */
    val endpoints: List<String>
    /** Tries [endpoint] before the others from now on (an address found by discovery, once its server is confirmed). */
    fun useEndpoint(endpoint: String)
    suspend fun info(): InfoResponse
    suspend fun redeem(request: RedeemRequest): RedeemResponse
    suspend fun enroll(enrollmentToken: String, request: EnrollRequest): EnrollResponse
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

    /** Fetches [file] (a name from [updates]) into [target]; [onProgress] gets the bytes received so far. A failed download leaves nothing at [target]. */
    suspend fun downloadUpdate(file: String, target: Path, onProgress: (Long) -> Unit)
}
