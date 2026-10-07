package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.app.client.ServerDiscovery
import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.client.CredentialStore
import xyz.felismp.shoparchive.app.client.ServerApi
import xyz.felismp.shoparchive.app.client.Preferences
import xyz.felismp.shoparchive.app.client.PreferencesStore
import xyz.felismp.shoparchive.app.client.FoundServer
import xyz.felismp.shoparchive.app.client.StoredServer
import xyz.felismp.shoparchive.app.client.StoredServers
import xyz.felismp.shoparchive.app.client.StoredUser
import xyz.felismp.shoparchive.shared.AuthPolicy
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollRequest
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.InfoResponse
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.PairPayload
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.RedeemResponse
import xyz.felismp.shoparchive.shared.UnlockRequest
import xyz.felismp.shoparchive.shared.UnlockResponse
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PIN_HEX = "AB".repeat(32)
private const val WRONG_PIN = "000000"
private val PIN_FP = PIN_HEX.chunked(4).joinToString(" ")
/** The key a reinstalled (or impersonated) server shows. */
private val OTHER_HEX = "CD".repeat(32)
private val OTHER_FP = OTHER_HEX.chunked(4).joinToString(" ")

private class FakeStore(var all: StoredServers? = null) : CredentialStore {
    /** The first saved server, which is the only one in most tests; setting it makes it the only one. */
    var stored: StoredServer?
        get() = all?.servers?.firstOrNull()
        set(value) { all = value?.let { StoredServers(listOf(it)) } }
    override fun load() = all
    override fun save(servers: StoredServers) { all = servers }
    override fun clear() { all = null }
}

private fun storedAlice(mode: DeviceMode = DeviceMode.PERSONAL) =
    StoredServer("sid-1", "Shop", PIN_HEX, listOf("10.0.0.5:8443"), "dev-1", listOf(StoredUser("alice", "cred")), mode)

/** Another server, named after its [id], with each of [users] holding the credential `cred-<name>`. */
private fun server(id: String, endpoint: String, vararg users: String) =
    StoredServer(id, "Shop $id", PIN_HEX, listOf(endpoint), "dev-$id", users.map { StoredUser(it, "cred-$it") })

private fun storedShared() = storedAlice(DeviceMode.SHARED).let { it.copy(users = it.users + StoredUser("bob", "cred-bob")) }

private class FakePrefs(var saved: Preferences = Preferences()) : PreferencesStore {
    override fun load() = saved
    override fun save(preferences: Preferences) { saved = preferences }
}

private class FakeDiscovery(var found: List<String> = emptyList()) : ServerDiscovery {
    var searches = 0
    /** What a browse of the whole network finds. */
    var announced: List<FoundServer> = emptyList()
    var browses = 0
    override suspend fun find(serverId: String, timeout: kotlin.time.Duration): List<String> { searches++; return found }
    override suspend fun browse(timeout: kotlin.time.Duration): List<FoundServer> { browses++; return announced }
}

private class FakeApi(val records: FakeRecordsApi = FakeRecordsApi()) : ServerApi, xyz.felismp.shoparchive.app.client.RecordsApi by records {
    var failConfig: Exception? = null
    /** The addresses `GET /config` lists. */
    var announced: List<String> = emptyList()
    /** The permission nodes `GET /config` says the signed-in user holds. */
    var permissions: List<String> = ALL_NODES
    var failReauth: Exception? = null
    var failDevices: Exception? = null
    var wsFails = false
    /** Makes the next socket find the session gone (the server restarted and forgot the token). */
    var wsLocked = false
    /** Like [wsLocked], for the next socket only; the token is forgotten as the real client does. */
    var wsLockedOnce = false
    /** Set to make the next open socket end when this completes (once). */
    var dropSocket: CompletableDeferred<Unit>? = null
    var sharedMinutes = 2
    var personalMinutes = 10
    /** What `GET /config` says about unlocking a one-user device with its credential alone. */
    var unlockWithoutPin = false
    var devicesList = listOf(
        DeviceInfo("dev-1", "Till 1", "windows", DeviceMode.PERSONAL, "2026-10-01", 1, current = true),
        DeviceInfo("dev-2", "Phone", "android", DeviceMode.PERSONAL, "2026-10-02", 1, current = false),
    )
    val reauths = mutableListOf<ReauthRequest>()
    val revoked = mutableListOf<String>()
    /** Errors the next revokes throw, in order; empty means success. */
    val revokeErrors = ArrayDeque<Exception>()
    var locks = 0
    /** Device credentials the fake server rejects when enrolling onto an existing device. */
    val badDeviceCredentials = mutableSetOf<String>()
    var revokeGate: CompletableDeferred<Unit>? = null
    var token_at_revoke = mutableListOf<Boolean>()

    var token = false
    var closed = false
    var failInfo: Exception? = null
    var infoServerId = "sid-1"
    /** When set, the server is only reachable at this address: calls throw Unreachable while another one is first. */
    var worksAt: String? = null
    private fun reach() { worksAt?.let { if (endpoints.firstOrNull() != it) throw ClientError.Unreachable() } }
    var failRedeem: Exception? = null
    var failEnroll: Exception? = null
    var failLogin: Exception? = null
    /** Users the fake server knows who have no PIN yet: a login without a new PIN is told `pin.not-set`. */
    val noPinYet = mutableSetOf<String>()
    val logins = mutableListOf<LoginRequest>()
    var failUnlock: Exception? = null
    var protocol = PROTOCOL_VERSION
    var redeemResponse = RedeemResponse("tok", "alice", passwordRequired = false, hasPassword = false, hasPin = false, pinLength = 6)
    val redeemed = mutableListOf<RedeemRequest>()
    val enrolled = mutableListOf<Pair<String, EnrollRequest>>()
    val unlocks = mutableListOf<UnlockRequest>()
    var onOpen: (() -> Unit)? = null

    override val isUnlocked get() = token
    override var endpoints: List<String> = emptyList()
    override fun useEndpoint(endpoint: String) { endpoints = listOf(endpoint) + (endpoints - endpoint) }
    override suspend fun info(): InfoResponse { reach(); failInfo?.let { throw it }; return InfoResponse(infoServerId, "Shop", "", "1", protocol) }
    override suspend fun redeem(request: RedeemRequest): RedeemResponse { redeemed += request; failRedeem?.let { throw it }; return redeemResponse }
    override suspend fun enroll(enrollmentToken: String, request: EnrollRequest): EnrollResponse {
        enrolled += enrollmentToken to request; failEnroll?.let { throw it }
        if (request.deviceCredential in badDeviceCredentials) throw ClientError.Api(401, ErrorCode.DEVICE_NOT_RECOGNIZED, "This device is not recognized for that user.")
        if (WRONG_PIN in listOf(request.pin, request.newPin)) throw ClientError.Api(401, ErrorCode.UNAUTHORIZED, "The password or PIN is wrong.")
        return EnrollResponse("dev-1", "cred-1")
    }
    override suspend fun login(request: LoginRequest): EnrollResponse {
        reach(); logins += request; failLogin?.let { throw it }
        if (request.deviceCredential in badDeviceCredentials) throw ClientError.Api(401, ErrorCode.DEVICE_NOT_RECOGNIZED, "This device is not recognized for that user.")
        if (request.username in noPinYet && request.newPin == null) throw ClientError.Api(401, ErrorCode.UNAUTHORIZED, "Set a PIN.", reason = ErrorReasons.PIN_NOT_SET)
        if (WRONG_PIN in listOf(request.pin, request.newPin)) throw ClientError.Api(401, ErrorCode.UNAUTHORIZED, "Login failed.")
        return EnrollResponse(request.deviceId ?: "dev-new", "cred-${request.username}")
    }
    override suspend fun unlock(request: UnlockRequest): UnlockResponse {
        reach(); unlocks += request; failUnlock?.let { throw it }; token = true; return UnlockResponse("access", 900)
    }
    override suspend fun config(): ConfigResponse {
        reach()
        failConfig?.let { throw it }
        return ConfigResponse(
            AuthPolicy(6, false, sharedMinutes, personalMinutes, false, 5, unlockWithoutPin), announced, emptyList(),
            records.configBranches.map { xyz.felismp.shoparchive.shared.BranchDto(it, it, false) },
            permissions = permissions,
        )
    }
    var reauthGate: CompletableDeferred<Unit>? = null
    override suspend fun reauth(request: ReauthRequest) { reauths += request; reauthGate?.await(); failReauth?.let { throw it } }
    var devicesGate: CompletableDeferred<Unit>? = null
    /** Errors the next device lists throw, in order; a [ClientError.Locked] forgets the token first, as the real client does on a 401. */
    val devicesErrors = ArrayDeque<Exception>()
    override suspend fun devices(): List<DeviceInfo> {
        val list = devicesList; devicesGate?.await(); failDevices?.let { throw it }
        devicesErrors.removeFirstOrNull()?.let { if (it is ClientError.Locked) token = false; throw it }
        return list
    }
    override suspend fun revokeDevice(id: String) { revokeGate?.await(); token_at_revoke += token; revokeErrors.removeFirstOrNull()?.let { throw it }; revoked += id }
    override fun lock() { locks++; token = false }
    var offered: List<xyz.felismp.shoparchive.shared.UpdateFile> = emptyList()
    var updateChecks = 0
    override suspend fun updates(): List<xyz.felismp.shoparchive.shared.UpdateFile> { updateChecks++; return offered }
    override suspend fun downloadUpdate(file: String, target: java.nio.file.Path, onProgress: (Long) -> Unit) = error("not used by the flow tests")
    override suspend fun webSocketSession(onOpen: () -> Unit, onText: (String) -> Unit) {
        if (wsFails) throw ClientError.Unreachable()
        if (wsLocked) throw ClientError.Locked()
        if (wsLockedOnce) { wsLockedOnce = false; token = false; throw ClientError.Locked() }
        reach()
        this.onOpen = onOpen; onOpen()
        while (dropSocket?.isCompleted != true) delay(20) // open until cancelled, or until a test drops it
        dropSocket = null
    }
    override fun close() { closed = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppFlowTest {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob(job))
    private val api = FakeApi()
    private val store = FakeStore()
    private var probed: String? = null
    private var probeResult: () -> String = { PIN_FP }
    private var connected = mutableListOf<Triple<String, String, List<String>>>()
    private val prefs = FakePrefs()
    private var clock = 0L
    private val discovery = FakeDiscovery()
    /** What a connection to exactly one candidate address reaches (discovery's probes); any other connect gets [api]. */
    private val probes = mutableMapOf<String, FakeApi>()

    @AfterTest fun stop() = job.cancel()

    private fun flow(updater: AppUpdater? = null, own: xyz.felismp.shoparchive.shared.AppVersion = xyz.felismp.shoparchive.shared.AppVersion(1, 0, 0, 1)) = AppFlow(
        scope, store, ThisDevice("Test PC", "windows"),
        connect = { pin, sid, eps -> connected += Triple(pin, sid, eps); probes[eps.singleOrNull()] ?: api.also { it.endpoints = eps } },
        probe = { probed = it; probeResult() },
        prefs = prefs, now = { clock },
        io = Dispatchers.Unconfined,
        discovery = discovery,
        updater = updater, ownVersion = own,
    )

    private fun link(secret: String = "secret", endpoints: List<String> = listOf("192.168.1.2:8443")): String {
        val json = Json.encodeToString(PairPayload(1, "sid-1", PIN_HEX, secret, "alice", endpoints))
        return "shoparchive://pair?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private fun AppFlow.enrolling(): AppState.Enroll {
        previewLink(link()); redeemLink()
        return assertIs(state.value)
    }

    private val good = EnrollInput(pin = "483926", pinRepeat = "483926", label = "Till 1")

    // ---- start ----

    /**
     * The empty pairing screen. Nothing on the server list leads there any more (a server opens its login), so it is reached the one way
     * left: leaving the preview of a pasted link. Nothing is probed or connected on the way.
     */
    private fun pairingFlow(): AppFlow = flow().apply {
        previewLink(link())
        cancelPreview()
        assertIs<AppState.Pair>(state.value)
    }

    @Test fun noStoredServerStartsAtTheEmptyServerListAndLooksOnTheNetwork() {
        discovery.announced = listOf(FoundServer("sid-9", "Market", "192.168.1.9:8443"))
        val s = assertIs<AppState.Servers>(flow().state.value)
        assertTrue(s.saved.isEmpty())
        assertEquals(listOf(FoundServer("sid-9", "Market", "192.168.1.9:8443")), s.found)
        assertFalse(s.searching)
        assertEquals(1, discovery.browses)
    }

    @Test fun twoStoredServersStartAtTheServerList() {
        store.all = StoredServers(listOf(server("sid-1", "10.0.0.5:8443", "alice"), server("sid-2", "10.0.0.6:8443", "bob", "carol")))
        val s = assertIs<AppState.Servers>(flow().state.value)
        assertEquals(listOf(ServerRow("sid-1", "Shop sid-1", "10.0.0.5:8443", 1), ServerRow("sid-2", "Shop sid-2", "10.0.0.6:8443", 2)), s.saved)
        assertTrue(connected.isEmpty(), "no server is opened before one is picked")
    }

    @Test fun storedCredentialsStartAtLocked() {
        store.stored = storedAlice()
        val locked = assertIs<AppState.Locked>(flow().state.value)
        assertEquals("alice", locked.username)
        assertEquals("10.0.0.5:8443", locked.server)
        assertEquals(PIN_HEX, connected.single().first)
    }

    @Test fun unreadableStoreStartsAtTheServerList() {
        val broken = object : CredentialStore {
            override fun load(): StoredServers? = error("damaged")
            override fun save(servers: StoredServers) = Unit
            override fun clear() = Unit
        }
        val f = AppFlow(scope, broken, ThisDevice("x", "windows"), { _, _, _ -> api }, { "" }, prefs, { 0L }, Dispatchers.Unconfined)
        assertTrue(assertIs<AppState.Servers>(f.state.value).saved.isEmpty())
    }

    @Test fun unlockingLooksForAnUpdateOnlyWhereThePlatformCanInstallOne() {
        store.stored = storedAlice()
        api.offered = listOf(xyz.felismp.shoparchive.shared.UpdateFile("ShopArchive-1.1.0.msi", xyz.felismp.shoparchive.shared.UpdatePlatform.WINDOWS, "1.1.0", 0, 1, "00"))
        val without = flow()
        without.unlock("483926")
        assertNull(without.workspace!!.updates, "no updater: nothing about updates exists")
        assertEquals(0, api.updateChecks)

        val updater = object : AppUpdater {
            override val platform = xyz.felismp.shoparchive.shared.UpdatePlatform.WINDOWS
            override val folder: java.nio.file.Path get() = error("not installing here")
            override suspend fun install(file: java.nio.file.Path) = error("not installing here")
        }
        val with = flow(updater)
        with.unlock("483926")
        assertEquals(1, api.updateChecks)
        assertEquals("ShopArchive-1.1.0.msi", assertIs<UpdateUi.Available>(with.workspace!!.updates!!.ui.value).file.file)
    }

    @Test fun lockedUnlocksWithPin() {
        store.stored = storedAlice()
        val f = flow()
        f.unlock("483926")
        assertIs<AppState.Unlocked>(f.state.value)
        assertEquals(UnlockRequest("dev-1", "alice", "cred", pin = "483926"), api.unlocks.single())
    }

    @Test fun aWrongTryBeforeThePasswordRequestLeavesNoStaleErrorOnThePasswordField() {
        store.stored = storedAlice()
        val f = flow()
        api.failUnlock = ClientError.Api(401, ErrorCode.UNAUTHORIZED, "wrong")
        f.unlock("111111")
        assertNotNull(assertIs<AppState.Locked>(f.state.value).problem)
        api.failUnlock = ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "password", passwordRequired = true)
        f.unlock("483926")
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertTrue(locked.needsPassword)
        assertNull(locked.problem)
    }

    @Test fun lockedAsksForPasswordWhenServerSaysSo() {
        store.stored = storedAlice()
        val f = flow()
        api.failUnlock = ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "password", passwordRequired = true)
        f.unlock("483926")
        assertTrue(assertIs<AppState.Locked>(f.state.value).needsPassword)
        api.failUnlock = null
        f.unlock("a long password")
        assertEquals("a long password", api.unlocks.last().password)
        assertNull(api.unlocks.last().pin)
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun wrongPinStaysLockedWithMessage() {
        store.stored = storedAlice()
        val f = flow()
        api.failUnlock = ClientError.Api(401, ErrorCode.UNAUTHORIZED, "no")
        f.unlock("000000")
        assertEquals(Problem.WrongCredentials, assertIs<AppState.Locked>(f.state.value).problem)
    }

    // ---- the server list ----

    private fun twoServers() = StoredServers(listOf(server("sid-1", "10.0.0.5:8443", "alice"), server("sid-2", "10.0.0.6:8443", "bob", "carol")))

    @Test fun openingAServerShowsItsLockScreenAndRemembersItAsTheLastOne() {
        store.all = twoServers()
        val f = flow()
        f.openServer("sid-2")
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertEquals("10.0.0.6:8443", locked.server)
        assertEquals(listOf("bob", "carol"), locked.users)
        assertEquals(Triple(PIN_HEX, "sid-2", listOf("10.0.0.6:8443")), connected.single())
        assertEquals("sid-2", store.all?.lastServerId)
        assertEquals(twoServers().servers, store.all?.servers, "opening changes no server")
        f.unlock("483926")
        assertEquals(UnlockRequest("dev-sid-2", "bob", "cred-bob", pin = "483926"), api.unlocks.single())
    }

    @Test fun aServerWithoutUsersOpensItsLogin() {
        store.all = StoredServers(listOf(server("sid-1", "10.0.0.5:8443", "alice"), server("sid-2", "10.0.0.6:8443").copy(deviceId = null)))
        val f = flow()
        f.openServer("sid-2")
        assertEquals(AppState.Login("10.0.0.6:8443", "Shop sid-2"), f.state.value)
        assertEquals(Triple(PIN_HEX, "sid-2", listOf("10.0.0.6:8443")), connected.single())
    }

    @Test fun pairingASecondServerKeepsTheFirst() {
        val first = server("sid-0", "10.0.0.4:8443", "zoe")
        store.stored = first
        api.redeemResponse = api.redeemResponse.copy(serverName = "Market")
        val f = flow()
        assertIs<AppState.Locked>(f.state.value)
        f.showServers()
        f.addServer("192.168.1.2:8443")
        f.enrolling(); f.enroll(good)
        assertIs<AppState.Unlocked>(f.state.value)
        val all = assertNotNull(store.all)
        assertEquals(first, all.servers.first(), "the first server is untouched")
        assertEquals(StoredServer("sid-1", "Market", PIN_HEX, listOf("192.168.1.2:8443"), "dev-1", listOf(StoredUser("alice", "cred-1"))), all.servers[1])
        assertEquals("sid-1", all.lastServerId)
    }

    @Test fun removingAServerRemovesOnlyThatOne() {
        store.all = twoServers()
        val f = flow()
        f.openServer("sid-1"); f.unlock("483926"); f.openSettings()
        f.removeServer()
        val s = assertIs<AppState.Servers>(f.state.value)
        assertEquals(listOf("sid-2"), s.saved.map { it.serverId })
        assertEquals(listOf(server("sid-2", "10.0.0.6:8443", "bob", "carol")), store.all?.servers)
        assertNull(f.workspace)
    }

    @Test fun theServerListFromTheLockScreenEndsTheSession() {
        store.stored = storedAlice()
        val f = flow()
        f.unlock("483926")
        f.lockNow()
        f.showServers()
        val s = assertIs<AppState.Servers>(f.state.value)
        assertEquals(listOf(ServerRow("sid-1", "Shop", "10.0.0.5:8443", 1)), s.saved)
        assertTrue(api.closed)
        assertFalse(api.token)
        assertNull(f.workspace)
        assertFalse(f.canWrite.value)
        f.unlock("483926") // not on the lock screen any more
        assertEquals(1, api.unlocks.size)
    }

    @Test fun theServerListFromPairingDropsThePairingInProgress() {
        val f = pairingFlow()
        f.submitManual("192.168.1.2:8443", "alice", "BCDFG-HJKLM")
        f.showServers()
        assertIs<AppState.Servers>(f.state.value)
        f.confirmFingerprint() // nothing to confirm any more
        assertTrue(api.redeemed.isEmpty())
    }

    @Test fun theFoundListLeavesOutSavedServers() {
        store.all = twoServers()
        discovery.announced = listOf(
            FoundServer("sid-2", "Shop sid-2", "10.0.0.6:8443"),
            FoundServer("sid-9", "Market", "192.168.1.9:8443"),
            FoundServer("sid-9", "Market", "10.8.0.9:8443"), // the same server on another network
        )
        val f = flow()
        assertEquals(listOf(FoundServer("sid-9", "Market", "192.168.1.9:8443")), assertIs<AppState.Servers>(f.state.value).found)
        discovery.announced = emptyList()
        f.refreshFound()
        assertEquals(emptyList(), assertIs<AppState.Servers>(f.state.value).found)
        assertEquals(2, discovery.browses)
    }

    @Test fun addingAServerChecksTheAddressProbesItThenOpensItsLogin() {
        val f = flow()
        f.addServer("no-port")
        assertEquals(Problem.BadAddress, assertIs<AppState.Servers>(f.state.value).problem)
        assertNull(probed)
        probeResult = { throw ClientError.Unreachable() }
        f.addServer("192.168.1.2:8443")
        val s = assertIs<AppState.Servers>(f.state.value)
        assertEquals(Problem.Unreachable, s.problem)
        assertFalse(s.busy)
        probeResult = { PIN_FP }
        f.addServer(" shop.example.com:25655 ")
        assertEquals("shop.example.com:25655", probed)
        assertEquals(AppState.Login("shop.example.com:25655", "Shop"), f.state.value)
    }

    @Test fun openingAFoundServerAddsItsAddress() {
        val found = FoundServer("sid-9", "Market", "192.168.1.9:8443")
        discovery.announced = listOf(found)
        val f = flow()
        f.openFound(found)
        assertEquals("192.168.1.9:8443", probed)
        assertEquals("192.168.1.9:8443", assertIs<AppState.Login>(f.state.value).server)
    }

    // ---- trust on first use ----

    /** The saved Alice server, then back on the server list with a probe that finds [fingerprint]; what was connected so far is forgotten. */
    private fun savedThenList(fingerprint: String = PIN_FP): AppFlow = lockedFlow().apply {
        showServers()
        probeResult = { fingerprint }
        connected.clear()
    }

    @Test fun addingANewServerSavesItsKeyWithoutAskingAndOpensIt() {
        val f = flow()
        f.addServer(" 192.168.1.2:8443 ")
        assertEquals("192.168.1.2:8443", probed)
        assertEquals(Triple(PIN_HEX, "", listOf("192.168.1.2:8443")), connected.first(), "pinned to the key just probed")
        assertEquals(StoredServer("sid-1", "Shop", PIN_HEX, listOf("192.168.1.2:8443")), store.stored, "saved with no device and no users")
        assertEquals("sid-1", store.all?.lastServerId)
        assertEquals(AppState.Login("192.168.1.2:8443", "Shop"), f.state.value, "no fingerprint to confirm")
        assertTrue(api.redeemed.isEmpty() && api.unlocks.isEmpty())
    }

    @Test fun addingAServerOfAnotherProtocolSavesNothing() {
        api.protocol = PROTOCOL_VERSION + 1
        val f = flow()
        f.addServer("192.168.1.2:8443")
        assertEquals(AppState.ProtocolMismatch(PROTOCOL_VERSION + 1), f.state.value)
        assertNull(store.all)
    }

    @Test fun addingASavedServerWithTheSameKeyOpensItAndKeepsTheAddress() {
        val f = savedThenList()
        f.addServer("192.168.1.2:8443")
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertEquals(listOf("alice"), locked.users)
        assertEquals(storedAlice().copy(endpoints = listOf("192.168.1.2:8443", oldAddress)), store.stored)
        assertEquals(1, store.all?.servers?.size)
    }

    @Test fun aSavedServerWithAnotherKeyWarnsAndSendsNothingButTheProbe() {
        val f = savedThenList(OTHER_FP)
        f.addServer("192.168.1.2:8443")
        assertEquals(AppState.CertChanged("sid-1", "Shop", "192.168.1.2:8443"), f.state.value)
        assertEquals(listOf(OTHER_HEX), connected.map { it.first }, "only the server's own /info is read, with the key it showed")
        assertTrue(api.unlocks.isEmpty() && api.redeemed.isEmpty() && api.enrolled.isEmpty())
        assertEquals(storedAlice(), store.stored, "nothing is saved")
    }

    @Test fun cancelOnAChangedKeyGoesBackToTheServerListAndChangesNothing() {
        val f = savedThenList(OTHER_FP)
        f.addServer("192.168.1.2:8443")
        f.cancelCertChanged()
        assertEquals(listOf(ServerRow("sid-1", "Shop", oldAddress, 1)), assertIs<AppState.Servers>(f.state.value).saved)
        assertEquals(storedAlice(), store.stored)
        assertTrue(api.unlocks.isEmpty())
    }

    @Test fun trustingTheNewKeyReplacesItAndKeepsTheUsers() {
        val f = savedThenList(OTHER_FP)
        f.addServer("192.168.1.2:8443")
        probed = null
        f.trustNewKey()
        assertEquals("192.168.1.2:8443", probed, "the key is probed again")
        assertEquals(storedAlice().copy(certPin = OTHER_HEX, endpoints = listOf("192.168.1.2:8443", oldAddress)), store.stored)
        assertEquals(listOf("alice"), assertIs<AppState.Locked>(f.state.value).users)
        assertEquals(OTHER_HEX, connected.last().first, "the server is opened with the new key")
        f.unlock("483926")
        assertEquals(UnlockRequest("dev-1", "alice", "cred", pin = "483926"), api.unlocks.single())
    }

    @Test fun trustingAKeyThatAnotherServerAnswersWithSavesNothing() {
        val f = savedThenList(OTHER_FP)
        f.addServer("192.168.1.2:8443")
        api.infoServerId = "someone-else"
        f.trustNewKey()
        val s = assertIs<AppState.CertChanged>(f.state.value)
        assertEquals(Problem.OtherServer, s.problem)
        assertFalse(s.busy)
        assertEquals(storedAlice(), store.stored)
    }

    @Test fun aChangedKeyOnUnlockWarnsAndCanBeTrusted() {
        val f = lockedFlow()
        api.failUnlock = ClientError.PinMismatch(null, oldAddress)
        f.unlock("483926")
        assertEquals(AppState.CertChanged("sid-1", "Shop", oldAddress), f.state.value)
        assertNull(f.workspace)
        api.failUnlock = null
        probeResult = { OTHER_FP }
        f.trustNewKey()
        assertEquals(oldAddress, probed)
        assertEquals(storedAlice().copy(certPin = OTHER_HEX), store.stored)
        assertIs<AppState.Locked>(f.state.value)
    }

    // ---- login ----

    /** A server saved by its address, with nobody on this device yet: its login form. */
    private fun loginFlow(): AppFlow = flow().apply {
        addServer("192.168.1.2:8443")
        assertIs<AppState.Login>(state.value)
        api.closed = false // the client that read /info
    }

    @Test fun theFirstLoginOnThisDeviceSavesTheDeviceAndTheUserAndUnlocks() {
        val f = loginFlow()
        f.login(" alice ", "483926")
        assertEquals(LoginRequest("alice", "Test PC", "windows", DeviceMode.SHARED, pin = "483926"), api.logins.single())
        assertEquals(StoredServer("sid-1", "Shop", PIN_HEX, listOf("192.168.1.2:8443"), "dev-new", listOf(StoredUser("alice", "cred-alice")), DeviceMode.SHARED), store.stored)
        assertEquals(UnlockRequest("dev-new", "alice", "cred-alice", pin = "483926"), api.unlocks.single())
        assertEquals("alice", assertIs<AppState.Unlocked>(f.state.value).username)
        val text = Json.encodeToString(StoredServers.serializer(), assertNotNull(store.all))
        assertFalse("483926" in text || "access" in text, "no PIN or token is saved")
    }

    @Test fun aUserWithoutAPinIsAskedForANewOneAndThenUnlocks() {
        api.noPinYet += "alice"
        val f = loginFlow()
        f.login("alice", "")
        assertEquals(LoginRequest("alice", "Test PC", "windows", DeviceMode.SHARED), api.logins.single())
        val s = assertIs<AppState.Login>(f.state.value)
        assertTrue(s.needsNewPin)
        assertNull(s.problem)
        assertFalse(s.busy)
        assertNull(store.stored?.deviceId)
        f.login("alice", "", newPin = "4839", newPinRepeat = "4839")
        assertEquals(LoginRequest("alice", "Test PC", "windows", DeviceMode.SHARED, newPin = "4839"), api.logins.last())
        assertEquals(UnlockRequest("dev-new", "alice", "cred-alice", pin = "4839"), api.unlocks.single())
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun aNewPinIsCheckedBeforeItIsSent() {
        api.noPinYet += "alice"
        val f = loginFlow()
        f.login("alice", "")
        f.login("alice", "", newPin = "4839", newPinRepeat = "4893")
        assertEquals(Problem.PinsDiffer, assertIs<AppState.Login>(f.state.value).problem)
        f.login("alice", "", newPin = "483", newPinRepeat = "483")
        assertEquals(Problem.PinDigits, assertIs<AppState.Login>(f.state.value).problem)
        f.login("alice", "", newPin = "48a926", newPinRepeat = "48a926")
        assertEquals(Problem.PinDigits, assertIs<AppState.Login>(f.state.value).problem)
        f.login(" ", "483926")
        assertEquals(Problem.BadUsername, assertIs<AppState.Login>(f.state.value).problem)
        assertEquals(1, api.logins.size, "only the first try was sent")
    }

    @Test fun aWrongPinSaysSoAndSavesNothing() {
        val f = loginFlow()
        val before = store.all
        f.login("alice", WRONG_PIN)
        val s = assertIs<AppState.Login>(f.state.value)
        assertEquals(Problem.WrongCredentials, s.problem)
        assertFalse(s.busy)
        assertEquals(before, store.all)
        assertTrue(api.unlocks.isEmpty())
    }

    @Test fun aLockedAccountShowsTheWait() {
        api.failLogin = ClientError.Api(429, ErrorCode.RATE_LIMITED, "Too many wrong tries.", retryAfterSeconds = 120)
        val f = loginFlow()
        f.login("alice", "483926")
        assertEquals(Problem.TooManyAttempts(120), assertIs<AppState.Login>(f.state.value).problem)
    }

    @Test fun anOldPersonalDeviceSaysToRemoveTheServerAndAddItAgain() {
        val f = lockedFlow()
        f.addUser()
        api.failLogin = ClientError.Api(403, ErrorCode.FORBIDDEN, "This device is personal.", reason = ErrorReasons.DEVICE_PERSONAL)
        f.login("bob", "483926")
        assertEquals(Problem.PersonalDevice, assertIs<AppState.Login>(f.state.value).problem)
        assertEquals(storedAlice(), store.stored)
    }

    @Test fun addUserFromTheLockScreenLogsInWithThisDeviceAndKeepsBoth() {
        val f = lockedFlow(storedAlice(DeviceMode.SHARED))
        f.addUser()
        assertEquals(AppState.Login("10.0.0.5:8443", "Shop", adding = true), f.state.value)
        f.login("bob", "483926")
        assertEquals(LoginRequest("bob", "Test PC", "windows", DeviceMode.SHARED, pin = "483926", deviceId = "dev-1", deviceCredential = "cred"), api.logins.single())
        assertEquals(listOf(StoredUser("alice", "cred"), StoredUser("bob", "cred-bob")), store.stored?.users)
        assertEquals("dev-1", store.stored?.deviceId)
        assertEquals(DeviceMode.SHARED, store.stored?.mode)
        assertEquals(UnlockRequest("dev-1", "bob", "cred-bob", pin = "483926"), api.unlocks.single())
        assertEquals("bob", assertIs<AppState.Unlocked>(f.state.value).username)
    }

    @Test fun addUserTriesTheNextCredentialWhenTheServerDoesNotKnowOne() {
        api.badDeviceCredentials += "cred"
        val f = lockedFlow(storedShared())
        f.addUser()
        f.login("carol", "483926")
        assertEquals(listOf("cred", "cred-bob"), api.logins.map { it.deviceCredential })
        assertEquals(listOf("alice", "bob", "carol"), store.stored?.users?.map { it.username })
        assertTrue(store.stored!!.users.first { it.username == "alice" }.rejected, "tried last next time")
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun backFromAddingAUserShowsTheLockScreenAgain() {
        val f = lockedFlow(storedShared())
        f.addUser()
        f.cancelLogin()
        assertEquals(listOf("alice", "bob"), assertIs<AppState.Locked>(f.state.value).users)
        assertTrue(api.logins.isEmpty())
    }

    @Test fun theServerListFromTheLoginClosesItsClient() {
        val f = loginFlow()
        f.showServers()
        assertEquals(listOf(ServerRow("sid-1", "Shop", "192.168.1.2:8443", 0)), assertIs<AppState.Servers>(f.state.value).saved)
        assertTrue(api.closed)
    }

    // ---- pair by link ----

    @Test fun pairByLinkShowsPreviewThenRedeemsWithSecret() {
        val f = flow()
        f.previewLink("  " + link() + "\n")
        val preview = assertNotNull(assertIs<AppState.Pair>(f.state.value).preview)
        assertEquals(listOf("192.168.1.2:8443"), preview.ep)
        assertTrue(api.redeemed.isEmpty(), "nothing is sent before Continue")
        f.redeemLink()
        assertEquals(RedeemRequest(secret = "secret"), api.redeemed.single())
        assertEquals(Triple(PIN_HEX, "sid-1", listOf("192.168.1.2:8443")), connected.single())
        val enroll = assertIs<AppState.Enroll>(f.state.value)
        assertEquals("192.168.1.2:8443", enroll.server)
    }

    @Test fun badLinkStaysWithInvalidLinkProblem() {
        val f = flow()
        f.previewLink("https://example.com")
        assertEquals(Problem.InvalidLink, assertIs<AppState.Pair>(f.state.value).problem)
    }

    @Test fun cancelPreviewGoesBack() {
        val f = flow()
        f.previewLink(link()); f.cancelPreview()
        assertNull(assertIs<AppState.Pair>(f.state.value).preview)
    }

    @Test fun linkRedeemErrorsStayOnPreview() {
        val cases = mapOf(
            ClientError.Unreachable() to Problem.Unreachable,
            ClientError.Api(400, ErrorCode.PAIRING_INVALID, "bad") to Problem.PairingInvalid,
            ClientError.Api(429, ErrorCode.RATE_LIMITED, "slow", retryAfterSeconds = 30) to Problem.TooManyAttempts(30),
        )
        for ((error, problem) in cases) {
            val f = flow()
            api.failRedeem = error
            f.previewLink(link()); f.redeemLink()
            val s = assertIs<AppState.Pair>(f.state.value)
            assertNotNull(s.preview, "stays on the preview so the user can retry")
            assertFalse(s.busy)
            assertEquals(problem, s.problem)
        }
        assertTrue(api.closed)
    }

    // ---- pair by manual code ----

    @Test fun manualCodeProbesThenConfirmRedeemsWithThatPin() {
        val f = pairingFlow()
        f.submitManual(" 192.168.1.2:8443 ", "alice", "bcdfg-hjklm")
        assertEquals("192.168.1.2:8443", probed)
        val check = assertNotNull(assertIs<AppState.Pair>(f.state.value).check)
        assertEquals(PIN_FP, check.fingerprint)
        assertTrue(api.redeemed.isEmpty(), "nothing is redeemed before the fingerprint is confirmed")
        f.confirmFingerprint()
        assertEquals(RedeemRequest(username = "alice", code = "BCDFGHJKLM"), api.redeemed.single())
        assertEquals(Triple(PIN_HEX, "", listOf("192.168.1.2:8443")), connected.single())
        assertIs<AppState.Enroll>(f.state.value)
    }

    @Test fun cancelAtFingerprintRedeemsNothing() {
        val f = pairingFlow()
        f.submitManual("192.168.1.2:8443", "alice", "BCDFG-HJKLM")
        f.cancelFingerprint()
        assertNull(assertIs<AppState.Pair>(f.state.value).check)
        assertTrue(connected.isEmpty() && api.redeemed.isEmpty())
        f.confirmFingerprint() // nothing to confirm any more
        assertTrue(api.redeemed.isEmpty())
    }

    @Test fun manualFieldsAreChecked() {
        val f = pairingFlow()
        f.submitManual("no-port", "alice", "BCDFG-HJKLM")
        assertEquals(Problem.BadAddress, assertIs<AppState.Pair>(f.state.value).problem)
        f.submitManual("host:99999", "alice", "BCDFG-HJKLM")
        assertEquals(Problem.BadAddress, assertIs<AppState.Pair>(f.state.value).problem)
        f.submitManual("host:1", " ", "BCDFG-HJKLM")
        assertEquals(Problem.BadUsername, assertIs<AppState.Pair>(f.state.value).problem)
        f.submitManual("host:1", "alice", "BCDFG")
        assertEquals(Problem.BadCode, assertIs<AppState.Pair>(f.state.value).problem)
        assertNull(probed)
    }

    @Test fun unreachableProbeStaysOnPairWithError() {
        val f = pairingFlow()
        probeResult = { throw ClientError.Unreachable() }
        f.submitManual("192.168.1.2:8443", "alice", "BCDFG-HJKLM")
        val s = assertIs<AppState.Pair>(f.state.value)
        assertEquals(Problem.Unreachable, s.problem)
        assertNull(s.check)
    }

    @Test fun manualRedeemWithWrongCodeStaysOnFingerprint() {
        val f = pairingFlow()
        f.submitManual("192.168.1.2:8443", "alice", "BCDFG-HJKLM")
        api.failRedeem = ClientError.Api(400, ErrorCode.PAIRING_INVALID, "no")
        f.confirmFingerprint()
        val s = assertIs<AppState.Pair>(f.state.value)
        assertNotNull(s.check)
        assertEquals(Problem.PairingInvalid, s.problem)
    }

    @Test fun manualPairingWithOtherProtocolIsBlocked() {
        val f = pairingFlow()
        api.protocol = PROTOCOL_VERSION + 1
        f.submitManual("192.168.1.2:8443", "alice", "BCDFG-HJKLM")
        f.confirmFingerprint()
        assertEquals(AppState.ProtocolMismatch(PROTOCOL_VERSION + 1), f.state.value)
        assertTrue(api.redeemed.isEmpty())
    }

    // ---- blocking states from any step ----

    @Test fun aChangedKeyFromEveryStepWarnsOrStops() {
        val mismatch = ClientError.PinMismatch()
        // link redeem: nothing of this server is held yet, so the pairing stops with the reason
        flow().apply { api.failRedeem = mismatch; previewLink(link()); redeemLink(); assertEquals(Problem.PinMismatch, assertIs<AppState.Pair>(state.value).problem) }
        api.failRedeem = null
        // manual probe never talks to a pinned server, but a mismatch on confirm stops the pairing
        pairingFlow().apply { api.failInfo = mismatch; submitManual("h:1", "a", "BCDFG-HJKLM"); confirmFingerprint(); assertEquals(Problem.PinMismatch, assertIs<AppState.Pair>(state.value).problem) }
        api.failInfo = null
        store.all = null // the server the step above added is not part of this one
        // enroll: the server is known by then, so its changed key is offered for trust
        flow().apply { enrolling(); api.failEnroll = mismatch; enroll(good); assertEquals(AppState.CertChanged("sid-1", "", "192.168.1.2:8443"), state.value) }
        api.failEnroll = null
        // unlock from the lock screen
        store.stored = StoredServer("s", "", PIN_HEX, listOf("h:1"), "d", listOf(StoredUser("alice", "c")))
        flow().apply { api.failUnlock = mismatch; unlock("483926"); assertEquals(AppState.CertChanged("s", "", "h:1"), state.value) }
    }

    @Test fun protocolMismatchBlocksWithServerVersion() {
        flow().apply { api.failRedeem = ClientError.ProtocolMismatch(7, "old"); previewLink(link()); redeemLink(); assertEquals(AppState.ProtocolMismatch(7), state.value) }
        api.failRedeem = null
        store.stored = StoredServer("s", "", PIN_HEX, listOf("h:1"), "d", listOf(StoredUser("alice", "c")))
        flow().apply { api.failUnlock = ClientError.ProtocolMismatch(2, "old"); unlock("483926"); assertEquals(AppState.ProtocolMismatch(2), state.value) }
    }

    // ---- enroll ----

    private fun redeemWith(passwordRequired: Boolean, hasPassword: Boolean, hasPin: Boolean, pinLength: Int = 6) {
        api.redeemResponse = RedeemResponse("tok", "alice", passwordRequired, hasPassword, hasPin, pinLength)
    }

    @Test fun enrollSettingPinOnlySendsNewPinAndUnlocksWithPin() {
        val f = flow()
        f.enrolling()
        f.enroll(good)
        val (token, req) = api.enrolled.single()
        assertEquals("tok", token)
        assertEquals(EnrollRequest("Till 1", "windows", DeviceMode.PERSONAL, newPin = "483926"), req)
        assertEquals(UnlockRequest("dev-1", "alice", "cred-1", pin = "483926"), api.unlocks.single())
        val unlocked = assertIs<AppState.Unlocked>(f.state.value)
        assertEquals("alice", unlocked.username)
        assertEquals(ConnectionStatus.CONNECTED, unlocked.connection.value)
    }

    @Test fun storedCredentialsNeverHoldSecrets() {
        val f = flow()
        f.enrolling(); f.enroll(good)
        val saved = assertNotNull(store.stored)
        assertEquals(StoredServer("sid-1", "", PIN_HEX, listOf("192.168.1.2:8443"), "dev-1", listOf(StoredUser("alice", "cred-1"))), saved)
        val text = Json.encodeToString(StoredServers.serializer(), assertNotNull(store.all))
        assertFalse("483926" in text || "access" in text)
    }

    @Test fun enrollEnteringExistingPinAndPassword() {
        redeemWith(passwordRequired = true, hasPassword = true, hasPin = true)
        val f = flow()
        f.enrolling()
        f.enroll(EnrollInput(password = "pw-existing", pin = "483926", mode = DeviceMode.SHARED, label = "Shared tablet"))
        val req = api.enrolled.single().second
        assertEquals(EnrollRequest("Shared tablet", "windows", DeviceMode.SHARED, password = "pw-existing", pin = "483926"), req)
        assertEquals("pw-existing", api.unlocks.single().password)
        assertNull(api.unlocks.single().pin)
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun enrollSettingPasswordAndPin() {
        redeemWith(passwordRequired = true, hasPassword = false, hasPin = false)
        val f = flow()
        f.enrolling()
        f.enroll(EnrollInput("a good password", "a good password", "483926", "483926", DeviceMode.PERSONAL, "PC"))
        val req = api.enrolled.single().second
        assertEquals("a good password", req.newPassword)
        assertNull(req.password)
        assertEquals("483926", req.newPin)
        assertNull(req.pin)
    }

    @Test fun enrollWithPasswordNotRequiredIgnoresPasswordFields() {
        val f = flow()
        f.enrolling()
        f.enroll(EnrollInput(password = "ignored", passwordRepeat = "different", pin = "483926", pinRepeat = "483926", label = "PC"))
        val req = api.enrolled.single().second
        assertNull(req.password); assertNull(req.newPassword)
    }

    @Test fun enrollEnteringExistingPinNeedsNoConfirm() {
        redeemWith(passwordRequired = false, hasPassword = false, hasPin = true, pinLength = 4)
        val f = flow()
        f.enrolling()
        f.enroll(EnrollInput(pin = "4839", label = "PC"))
        assertEquals("4839", api.enrolled.single().second.pin)
    }

    @Test fun enrollValidation() {
        val setAll = RedeemResponse("t", "alice", passwordRequired = true, hasPassword = false, hasPin = false, pinLength = 6)
        val ok = EnrollInput("pw-long-enough", "pw-long-enough", "483926", "483926", DeviceMode.PERSONAL, "PC")
        assertNull(validateEnroll(setAll, ok))
        assertEquals(Problem.PasswordEmpty, validateEnroll(setAll, EnrollInput("", "", "483926", "483926", label = "PC")))
        assertEquals(Problem.PasswordsDiffer, validateEnroll(setAll, EnrollInput("pw-one", "pw-two", "483926", "483926", label = "PC")))
        assertEquals(Problem.PinFormat(6), validateEnroll(setAll, EnrollInput("pw", "pw", "48392", "48392", label = "PC")))
        assertEquals(Problem.PinFormat(6), validateEnroll(setAll, EnrollInput("pw", "pw", "48392a", "48392a", label = "PC")))
        assertEquals(Problem.PinFormat(6), validateEnroll(setAll, EnrollInput("pw", "pw", "4839261", "4839261", label = "PC")))
        assertEquals(Problem.PinsDiffer, validateEnroll(setAll, EnrollInput("pw", "pw", "483926", "483927", label = "PC")))
        assertEquals(Problem.LabelEmpty, validateEnroll(setAll, EnrollInput("pw", "pw", "483926", "483926", label = "  ")))
        // entering existing ones: no confirm fields are compared
        val enter = RedeemResponse("t", "alice", passwordRequired = true, hasPassword = true, hasPin = true, pinLength = 6)
        assertNull(validateEnroll(enter, EnrollInput("pw", "", "483926", "", label = "PC")))
    }

    @Test fun invalidEnrollInputSendsNothingAndKeepsTheForm() {
        val f = flow()
        f.enrolling()
        f.enroll(EnrollInput(pin = "483926", pinRepeat = "000000", label = "PC"))
        assertEquals(Problem.PinsDiffer, assertIs<AppState.Enroll>(f.state.value).problem)
        assertTrue(api.enrolled.isEmpty())
    }

    @Test fun enrollUnreachableStaysOnEnrollWithoutStoring() {
        val f = flow()
        f.enrolling()
        api.failEnroll = ClientError.Unreachable()
        f.enroll(good)
        val s = assertIs<AppState.Enroll>(f.state.value)
        assertEquals(Problem.Unreachable, s.problem)
        assertFalse(s.busy)
        assertNull(store.stored)
    }

    @Test fun credentialsSetElsewhereTurnsFieldsIntoEnterExisting() {
        val f = flow()
        f.enrolling()
        api.failEnroll = ClientError.Api(409, ErrorCode.CREDENTIALS_CHANGED, "set")
        f.enroll(good)
        val s = assertIs<AppState.Enroll>(f.state.value)
        assertTrue(s.redeem.hasPin)
        assertEquals(Problem.CredentialsChanged, s.problem)
    }

    @Test fun unlockFailingAfterEnrollKeepsCredentialsAndShowsLocked() {
        val f = flow()
        f.enrolling()
        api.failUnlock = ClientError.Unreachable()
        f.enroll(good)
        assertNotNull(store.stored)
        val s = assertIs<AppState.Locked>(f.state.value)
        assertEquals("alice", s.username)
        assertEquals(Problem.Unreachable, s.problem)
    }

    @Test fun startOverReturnsToPairAndClosesClient() {
        val f = flow()
        f.enrolling()
        f.startOver()
        assertIs<AppState.Pair>(f.state.value)
        assertTrue(api.closed)
    }

    @Test fun deferredCallShowsBusyAndIgnoresSecondTap() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : ServerApi by api {
            override suspend fun redeem(request: RedeemRequest): RedeemResponse { api.redeemed += request; gate.await(); return api.redeemResponse }
        }
        val f = AppFlow(scope, store, ThisDevice("x", "windows"), { _, _, _ -> slow }, { "" }, prefs, { 0L }, Dispatchers.Unconfined)
        f.previewLink(link()); f.redeemLink()
        assertTrue(assertIs<AppState.Pair>(f.state.value).busy)
        f.redeemLink()
        gate.complete(Unit)
        assertEquals(1, api.redeemed.size)
        assertIs<AppState.Enroll>(f.state.value)
    }

    // ---- lock screen ----

    private fun lockedFlow(creds: StoredServer = storedAlice()): AppFlow { store.stored = creds; return flow() }

    @Test fun sharedDeviceListsItsUsersAndWaitsForAPick() {
        val f = lockedFlow(storedShared())
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertTrue(locked.shared)
        assertEquals(listOf("alice", "bob"), locked.users)
        assertNull(locked.username)
        f.unlock("483926") // nobody picked yet: nothing is sent
        assertTrue(api.unlocks.isEmpty())
    }

    @Test fun personalDeviceHasTheOneUserSelected() {
        val locked = assertIs<AppState.Locked>(lockedFlow().state.value)
        assertEquals("alice", locked.username)
        assertFalse(locked.shared)
    }

    @Test fun pickingAUserUnlocksWithThatUsersCredential() {
        val f = lockedFlow(storedShared())
        f.selectUser("bob")
        assertEquals("bob", assertIs<AppState.Locked>(f.state.value).username)
        f.unlock("483926")
        assertEquals(UnlockRequest("dev-1", "bob", "cred-bob", pin = "483926"), api.unlocks.single())
        assertEquals("bob", assertIs<AppState.Unlocked>(f.state.value).username)
    }

    @Test fun twoUsersTakeTurnsOnASharedDevice() {
        val f = lockedFlow(storedShared())
        f.selectUser("alice"); f.unlock("111111")
        f.lockNow()
        assertNull(assertIs<AppState.Locked>(f.state.value).username, "the list is shown again")
        f.selectUser("bob"); f.unlock("222222")
        assertEquals(listOf("alice", "bob"), api.unlocks.map { it.username })
        assertEquals("bob", assertIs<AppState.Unlocked>(f.state.value).username)
    }

    @Test fun backToTheListAndPersonalDevicesIgnoreThePick() {
        val shared = lockedFlow(storedShared())
        shared.selectUser("alice"); shared.selectUser(null)
        assertNull(assertIs<AppState.Locked>(shared.state.value).username)
        shared.selectUser("mallory")
        assertNull(assertIs<AppState.Locked>(shared.state.value).username, "not a user of this device")
        val personal = lockedFlow()
        personal.selectUser(null)
        assertEquals("alice", assertIs<AppState.Locked>(personal.state.value).username)
    }

    @Test fun wrongPinAndBackoffMessagesStayOnTheLockScreen() {
        val f = lockedFlow()
        api.failUnlock = ClientError.Api(401, ErrorCode.UNAUTHORIZED, "Unlock failed.")
        f.unlock("000000")
        assertEquals(Problem.WrongCredentials, assertIs<AppState.Locked>(f.state.value).problem)
        api.failUnlock = ClientError.Api(429, ErrorCode.RATE_LIMITED, "slow", retryAfterSeconds = 45)
        f.unlock("000000")
        val s = assertIs<AppState.Locked>(f.state.value)
        assertEquals(Problem.TooManyAttempts(45), s.problem)
        assertFalse(s.busy)
        api.failUnlock = ClientError.Api(403, ErrorCode.BANNED, "banned")
        f.unlock("000000")
        assertEquals(Problem.Banned, assertIs<AppState.Locked>(f.state.value).problem)
    }

    @Test fun unlockIsIgnoredWhileBusy() {
        store.stored = storedAlice()
        val gate = CompletableDeferred<Unit>()
        val slow = object : ServerApi by api {
            override suspend fun unlock(request: UnlockRequest): UnlockResponse { api.unlocks += request; gate.await(); return UnlockResponse("a", 900) }
        }
        val f = AppFlow(scope, store, ThisDevice("x", "windows"), { _, _, _ -> slow }, { "" }, prefs, { 0L }, Dispatchers.Unconfined)
        f.unlock("483926")
        assertTrue(assertIs<AppState.Locked>(f.state.value).busy)
        f.unlock("483926")
        gate.complete(Unit)
        assertEquals(1, api.unlocks.size)
    }

    // ---- add a user to a shared device ----

    @Test fun addUserGoesToPairAndTheEnrollJoinsThisDevice() {
        val f = lockedFlow(storedShared())
        f.addUserByPairing()
        assertTrue(assertIs<AppState.Pair>(f.state.value).adding)
        api.redeemResponse = RedeemResponse("tok", "carol", passwordRequired = false, hasPassword = false, hasPin = false, pinLength = 6)
        f.previewLink(link().replace("alice", "alice")); f.redeemLink()
        assertTrue(assertIs<AppState.Enroll>(f.state.value).adding)
        // The form's mode and name are ignored: the device keeps its own.
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321", mode = DeviceMode.PERSONAL, label = ""))
        val req = api.enrolled.single().second
        assertEquals("dev-1", req.deviceId)
        assertEquals("cred", req.deviceCredential)
        assertEquals(DeviceMode.SHARED, req.mode)
        val saved = assertNotNull(store.stored)
        assertEquals("dev-1", saved.deviceId)
        assertEquals(DeviceMode.SHARED, saved.mode)
        assertEquals(listOf("alice" to "cred", "bob" to "cred-bob", "carol" to "cred-1"), saved.users.map { it.username to it.credential })
        assertEquals("carol", assertIs<AppState.Unlocked>(f.state.value).username)
    }

    @Test fun addUserIsOnlyOfferedOnSharedDevices() {
        val f = lockedFlow()
        f.addUserByPairing()
        assertIs<AppState.Locked>(f.state.value)
    }

    @Test fun addUserRefusesAPairingForAnotherServerBeforeUsingIt() {
        val f = lockedFlow(storedShared())
        f.addUserByPairing()
        val other = Json.encodeToString(PairPayload(1, "other-sid", PIN_HEX, "s", "carol", listOf("h:1")))
        f.previewLink("shoparchive://pair?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(other.toByteArray()))
        f.redeemLink()
        assertEquals(Problem.WrongServer, assertIs<AppState.Pair>(f.state.value).problem)
        assertTrue(api.redeemed.isEmpty(), "the one-time pairing is not used up")
    }

    @Test fun cancelAddUserReturnsToTheLockScreen() {
        val f = lockedFlow(storedShared())
        f.addUserByPairing(); f.cancelAddUser()
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertEquals(listOf("alice", "bob"), locked.users)
        f.selectUser("alice"); f.unlock("111111")
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun enrollingASharedDeviceRemembersItsMode() {
        val f = flow()
        f.enrolling()
        f.enroll(EnrollInput(pin = "483926", pinRepeat = "483926", mode = DeviceMode.SHARED, label = "Tablet"))
        assertEquals(DeviceMode.SHARED, store.stored?.mode)
    }

    // ---- addresses ----

    private val oldAddress = "10.0.0.5:8443"
    private val newAddress = "192.168.1.2:8443"

    @Test fun aUserAddedWithANewAddressKeepsItFirstAndTheOldOnesAfterIt() {
        val f = lockedFlow(storedShared())
        f.addUserByPairing()
        f.previewLink(link(endpoints = listOf(newAddress))); f.redeemLink()
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(listOf(newAddress, oldAddress), store.stored?.endpoints)
        // After the app locks and starts again, the address that works is the one tried first.
        connected.clear()
        flow()
        assertEquals(listOf(newAddress, oldAddress), connected.single().third)
    }

    @Test fun storedAddressesAreCappedDroppingTheOldest() {
        val many = (1..4).map { "10.0.0.$it:8443" }
        val f = lockedFlow(storedShared().copy(endpoints = many))
        f.addUserByPairing()
        f.previewLink(link(endpoints = listOf("10.0.1.1:8443", "10.0.1.2:8443"))); f.redeemLink()
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(listOf("10.0.1.1:8443", "10.0.1.2:8443", "10.0.0.1:8443", "10.0.0.2:8443"), store.stored?.endpoints)
    }

    @Test fun addressesTheServerListsAreSavedWithTheOneInUseFirst() {
        store.stored = storedAlice()
        api.announced = listOf("shop.example.com:25655", oldAddress)
        val f = flow()
        f.unlock("483926")
        assertEquals(listOf(oldAddress, "shop.example.com:25655"), store.stored?.endpoints)
        assertEquals(listOf(oldAddress, "shop.example.com:25655"), connectedAfterRestart())
    }

    private fun connectedAfterRestart(): List<String> { connected.clear(); flow(); return connected.single().third }

    @Test fun nothingIsSavedWhenTheServerListsWhatIsAlreadyStored() {
        store.stored = storedAlice().copy(endpoints = listOf(oldAddress, "shop.example.com:25655", "gone:1"))
        api.announced = listOf("shop.example.com:25655", oldAddress)
        var saves = 0
        val counting = object : CredentialStore by store {
            override fun save(servers: StoredServers) { saves++; store.save(servers) }
        }
        val f = AppFlow(scope, counting, ThisDevice("Test PC", "windows"), { _, _, eps -> api.also { it.endpoints = eps } }, { PIN_FP }, prefs, { clock }, Dispatchers.Unconfined)
        f.unlock("483926")
        assertEquals(0, saves)
    }

    @Test fun aFailedSaveOfTheAddressesDoesNotBreakTheSession() {
        store.stored = storedAlice()
        api.announced = listOf("shop.example.com:25655")
        val broken = object : CredentialStore by store {
            override fun save(servers: StoredServers) = throw java.io.IOException("disk full")
        }
        val f = AppFlow(scope, broken, ThisDevice("Test PC", "windows"), { _, _, eps -> api.also { it.endpoints = eps } }, { PIN_FP }, prefs, { clock }, Dispatchers.Unconfined)
        f.unlock("483926")
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun aFailedSaveOfTheAddressesIsTriedAgainAtTheNextConfigLoadEvenWithTheSameAddresses() {
        store.stored = storedAlice()
        api.announced = listOf("shop.example.com:25655")
        var failing = true
        var saves = 0
        val flaky = object : CredentialStore by store {
            override fun save(servers: StoredServers) {
                saves++
                if (failing) throw java.io.IOException("disk full")
                store.save(servers)
            }
        }
        val f = AppFlow(scope, flaky, ThisDevice("Test PC", "windows"), { _, _, eps -> api.also { it.endpoints = eps } }, { PIN_FP }, prefs, { clock }, Dispatchers.Unconfined)
        f.unlock("483926")
        assertEquals(listOf(oldAddress), store.stored?.endpoints, "the first save failed")
        failing = false
        assertNotNull(f.workspace).retry() // the same config again
        assertEquals(listOf(oldAddress, "shop.example.com:25655"), store.stored?.endpoints)
        val done = saves
        f.workspace!!.retry()
        assertEquals(done, saves, "nothing more once it is on disk")
    }

    /** Waits (on real threads) until [condition] holds; the test fails if it never does. */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(5)
        }
    }

    /** A store whose next save stops inside [save], holding the flow's save lock, until [release] is counted down. */
    private class GatedStore(val inner: FakeStore) : CredentialStore by inner {
        @Volatile var gateNext = false
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        private val log = java.util.Collections.synchronizedList(mutableListOf<StoredServer>())
        /** A snapshot of every write so far, in order (the one server each holds). */
        fun written(): List<StoredServer> = synchronized(log) { log.toList() }
        override fun save(servers: StoredServers) {
            if (gateNext) {
                gateNext = false
                entered.countDown()
                check(release.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "the gate was never opened" }
            }
            synchronized(log) { log += servers.servers.single(); inner.save(servers) }
        }
    }

    /** Counts the tasks sent to it, so a test can tell that a coroutine has moved onto this dispatcher. */
    private class CountingDispatcher(private val to: kotlinx.coroutines.CoroutineDispatcher) : kotlinx.coroutines.CoroutineDispatcher() {
        val sent = java.util.concurrent.atomic.AtomicInteger()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { sent.incrementAndGet(); to.dispatch(context, block) }
    }

    @Test fun anEndpointSaveInFlightWhileAUserIsAddedNeitherLosesTheUserNorTheAddresses() {
        store.stored = storedShared()
        val gated = GatedStore(store)
        val io = CountingDispatcher(Dispatchers.IO)
        api.announced = listOf("shop.example.com:25655")
        api.redeemResponse = RedeemResponse("tok", "carol", passwordRequired = false, hasPassword = false, hasPin = false, pinLength = 6)
        val f = AppFlow(scope, gated, ThisDevice("Test PC", "windows"), { _, _, eps -> api.also { it.endpoints = eps } }, { PIN_FP }, prefs, { clock }, io)
        awaitTrue("the lock screen") { f.state.value is AppState.Locked }
        try {
            // alice unlocks; the config she loads lists a new address, and saving it stops in the store while holding the lock.
            gated.gateNext = true
            f.selectUser("alice"); f.unlock("111111")
            assertTrue(gated.entered.await(30, java.util.concurrent.TimeUnit.SECONDS), "the address save reached the store")
            // Meanwhile the device is locked and carol is added: her enrollment save has to wait behind the address save.
            f.lockNow()
            f.addUserByPairing()
            f.previewLink(link(endpoints = listOf(newAddress))); f.redeemLink()
            val before = io.sent.get()
            f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
            awaitTrue("the enrollment moved on to its save") { io.sent.get() > before } // it is queued on the lock from here, which the gate holds
            assertEquals(emptyList(), gated.written(), "nothing is written while the first save holds the lock")
            assertTrue(assertIs<AppState.Enroll>(f.state.value).busy)
        } finally {
            gated.release.countDown()
        }
        awaitTrue("carol unlocked") { (f.state.value as? AppState.Unlocked)?.username == "carol" }
        // First the address save (alice and bob), then the enrollment built on top of it: nothing the first wrote is lost.
        awaitTrue("both writes") { gated.written().size >= 2 }
        val writes = gated.written()
        val final = assertNotNull(store.stored)
        assertEquals(listOf("alice", "bob", "carol"), final.users.map { it.username }.sorted())
        assertTrue("shop.example.com:25655" in final.endpoints && newAddress in final.endpoints, "${final.endpoints}")
        assertEquals(listOf("alice", "bob"), writes.first().users.map { it.username }.sorted(), "the queued-behind address save came first")
        assertTrue(writes.all { w -> w.users.map { it.username }.containsAll(listOf("alice", "bob")) }, "no write dropped an existing user")
        assertTrue(writes.drop(1).all { w -> w.users.any { it.username == "carol" } }, "every write after the enrollment keeps carol")
        assertEquals(final, writes.last(), "the last write is what is on disk")
    }

    @Test fun cancellingAddUserDoesNotPretendAnUnsavedChangeIsOnDisk() {
        store.stored = storedShared()
        api.announced = listOf("shop.example.com:25655")
        var failing = true
        val flaky = object : CredentialStore by store {
            override fun save(servers: StoredServers) { if (failing) throw java.io.IOException("disk full"); store.save(servers) }
        }
        val f = AppFlow(scope, flaky, ThisDevice("Test PC", "windows"), { _, _, eps -> api.also { it.endpoints = eps } }, { PIN_FP }, prefs, { clock }, Dispatchers.Unconfined)
        f.selectUser("alice"); f.unlock("111111")
        assertEquals(listOf(oldAddress), store.stored?.endpoints, "the address save failed")
        f.lockNow(); f.addUserByPairing(); f.cancelAddUser()
        assertEquals(listOf("alice", "bob"), assertIs<AppState.Locked>(f.state.value).users)
        failing = false
        f.selectUser("alice"); f.unlock("111111")
        assertEquals(listOf(oldAddress, "shop.example.com:25655"), store.stored?.endpoints, "the save owed is made after all")
    }

    // ---- finding the server again ----

    private val movedTo = "192.168.7.9:8443"

    @Test fun whenNoSavedAddressAnswersTheServerIsFoundByDiscoveryAndTheNewAddressIsSaved() {
        store.stored = storedAlice()
        api.worksAt = movedTo
        discovery.found = listOf(movedTo)
        probes[movedTo] = FakeApi().also { it.endpoints = listOf(movedTo) }
        val f = flow()
        f.unlock("483926")
        assertIs<AppState.Unlocked>(f.state.value)
        assertEquals(1, discovery.searches)
        assertEquals(listOf(movedTo, oldAddress), store.stored?.endpoints)
        assertEquals(PIN_HEX, connected.last().first, "the candidate is reached with the same pin")
    }

    @Test fun aCandidateOfAnotherServerIsIgnored() {
        store.stored = storedAlice()
        api.worksAt = movedTo
        discovery.found = listOf(movedTo)
        probes[movedTo] = FakeApi().also { it.endpoints = listOf(movedTo); it.infoServerId = "someone-else" }
        val f = flow()
        f.unlock("483926")
        assertEquals(Problem.Unreachable, assertIs<AppState.Locked>(f.state.value).problem)
        assertEquals(listOf(oldAddress), store.stored?.endpoints)
        assertTrue(probes.getValue(movedTo).closed)
    }

    @Test fun aCandidateWithAnotherKeyIsIgnored() {
        store.stored = storedAlice()
        api.worksAt = movedTo
        discovery.found = listOf(movedTo)
        probes[movedTo] = FakeApi().also { it.endpoints = listOf(movedTo); it.failInfo = ClientError.PinMismatch(null) }
        val f = flow()
        f.unlock("483926")
        assertEquals(Problem.Unreachable, assertIs<AppState.Locked>(f.state.value).problem)
        assertEquals(listOf(oldAddress), store.stored?.endpoints)
    }

    @Test fun discoveryDoesNotRunWhenTheKeyIsWrong() {
        store.stored = storedAlice()
        api.failUnlock = ClientError.PinMismatch(null)
        discovery.found = listOf(movedTo)
        val f = flow()
        f.unlock("483926")
        assertIs<AppState.CertChanged>(f.state.value)
        assertEquals(0, discovery.searches)
    }

    @Test fun everyUnlockTheUserStartsMaySearchOnceSoAServerThatComesBackOnANewAddressIsFound() {
        store.stored = storedAlice()
        api.worksAt = "nowhere:1" // the server is off: nothing is found
        val f = flow()
        f.unlock("483926"); f.unlock("483926")
        assertEquals(2, discovery.searches, "one search per attempt, not one per outage")
        assertEquals(Problem.Unreachable, assertIs<AppState.Locked>(f.state.value).problem)
        // It comes back on a new address and announces itself.
        api.worksAt = movedTo
        discovery.found = listOf(movedTo)
        probes[movedTo] = FakeApi().also { it.endpoints = listOf(movedTo) }
        f.unlock("483926")
        assertIs<AppState.Unlocked>(f.state.value)
        assertEquals(movedTo, store.stored?.endpoints?.first())
    }

    @Test fun theLiveSocketSearchesAgainAfterTheCooldown() {
        store.stored = storedAlice()
        var mono = 0L
        val f = AppFlow(
            scope, store, ThisDevice("Test PC", "windows"), { _, _, eps -> probes[eps.singleOrNull()] ?: api.also { it.endpoints = eps } },
            { PIN_FP }, prefs, { clock }, Dispatchers.Unconfined, monotonicMs = { mono }, discovery = discovery,
        )
        f.unlock("483926")
        val drop = CompletableDeferred<Unit>().also { api.dropSocket = it }
        api.worksAt = movedTo // gone, and not announced yet
        drop.complete(Unit)
        repeat(40) { if (discovery.searches == 0) Thread.sleep(100) }
        assertEquals(1, discovery.searches)
        // Within the cooldown the retries do not search again; after it they do, and find the server.
        Thread.sleep(2500)
        assertEquals(1, discovery.searches)
        mono += 61_000
        discovery.found = listOf(movedTo)
        probes[movedTo] = FakeApi().also { it.endpoints = listOf(movedTo) }
        repeat(100) { if (store.stored?.endpoints?.first() != movedTo) Thread.sleep(100) }
        assertEquals(2, discovery.searches)
        assertEquals(movedTo, store.stored?.endpoints?.first())
    }

    @Test fun theAddressThatServedConfigIsSavedFirstEvenWhenTheServerDoesNotListIt() {
        store.stored = storedAlice().copy(endpoints = listOf("old:1", oldAddress))
        api.announced = listOf("shop.example.com:25655")
        val f = flow()
        api.endpoints = listOf(movedTo, "old:1") // what the client uses now
        f.unlock("483926")
        assertEquals(listOf(movedTo, "shop.example.com:25655", "old:1", oldAddress), store.stored?.endpoints)
    }

    @Test fun anEnrollAnswerThatArrivesAfterStartOverChangesNothing() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : ServerApi by api {
            override suspend fun enroll(enrollmentToken: String, request: EnrollRequest): EnrollResponse { gate.await(); return api.enroll(enrollmentToken, request) }
        }
        val f = AppFlow(scope, store, ThisDevice("x", "windows"), { _, _, eps -> api.also { it.endpoints = eps }.let { slow } }, { "" }, prefs, { 0L }, Dispatchers.Unconfined)
        f.enrolling()
        f.enroll(good)
        assertTrue(assertIs<AppState.Enroll>(f.state.value).busy)
        f.startOver()
        assertIs<AppState.Pair>(f.state.value)
        gate.complete(Unit) // the old enroll succeeds late
        assertIs<AppState.Pair>(f.state.value)
        assertNull(store.stored, "nothing is saved over the new pairing")
        assertTrue(api.unlocks.isEmpty())
    }

    @Test fun aFailedEnrollAnswerThatArrivesAfterStartOverDoesNotRestoreTheEnrollScreen() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : ServerApi by api {
            override suspend fun enroll(enrollmentToken: String, request: EnrollRequest): EnrollResponse { gate.await(); throw ClientError.Unreachable() }
        }
        val f = AppFlow(scope, store, ThisDevice("x", "windows"), { _, _, eps -> api.also { it.endpoints = eps }.let { slow } }, { "" }, prefs, { 0L }, Dispatchers.Unconfined)
        f.enrolling()
        f.enroll(good)
        f.startOver()
        gate.complete(Unit)
        assertIs<AppState.Pair>(f.state.value)
    }

    @Test fun theLiveConnectionFindsTheServerAgainWhenItsAddressChangesWhileUnlocked() {
        store.stored = storedAlice()
        val f = flow()
        f.unlock("483926")
        val status = assertIs<AppState.Unlocked>(f.state.value).connection
        assertEquals(ConnectionStatus.CONNECTED, status.value)
        discovery.found = listOf(movedTo)
        probes[movedTo] = FakeApi().also { it.endpoints = listOf(movedTo) }
        // The socket drops, and the old address no longer answers.
        val drop = CompletableDeferred<Unit>().also { api.dropSocket = it }
        api.worksAt = movedTo
        drop.complete(Unit)
        repeat(60) { if (store.stored?.endpoints?.first() != movedTo) Thread.sleep(100) } // the live loop waits a second before trying again
        assertEquals(1, discovery.searches)
        assertEquals(movedTo, api.endpoints.first())
        assertEquals(listOf(movedTo, oldAddress), store.stored?.endpoints)
        assertEquals(ConnectionStatus.CONNECTED, status.value)
    }

    // ---- screens by permission ----

    @Test fun aWorkspaceWithoutReportsNodesHasNoReportsAndCannotOpenThem() {
        api.records.configBranches = listOf("main")
        api.permissions = listOf(xyz.felismp.shoparchive.shared.PermissionNodes.ENTRY_CREATE, xyz.felismp.shoparchive.shared.PermissionNodes.DAY_OPEN)
        store.stored = storedAlice()
        val f = flow(); f.unlock("483926")
        val ws = assertNotNull(f.workspace)
        assertFalse(ws.capabilities.value.reports)
        ws.go(Destination.REPORTS)
        assertEquals(Destination.TODAY, ws.destination.value)
        ws.openReports(ReportsTab.CLOSE_DAY)
        assertEquals(Destination.TODAY, ws.destination.value)
        ws.go(Destination.HISTORY) // no view node either
        assertEquals(Destination.TODAY, ws.destination.value)
        ws.go(Destination.RECORD)
        assertEquals(Destination.RECORD, ws.destination.value)
    }

    @Test fun aRightsChangeAtTheNextConfigLoadMovesAwayFromAScreenThatIsGone() {
        api.records.configBranches = listOf("main")
        store.stored = storedAlice()
        val f = flow(); f.unlock("483926")
        val ws = assertNotNull(f.workspace)
        ws.openReports(ReportsTab.EXPORT)
        assertEquals(Destination.REPORTS, ws.destination.value)
        api.permissions = listOf(xyz.felismp.shoparchive.shared.PermissionNodes.DAY_CLOSE)
        ws.retry()
        assertEquals(Destination.REPORTS, ws.destination.value)
        assertEquals(ReportsTab.CLOSE_DAY, ws.reportsTab.value, "the tab that is left")
        api.permissions = emptyList()
        ws.retry()
        assertEquals(Destination.TODAY, ws.destination.value)
    }

    // ---- auto-lock ----

    private val minute = 60_000L

    @Test fun sharedDeviceLocksAfterTheServersSharedMinutes() {
        val f = lockedFlow(storedShared())
        f.selectUser("alice"); f.unlock("111111") // server says 2 minutes for shared
        clock += 119_000; f.checkIdle()
        assertIs<AppState.Unlocked>(f.state.value)
        clock += 1_000; f.checkIdle()
        assertIs<AppState.Locked>(f.state.value)
    }

    @Test fun personalDeviceUsesThePersonalMinutes() {
        val f = lockedFlow()
        f.unlock("483926") // 10 minutes
        clock += 9 * minute; f.checkIdle()
        assertIs<AppState.Unlocked>(f.state.value)
        clock += minute; f.checkIdle()
        assertIs<AppState.Locked>(f.state.value)
    }

    @Test fun interactionRestartsTheIdleTime() {
        val f = lockedFlow()
        f.unlock("483926")
        clock += 9 * minute; f.userActive()
        clock += 9 * minute; f.checkIdle()
        assertIs<AppState.Unlocked>(f.state.value)
        clock += minute; f.checkIdle()
        assertIs<AppState.Locked>(f.state.value)
    }

    @Test fun lockingForgetsTheTokenStopsTheConnectionAndShowsTheLockScreen() {
        val f = lockedFlow()
        f.unlock("483926")
        val unlocked = assertIs<AppState.Unlocked>(f.state.value)
        assertTrue(f.canWrite.value)
        clock += 10 * minute; f.checkIdle()
        assertFalse(api.token)
        assertEquals(ConnectionStatus.OFFLINE, unlocked.connection.value)
        assertFalse(f.canWrite.value)
        assertEquals("alice", assertIs<AppState.Locked>(f.state.value).username)
        clock += 99 * minute; f.checkIdle() // already locked: nothing more happens
        assertIs<AppState.Locked>(f.state.value)
    }

    @Test fun comingBackAfterLongerThanTheTimeoutLocks() {
        val f = lockedFlow()
        f.unlock("483926")
        clock += 5 * minute; f.appResumed()
        assertIs<AppState.Unlocked>(f.state.value)
        clock += 6 * minute; f.appResumed()
        assertIs<AppState.Locked>(f.state.value)
    }

    @Test fun untilTheConfigAnswersTheServersDefaultsApply() {
        api.failConfig = ClientError.Unreachable()
        val shared = lockedFlow(storedShared())
        shared.selectUser("alice"); shared.unlock("111111")
        clock += 3 * minute; shared.checkIdle()
        assertIs<AppState.Locked>(shared.state.value)
    }

    @Test fun lockNowWorksFromTheUnlockedScreenAndSettings() {
        val f = lockedFlow()
        f.unlock("483926"); f.lockNow()
        assertIs<AppState.Locked>(f.state.value)
        f.unlock("483926"); f.openSettings(); f.lockNow()
        assertIs<AppState.Locked>(f.state.value)
        assertEquals(2, api.locks)
    }

    @Test fun anAuthorizedCallThatFindsTheTokenGoneLocksWithTheReason() {
        val f = lockedFlow()
        f.unlock("483926"); f.openSettings()
        api.failDevices = ClientError.Locked()
        f.loadDevices()
        assertEquals(Problem.SessionEnded, assertIs<AppState.Locked>(f.state.value).problem)
    }

    // ---- one user who needs no PIN ----

    /** The device holds alice alone, and the last config said her credential alone unlocks. */
    private fun withoutPinFlow(mode: DeviceMode = DeviceMode.PERSONAL): AppFlow {
        api.unlockWithoutPin = true
        return lockedFlow(storedAlice(mode).copy(unlockWithoutPin = true))
    }

    private val credentialOnly = UnlockRequest("dev-1", "alice", "cred")

    @Test fun oneUserWhoNeedsNoPinOpensUnlockedWithTheCredentialAlone() {
        for (mode in DeviceMode.entries) {
            api.unlocks.clear()
            val f = withoutPinFlow(mode)
            assertEquals("alice", assertIs<AppState.Unlocked>(f.state.value).username, "$mode")
            assertEquals(listOf(credentialOnly), api.unlocks, "no PIN is sent ($mode)")
            assertNull(f.reauth.value)
        }
    }

    @Test fun oneUserWhoNeedsNoPinStaysUnlockedWhenIdleAndTheNextCallGetsANewTokenWithoutAPin() {
        val f = withoutPinFlow()
        clock += 99 * minute; f.checkIdle()
        assertIs<AppState.Unlocked>(f.state.value)
        assertFalse(api.token, "the token is dropped")
        assertNotNull(f.workspace)
        f.openSettings() // loads the device list
        assertEquals(listOf(credentialOnly, credentialOnly), api.unlocks)
        assertEquals(listOf("dev-1", "dev-2"), assertIs<AppState.Settings>(f.state.value).devices?.map { it.id })
        f.closeSettings()
        clock += 99 * minute; f.appResumed()
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun oneUserWhoNeedsNoPinGetsANewTokenWhenTheServerEndedTheOldOne() {
        val f = withoutPinFlow(); f.openSettings()
        api.devicesErrors += ClientError.Locked() // the access token ran out
        f.loadDevices()
        val s = assertIs<AppState.Settings>(f.state.value)
        assertEquals(listOf("dev-1", "dev-2"), s.devices?.map { it.id })
        assertNull(s.problem)
        assertEquals(listOf(credentialOnly, credentialOnly), api.unlocks)
    }

    @Test fun oneUserWhoNeedsNoPinGetsANewTokenWhenTheSocketFindsTheSessionGone() {
        val f = withoutPinFlow()
        assertTrue(f.canWrite.value)
        val drop = CompletableDeferred<Unit>().also { api.dropSocket = it }
        api.wsLockedOnce = true
        drop.complete(Unit)
        awaitTrue("a new token") { api.unlocks.size == 2 && f.canWrite.value }
        assertIs<AppState.Unlocked>(f.state.value)
        assertEquals(credentialOnly, api.unlocks.last())
    }

    @Test fun aTokenTheServerKeepsEndingLocksOnceAndDoesNotLoop() {
        val f = withoutPinFlow(); f.openSettings()
        api.devicesErrors += listOf(ClientError.Locked(), ClientError.Locked()) // the new token is refused too
        f.loadDevices()
        assertEquals(Problem.SessionEnded, assertIs<AppState.Locked>(f.state.value).problem)
        assertEquals(2, api.unlocks.size, "one new token, then the lock screen")
    }

    @Test fun oneUserWithoutTheServersLeaveIsLockedAndThePinUnlocksAsBefore() {
        api.unlockWithoutPin = true // the server allows it, but this device has not read that yet
        val f = lockedFlow(storedAlice(DeviceMode.SHARED))
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertTrue(api.unlocks.isEmpty())
        f.selectUser("alice")
        f.unlock("483926")
        assertIs<AppState.Unlocked>(f.state.value)
        assertEquals(UnlockRequest("dev-1", "alice", "cred", pin = "483926"), api.unlocks.single())
        assertEquals(listOf("alice"), locked.users)
    }

    @Test fun aRefusedCredentialOnlyUnlockShowsThatUsersLockScreenOnceWithTheReason() {
        api.failUnlock = ClientError.Api(401, ErrorCode.UNAUTHORIZED, "Unlock failed.")
        val f = withoutPinFlow(DeviceMode.SHARED)
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertEquals("alice", locked.username, "the PIN field is shown for her")
        assertEquals(Problem.WrongCredentials, locked.problem)
        assertEquals(listOf(credentialOnly), api.unlocks)
        assertFalse(store.stored!!.unlockWithoutPin, "the next start asks for the PIN rather than trying again")
        clock += 99 * minute; f.checkIdle()
        assertEquals(1, api.unlocks.size, "no loop")
        api.failUnlock = null
        f.unlock("483926")
        assertIs<AppState.Unlocked>(f.state.value)
        assertEquals("483926", api.unlocks.last().pin)
        assertTrue(store.stored!!.unlockWithoutPin, "the config read after the unlock turns it on again")
    }

    @Test fun anUnreachableServerShowsTheLockScreenAndKeepsTheSetting() {
        api.failUnlock = ClientError.Unreachable()
        val f = withoutPinFlow()
        assertEquals(Problem.Unreachable, assertIs<AppState.Locked>(f.state.value).problem)
        assertTrue(store.stored!!.unlockWithoutPin)
    }

    @Test fun whenTheServerAsksForThePasswordTheDeviceStopsTryingWithoutIt() {
        api.failUnlock = ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "Enter your password to unlock.", passwordRequired = true)
        val f = withoutPinFlow()
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertTrue(locked.needsPassword)
        assertNull(locked.problem)
        assertFalse(store.stored!!.unlockWithoutPin)
    }

    @Test fun aRefusedNewTokenWhileUnlockedShowsTheLockScreenOnce() {
        val f = withoutPinFlow(); f.openSettings()
        api.failUnlock = ClientError.Api(401, ErrorCode.UNAUTHORIZED, "Unlock failed.")
        api.devicesErrors += ClientError.Locked()
        f.loadDevices()
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertEquals(Problem.WrongCredentials, locked.problem)
        assertEquals("alice", locked.username)
        assertEquals(2, api.unlocks.size)
        assertNull(f.workspace)
    }

    @Test fun twoUsersLockWithThePickerWhenIdleAndUnlockWithThePin() {
        api.unlockWithoutPin = true
        val f = lockedFlow(storedShared().copy(unlockWithoutPin = true))
        assertNull(assertIs<AppState.Locked>(f.state.value).username, "the picker")
        assertTrue(api.unlocks.isEmpty())
        f.selectUser("alice"); f.unlock("111111")
        assertEquals("111111", api.unlocks.single().pin)
        clock += 2 * minute; f.checkIdle() // the shared minutes
        val locked = assertIs<AppState.Locked>(f.state.value)
        assertNull(locked.username)
        assertFalse(api.token)
    }

    @Test fun theIdleTimeFollowsHowManyUsersTheDeviceHoldsNotItsMode() {
        val f = lockedFlow(storedAlice(DeviceMode.SHARED)) // a login makes a shared device with one user
        f.selectUser("alice"); f.unlock("483926")
        clock += 9 * minute; f.checkIdle()
        assertIs<AppState.Unlocked>(f.state.value, "the personal minutes (10), not the shared ones (2)")
        clock += minute; f.checkIdle()
        assertIs<AppState.Locked>(f.state.value)
    }

    @Test fun withoutAPinOnlyTheServersRequestAsksForItBeforeADeleteAndExportAsksNothing() {
        api.records.configBranches = listOf("main")
        api.records.listed = listOf(testEntry())
        val f = withoutPinFlow()
        val ws = assertNotNull(f.workspace)
        ws.export.export()
        assertEquals(1, api.records.exports.size)
        assertNull(f.reauth.value, "export asks for nothing")
        ws.history.reload()
        ws.history.open(ws.history.ui.value.entries!!.single())
        val detail = assertNotNull(ws.history.detail.value)
        detail.askDelete()
        assertNull(f.reauth.value, "nothing is asked before the server does")
        api.records.changeError = ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "Enter your PIN again.")
        detail.confirmDelete()
        assertEquals(ReauthPrompt(needsPassword = false), f.reauth.value)
        api.records.changeError = null
        f.submitReauth("483926")
        assertEquals(ReauthRequest(pin = "483926"), api.reauths.single())
        assertNull(f.reauth.value)
        assertEquals(listOf("e1", "e1"), api.records.deletes, "tried once more after the PIN")
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun theConfigKeepsWhetherThePinIsNeededAndAnOldFileReadsAsNeedingIt() {
        val f = lockedFlow()
        assertFalse(store.stored!!.unlockWithoutPin)
        api.unlockWithoutPin = true
        f.unlock("483926")
        assertTrue(store.stored!!.unlockWithoutPin)
        api.unlockWithoutPin = false
        f.workspace!!.retry() // the config is read again
        assertFalse(store.stored!!.unlockWithoutPin)
        val old = """{"servers":[{"serverId":"s","name":"n","certPin":"p","endpoints":["h:1"],"deviceId":"d","users":[{"username":"a","credential":"c"}]}]}"""
        assertFalse(xyz.felismp.shoparchive.app.client.decodeServers(old.toByteArray()).servers.single().unlockWithoutPin)
    }

    // ---- re-auth ----

    @Test fun reauthPromptThenRetriesTheActionOnce() {
        val f = lockedFlow(); f.unlock("483926")
        var calls = 0
        val result = scope.async { f.withReauth { if (++calls == 1) throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") else "done" } }
        assertEquals(ReauthPrompt(needsPassword = false), f.reauth.value)
        assertFalse(result.isCompleted)
        f.submitReauth("483926")
        assertEquals(ReauthRequest(pin = "483926"), api.reauths.single())
        assertNull(f.reauth.value)
        assertEquals("done", result.getCompleted())
        assertEquals(2, calls)
    }

    @Test fun reauthAsksForThePasswordWhenTheServerSaysSo() {
        val f = lockedFlow(); f.unlock("483926")
        val result = scope.async { f.withReauth { if (api.reauths.isEmpty()) throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") else 1 } }
        api.failReauth = ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "password", passwordRequired = true)
        f.submitReauth("483926")
        assertEquals(ReauthPrompt(needsPassword = true), f.reauth.value)
        api.failReauth = null
        f.submitReauth("a long password")
        assertEquals(ReauthRequest(password = "a long password"), api.reauths.last())
        assertEquals(1, result.getCompleted())
    }

    @Test fun aWrongPinKeepsThePromptWithAMessage() {
        val f = lockedFlow(); f.unlock("483926")
        val result = scope.async { f.withReauth { if (api.reauths.size < 2) throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") else "ok" } }
        api.failReauth = ClientError.Api(401, ErrorCode.UNAUTHORIZED, "Wrong PIN or password.")
        f.submitReauth("000000")
        assertEquals(Problem.WrongCredentials, f.reauth.value?.problem)
        assertFalse(f.reauth.value!!.busy)
        assertFalse(result.isCompleted)
        api.failReauth = ClientError.Api(429, ErrorCode.RATE_LIMITED, "slow", retryAfterSeconds = 20)
        f.submitReauth("000000")
        assertEquals(Problem.TooManyAttempts(20), f.reauth.value?.problem)
        api.failReauth = null
        f.submitReauth("483926")
        assertEquals("ok", result.getCompleted())
    }

    @Test fun cancellingThePromptFailsTheActionWithoutRunningItAgain() {
        val f = lockedFlow(); f.unlock("483926")
        var calls = 0
        val result = scope.async { f.withReauth { calls++; throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") } }
        f.cancelReauth()
        assertNull(f.reauth.value)
        assertIs<ClientError.ReauthCancelled>(result.getCompletionExceptionOrNull())
        assertEquals(1, calls)
    }

    @Test fun otherErrorsAndASecondReauthRequestPassThrough() {
        val f = lockedFlow(); f.unlock("483926")
        val notReauth = scope.async { f.withReauth { throw ClientError.Api(403, ErrorCode.FORBIDDEN, "no") } }
        assertEquals(ErrorCode.FORBIDDEN, (notReauth.getCompletionExceptionOrNull() as ClientError.Api).code)
        assertNull(f.reauth.value)
        val twice = scope.async { f.withReauth { throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") } }
        f.submitReauth("483926")
        assertIs<ClientError.Api>(twice.getCompletionExceptionOrNull(), "asked once, then the error is the caller's")
        assertNull(f.reauth.value)
    }

    @Test fun theWorkspaceAndItsDraftEndWithTheLock() {
        val f = lockedFlow()
        assertNull(f.workspace)
        f.unlock("483926")
        val first = assertNotNull(f.workspace)
        first.record.setNote("half written")
        assertEquals("half written", first.record.ui.value.draft.note)
        f.openSettings(); f.closeSettings()
        assertTrue(f.workspace === first) // Settings and back keep the draft
        f.lockNow()
        assertNull(f.workspace)
        f.unlock("483926")
        val second = assertNotNull(f.workspace)
        assertTrue(first !== second)
        assertEquals("", second.record.ui.value.draft.note)
    }

    @Test fun theBranchChosenIsRememberedOnThisDeviceAndTheLanguageIsKept() {
        prefs.saved = Preferences(language = "lo")
        api.records.configBranches = listOf("main", "second")
        val f = lockedFlow()
        f.unlock("483926")
        f.workspace!!.selectBranch("second")
        assertEquals(Preferences(language = "lo", branch = "second"), prefs.saved)
        f.setLanguage("th")
        assertEquals(Preferences(language = "th", branch = "second"), prefs.saved)
    }

    @Test fun lockingWhileThePromptIsOpenEndsIt() {
        val f = lockedFlow(); f.unlock("483926")
        val result = scope.async { f.withReauth { throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") } }
        f.lockNow()
        assertNull(f.reauth.value)
        assertIs<ClientError.Locked>(result.getCompletionExceptionOrNull())
    }

    // ---- settings ----

    @Test fun settingsListsTheUsersDevices() {
        val f = lockedFlow(); f.unlock("483926"); f.openSettings()
        val s = assertIs<AppState.Settings>(f.state.value)
        assertEquals(listOf("dev-1", "dev-2"), s.devices?.map { it.id })
        f.closeSettings()
        assertIs<AppState.Unlocked>(f.state.value)
    }

    @Test fun revokingAnotherDeviceAsksForReauthThenRefreshesTheList() {
        val f = lockedFlow(); f.unlock("483926"); f.openSettings()
        api.revokeErrors += ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again")
        f.revokeDevice("dev-2")
        assertEquals(ReauthPrompt(needsPassword = false), f.reauth.value)
        assertTrue(api.revoked.isEmpty())
        api.devicesList = api.devicesList.take(1)
        f.submitReauth("483926")
        assertEquals(listOf("dev-2"), api.revoked)
        val s = assertIs<AppState.Settings>(f.state.value)
        assertFalse(s.busy)
        assertEquals(listOf("dev-1"), s.devices?.map { it.id })
    }

    @Test fun thisDeviceCannotBeRevokedFromSettings() {
        val f = lockedFlow(); f.unlock("483926"); f.openSettings()
        f.revokeDevice("dev-1")
        assertTrue(api.revoked.isEmpty())
    }

    @Test fun aCancelledRevokeShowsWhatHappened() {
        val f = lockedFlow(); f.unlock("483926"); f.openSettings()
        api.revokeErrors += ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again")
        f.revokeDevice("dev-2")
        f.cancelReauth()
        val s = assertIs<AppState.Settings>(f.state.value)
        assertEquals(Problem.ReauthCancelled, s.problem)
        assertFalse(s.busy)
        assertTrue(api.revoked.isEmpty())
    }

    @Test fun languageIsAppliedAndRemembered() {
        val f = flow()
        assertNull(f.language.value)
        f.setLanguage("th")
        assertEquals("th", f.language.value)
        assertEquals(Preferences("th"), prefs.saved)
        f.setLanguage(null)
        assertEquals(Preferences(null), prefs.saved)
    }

    @Test fun savedLanguageIsUsedFromTheStart() {
        prefs.saved = Preferences("lo")
        assertEquals("lo", flow().language.value)
    }

    @Test fun removingTheOnlyServerDeletesTheCredentialsAndReturnsToTheServerList() {
        val f = lockedFlow(storedShared()); f.selectUser("alice"); f.unlock("111111"); f.openSettings()
        f.removeServer()
        assertNull(store.all)
        assertTrue(assertIs<AppState.Servers>(f.state.value).saved.isEmpty())
        assertTrue(api.closed)
        assertFalse(api.token)
    }

    // ---- offline ----

    @Test fun canWriteFollowsTheLiveConnection() {
        val f = lockedFlow()
        assertFalse(f.canWrite.value)
        f.unlock("483926")
        assertTrue(f.canWrite.value)
        f.lockNow()
        assertFalse(f.canWrite.value)
        api.wsFails = true
        f.unlock("483926")
        assertEquals(ConnectionStatus.OFFLINE, assertIs<AppState.Unlocked>(f.state.value).connection.value)
        assertFalse(f.canWrite.value)
    }

    @Test fun aSocketThatFindsTheSessionGoneLocksWithTheReason() {
        val f = lockedFlow()
        f.unlock("483926")
        assertTrue(f.canWrite.value)
        val drop = CompletableDeferred<Unit>().also { api.dropSocket = it }
        api.wsLocked = true // the server restarted: the reconnect is refused
        drop.complete(Unit)
        awaitTrue("the lock screen") { f.state.value is AppState.Locked } // the live loop waits a second before trying again
        assertEquals(Problem.SessionEnded, assertIs<AppState.Locked>(f.state.value).problem)
        assertFalse(f.canWrite.value)
    }

    // ---- review fixes ----

    private fun addCarol(f: AppFlow) {
        f.addUserByPairing()
        api.redeemResponse = RedeemResponse("tok", "carol", passwordRequired = false, hasPassword = false, hasPin = false, pinLength = 6)
        f.previewLink(link()); f.redeemLink()
    }

    private fun names(c: StoredServer?) = c?.users?.map { it.username }

    @Test fun addUserFallsBackToTheNextUsersCredentialAndPrefersTheLastToUnlock() {
        val f = lockedFlow(storedShared())
        f.selectUser("bob"); f.unlock("222222"); f.lockNow()
        api.badDeviceCredentials += "cred-bob" // the server does not recognize bob's credential
        addCarol(f)
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(listOf("cred-bob", "cred"), api.enrolled.map { it.second.deviceCredential }, "bob unlocked last, so he is tried first")
        assertEquals("carol", assertIs<AppState.Unlocked>(f.state.value).username)
        assertEquals(listOf("alice", "bob", "carol"), names(store.stored), "nobody is deleted for that answer")
        assertEquals(listOf(false, true, false), store.stored?.users?.map { it.rejected }, "bob is marked, to be tried last")
    }

    @Test fun aRejectedCredentialIsTriedLastNextTimeSoItCannotUseUpTheEnrollmentsTries() {
        val f = lockedFlow(storedShared())
        f.selectUser("bob"); f.unlock("222222"); f.lockNow()
        api.badDeviceCredentials += "cred-bob"
        addCarol(f)
        f.enroll(EnrollInput(pin = WRONG_PIN, pinRepeat = WRONG_PIN)) // bob's credential is refused, alice's gets as far as the wrong PIN
        assertEquals(listOf("cred-bob", "cred"), api.enrolled.map { it.second.deviceCredential })
        assertEquals(Problem.WrongCredentials, assertIs<AppState.Enroll>(f.state.value).problem)
        assertEquals(listOf("alice", "bob"), names(store.stored))
        assertTrue(store.stored!!.users.single { it.username == "bob" }.rejected)
        api.enrolled.clear()
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(listOf("cred"), api.enrolled.map { it.second.deviceCredential }, "alice, who is accepted, goes first now")
        assertEquals("carol", assertIs<AppState.Unlocked>(f.state.value).username)
        assertEquals(listOf("alice", "bob", "carol"), names(store.stored))
    }

    @Test fun aUserDisabledForAWhileKeepsTheirCredentialAndCanUnlockAgainAfterBeingEnabled() {
        val f = lockedFlow(storedShared())
        api.badDeviceCredentials += "cred-bob" // bob is disabled on the server
        f.selectUser("alice"); f.unlock("111111"); f.lockNow() // alice is the last to unlock, so bob is tried second
        api.badDeviceCredentials += "cred" // and alice too, so every credential is refused and the answer was about the users
        addCarol(f)
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(listOf("alice", "bob"), names(store.stored), "nothing is forgotten")
        assertTrue(store.stored!!.users.all { it.rejected })
        api.badDeviceCredentials.clear() // bob is enabled again
        f.cancelAddUser()
        f.selectUser("bob"); f.unlock("222222")
        assertEquals("bob", assertIs<AppState.Unlocked>(f.state.value).username)
        assertEquals("cred-bob", api.unlocks.last().credential, "the stored credential still works")
        assertFalse(store.stored!!.users.single { it.username == "bob" }.rejected, "a successful unlock clears the mark")
        assertTrue(store.stored!!.users.single { it.username == "alice" }.rejected)
    }

    @Test fun aRejectedUserIsStillOnTheLockScreen() {
        val f = lockedFlow(storedShared())
        api.badDeviceCredentials += "cred" // alice is refused
        addCarol(f)
        f.enroll(EnrollInput(pin = WRONG_PIN, pinRepeat = WRONG_PIN))
        f.startOver(); f.cancelPreview()
        f.cancelAddUser()
        assertEquals(listOf("alice", "bob"), assertIs<AppState.Locked>(f.state.value).users)
    }

    @Test fun anUnknownDeviceWithManyUsersForgetsNobodyEvenWhenTheFifthTryIsRefusedForTooManyTries() {
        val users = (1..6).map { StoredUser("user$it", "cred$it") }
        val f = lockedFlow(storedShared().copy(users = users))
        api.badDeviceCredentials += users.map { it.credential }
        addCarol(f)
        api.failEnroll = null
        // The fifth try hits the enrollment's limit: the server stops with a different answer than "not recognized".
        val counting = object : ServerApi by api {
            override suspend fun enroll(enrollmentToken: String, request: EnrollRequest): EnrollResponse {
                if (api.enrolled.size >= 4) { api.enrolled += enrollmentToken to request; throw ClientError.Api(401, ErrorCode.UNAUTHORIZED, "Too many wrong tries.") }
                return api.enroll(enrollmentToken, request)
            }
        }
        val g = AppFlow(scope, store, ThisDevice("Test PC", "windows"), { _, _, eps -> counting.also { api.endpoints = eps } }, { PIN_FP }, prefs, { clock }, Dispatchers.Unconfined)
        g.addUserByPairing()
        g.previewLink(link()); g.redeemLink()
        g.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(users.map { it.username }, names(store.stored), "all six are still stored")
        assertEquals(4, store.stored!!.users.count { it.rejected }, "the four refused are tried last next time")
    }

    @Test fun anOldCredentialFileWithoutTheMarkStillReads() {
        val old = """{"deviceId":"d","serverId":"s","certPin":"p","endpoints":["h:1"],"users":[{"username":"a","credential":"c"}]}"""
        val read = xyz.felismp.shoparchive.app.client.decodeServers(old.toByteArray())
        assertFalse(read.servers.single().users.single().rejected)
    }

    @Test fun addUserWithEveryCredentialRejectedSaysWhatToDo() {
        val f = lockedFlow(storedShared())
        api.badDeviceCredentials += setOf("cred", "cred-bob")
        addCarol(f)
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(2, api.enrolled.size)
        val s = assertIs<AppState.Enroll>(f.state.value)
        assertEquals(Problem.DeviceRejected, s.problem)
        assertFalse(s.busy)
        assertEquals(listOf("alice", "bob"), names(store.stored), "the device itself is unknown: nobody is forgotten")
    }

    @Test fun addUserWithAWrongPinStopsAfterOneRequestAndIsNotBlamedOnTheDevice() {
        val f = lockedFlow(storedShared())
        addCarol(f)
        f.enroll(EnrollInput(pin = WRONG_PIN, pinRepeat = WRONG_PIN))
        assertEquals(1, api.enrolled.size, "a wrong PIN is not a reason to try the next credential")
        val s = assertIs<AppState.Enroll>(f.state.value)
        assertEquals(Problem.WrongCredentials, s.problem)
        assertFalse(s.busy)
    }

    @Test fun addUserMovesOnOnlyWhenTheDeviceCredentialIsNotRecognized() {
        val f = lockedFlow(storedShared())
        api.badDeviceCredentials += "cred" // alice was taken off; bob (second after the sort) still works
        addCarol(f)
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(listOf("cred", "cred-bob"), api.enrolled.map { it.second.deviceCredential })
        assertEquals("carol", assertIs<AppState.Unlocked>(f.state.value).username)
    }

    @Test fun anEnrollmentThatRanOutOnTheServerGoesBackToPairingAndKeepsAddingAUser() {
        val f = lockedFlow(storedShared())
        addCarol(f)
        api.failEnroll = ClientError.Api(401, ErrorCode.ENROLLMENT_EXPIRED, "The pairing has expired.")
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(1, api.enrolled.size, "the tries stop at once")
        val s = assertIs<AppState.Pair>(f.state.value)
        assertEquals(Problem.EnrollmentExpired, s.problem)
        assertTrue(s.adding, "still adding a user to this shared device")
        assertTrue(api.closed, "the opened session is closed")
    }

    @Test fun anEnrollmentThatRanOutWhileSettingUpANewDeviceGoesBackToPairing() {
        val f = flow()
        f.enrolling()
        api.failEnroll = ClientError.Api(401, ErrorCode.ENROLLMENT_EXPIRED, "The pairing has expired.")
        f.enroll(good)
        val s = assertIs<AppState.Pair>(f.state.value)
        assertEquals(Problem.EnrollmentExpired, s.problem)
        assertFalse(s.adding)
    }

    // ---- the countdown ----

    private var monotonic = 0L

    private fun countingFlow(seconds: Long): AppFlow {
        api.redeemResponse = api.redeemResponse.copy(expiresInSeconds = seconds)
        return AppFlow(
            scope, store, ThisDevice("Test PC", "windows"),
            connect = { pin, sid, eps -> connected += Triple(pin, sid, eps); api.also { it.endpoints = eps } },
            probe = { probed = it; probeResult() },
            prefs = prefs, now = { clock }, io = Dispatchers.Unconfined, monotonicMs = { monotonic },
        )
    }

    @Test fun theCountdownRunsFromTheSecondsTheServerSaidOnItsOwnClock() {
        monotonic = 5_000_000L // the device clock may read anything
        val f = countingFlow(600)
        f.enrolling()
        assertEquals(600L, f.enrollSecondsLeft())
        monotonic += 61_500
        assertEquals(539L, f.enrollSecondsLeft(), "rounded up to a whole second")
        assertEquals("8:59", formatCountdown(539))
        assertEquals("0:05", formatCountdown(5))
    }

    @Test fun withoutSecondsFromTheServerThereIsNoCountdown() {
        val f = countingFlow(0)
        f.enrolling()
        monotonic += 10_000_000
        assertNull(f.enrollSecondsLeft())
        f.enroll(good)
        assertEquals(1, api.enrolled.size, "nothing is blocked when the time is unknown")
    }

    @Test fun whenTheCountdownReachesZeroSubmitSendsNothingAndPairingStartsAgain() {
        val f = countingFlow(120)
        f.enrolling()
        monotonic += 119_000
        assertEquals(1L, f.enrollSecondsLeft())
        monotonic += 1_000
        assertEquals(0L, f.enrollSecondsLeft())
        f.enroll(good)
        assertTrue(api.enrolled.isEmpty(), "nothing is sent")
        assertEquals(Problem.EnrollmentExpired, assertIs<AppState.Pair>(f.state.value).problem)
        assertNull(f.enrollSecondsLeft())
    }

    @Test fun addUserStopsOnANonCredentialError() {
        val f = lockedFlow(storedShared())
        addCarol(f)
        api.failEnroll = ClientError.Unreachable()
        f.enroll(EnrollInput(pin = "654321", pinRepeat = "654321"))
        assertEquals(1, api.enrolled.size)
        assertEquals(Problem.Unreachable, assertIs<AppState.Enroll>(f.state.value).problem)
    }

    @Test fun aRevokeStartedByAliceNeverPromptsOrRetriesForBob() {
        val f = lockedFlow(storedShared())
        f.selectUser("alice"); f.unlock("111111"); f.openSettings()
        val gate = CompletableDeferred<Unit>()
        api.revokeGate = gate
        api.revokeErrors += ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again")
        f.revokeDevice("dev-2")
        f.lockNow()
        f.selectUser("bob"); f.unlock("222222")
        gate.complete(Unit) // Alice's answer arrives now
        assertNull(f.reauth.value, "no prompt for Bob")
        assertTrue(api.revoked.isEmpty(), "no retry")
        assertTrue(api.token_at_revoke.size <= 1, "at most the first call, which the lock cancels; never a retry")
        assertEquals("bob", assertIs<AppState.Unlocked>(f.state.value).username)
    }

    /** Alice confirms her re-auth, locks before the answer, Bob unlocks and starts his own prompt: Alice's late answer touches nothing of Bob's. */
    private fun aliceLateReauthAnswer(answer: Exception?) {
        val f = lockedFlow(storedShared())
        f.selectUser("alice"); f.unlock("111111")
        scope.async { runCatching { f.withReauth { if (api.reauths.isEmpty()) throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") else "alice" } } }
        val gate = CompletableDeferred<Unit>()
        api.reauthGate = gate
        api.failReauth = answer
        f.submitReauth("111111")
        f.lockNow()
        f.selectUser("bob"); f.unlock("222222")
        api.reauthGate = null
        var bobRuns = 0
        val bob = scope.async { runCatching { f.withReauth { bobRuns++; if (bobRuns == 1) throw ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "again") else "bob" } } }
        val bobPrompt = assertNotNull(f.reauth.value)
        gate.complete(Unit) // Alice's answer arrives now
        assertEquals(bobPrompt, f.reauth.value, "Bob's prompt is untouched")
        assertEquals(1, bobRuns, "Bob's action is not retried without his confirmation")
        assertTrue(bob.isActive)
    }

    @Test fun aliceLateReauthSuccessDoesNotConfirmBobsPrompt() = aliceLateReauthAnswer(null)

    @Test fun aliceLatePasswordRequestDoesNotChangeBobsPrompt() =
        aliceLateReauthAnswer(ClientError.Api(401, ErrorCode.REAUTH_REQUIRED, "password", passwordRequired = true))

    @Test fun aliceLateDeviceListNeverShowsInBobsSettings() {
        val f = lockedFlow(storedShared())
        f.selectUser("alice"); f.unlock("111111")
        val gate = CompletableDeferred<Unit>()
        api.devicesGate = gate
        val alices = api.devicesList
        f.openSettings() // Alice's list is on its way
        f.lockNow()
        api.devicesGate = null
        api.devicesList = alices.take(1).map { it.copy(label = "Bob's till") }
        f.selectUser("bob"); f.unlock("222222"); f.openSettings()
        gate.complete(Unit) // Alice's answer arrives now
        assertEquals(listOf("Bob's till"), assertIs<AppState.Settings>(f.state.value).devices?.map { it.label })
    }

    @Test fun aliceLateFailureNeverLocksBob() {
        val f = lockedFlow(storedShared())
        f.selectUser("alice"); f.unlock("111111")
        val gate = CompletableDeferred<Unit>()
        api.devicesGate = gate
        api.failDevices = ClientError.Locked()
        f.openSettings()
        f.lockNow()
        api.devicesGate = null
        api.failDevices = null
        f.selectUser("bob"); f.unlock("222222")
        gate.complete(Unit) // Alice's 401 arrives now
        assertEquals("bob", assertIs<AppState.Unlocked>(f.state.value).username)
    }

    @Test fun aLockWhileTheServerIsBeingRemovedWaitsForTheRemoval() {
        lateinit var f: AppFlow
        val removing = object : CredentialStore {
            var stored: StoredServers? = StoredServers(listOf(storedAlice()))
            override fun load() = stored
            override fun save(servers: StoredServers) { stored = servers }
            override fun clear() { f.lockNow(); stored = null } // the auto-lock fires while the sign-ins are being deleted
        }
        f = AppFlow(
            scope, removing, ThisDevice("Test PC", "windows"), connect = { _, _, _ -> api }, probe = { PIN_FP },
            prefs = prefs, now = { clock }, io = Dispatchers.Unconfined,
        )
        f.unlock("111111"); f.openSettings()
        f.removeServer()
        assertIs<AppState.Servers>(f.state.value)
        assertNull(removing.stored)
    }

    @Test fun aSecondRemovalWhileTheFirstRunsIsIgnored() {
        lateinit var f: AppFlow
        var clears = 0
        val removing = object : CredentialStore {
            var stored: StoredServers? = StoredServers(listOf(storedAlice()))
            override fun load() = stored
            override fun save(servers: StoredServers) { stored = servers }
            override fun clear() {
                clears++
                f.closeSettings(); f.openSettings(); f.removeServer() // Back, Settings, Remove again while the first one runs
                throw java.io.IOException("disk busy")
            }
        }
        f = AppFlow(
            scope, removing, ThisDevice("Test PC", "windows"), connect = { _, _, _ -> api }, probe = { PIN_FP },
            prefs = prefs, now = { clock }, io = Dispatchers.Unconfined,
        )
        f.unlock("111111"); f.openSettings()
        f.removeServer()
        assertEquals(1, clears)
        assertNotNull(removing.stored)
    }
}
