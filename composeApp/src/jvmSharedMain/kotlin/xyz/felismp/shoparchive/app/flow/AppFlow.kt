package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.client.CredentialStore
import xyz.felismp.shoparchive.app.client.FoundServer
import xyz.felismp.shoparchive.app.client.LiveConnection
import xyz.felismp.shoparchive.app.client.Preferences
import xyz.felismp.shoparchive.app.client.PreferencesStore
import xyz.felismp.shoparchive.app.client.NoDiscovery
import xyz.felismp.shoparchive.app.client.ServerApi
import xyz.felismp.shoparchive.app.client.ServerDiscovery
import xyz.felismp.shoparchive.app.client.mergeEndpoints
import xyz.felismp.shoparchive.app.client.rememberEndpoints
import xyz.felismp.shoparchive.app.client.StoredServer
import xyz.felismp.shoparchive.app.client.StoredServers
import xyz.felismp.shoparchive.app.client.StoredUser
import xyz.felismp.shoparchive.app.client.fingerprintToPin
import xyz.felismp.shoparchive.app.client.parsePairLink
import xyz.felismp.shoparchive.shared.AuthPolicy
import xyz.felismp.shoparchive.shared.ConfigResponse
import xyz.felismp.shoparchive.shared.DeviceMode
import xyz.felismp.shoparchive.shared.EnrollResponse
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.LoginRequest
import xyz.felismp.shoparchive.shared.PROTOCOL_VERSION
import xyz.felismp.shoparchive.shared.AppVersion
import xyz.felismp.shoparchive.shared.ReauthRequest
import xyz.felismp.shoparchive.shared.RedeemRequest
import xyz.felismp.shoparchive.shared.RedeemResponse
import xyz.felismp.shoparchive.shared.UnlockRequest
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What this device calls itself to the server: [label] is only the form's default, [platform] goes in the enroll request. */
class ThisDevice(val label: String, val platform: String)

/** What the server's own defaults are, used until `GET /config` has answered. */
private const val FALLBACK_LOCK_SHARED_MINUTES = 3
private const val FALLBACK_LOCK_PERSONAL_MINUTES = 15
private const val IDLE_CHECK_MS = 5_000L

/** The live socket searches for a moved server at most this often: the server may be off for a while, and a search is not free. An unlock the person starts always searches once. */
private const val LIVE_SEARCH_COOLDOWN_MS = 60_000L

/** How long to listen for the server's mDNS announcement once none of its saved addresses answers; a LAN answers in well under a second, so this only bounds the wait when it is not there. */
private val DISCOVERY_TIMEOUT: Duration = 4.seconds

/** How long the server list listens for servers announcing themselves on the local network. */
private val BROWSE_TIMEOUT: Duration = 3.seconds

/**
 * The app's flow: Starting, then the server list (Servers), or straight to Locked when this device holds one server. Opening a
 * server shows Locked (it has users here) or Login (it has none yet), then Unlocked and Settings. A server added by address is
 * trusted on first use: its key is saved without asking. A changed key of a saved server lands on CertChanged, which the person cancels or trusts;
 * a changed protocol from any call lands on a blocking state. Screens read [state] and call the actions;
 * typed text stays in the screens and comes in as arguments. PINs, passwords and tokens are never stored here.
 * While unlocked the app reports interaction with [userActive]; no interaction for the server's auto-lock time locks it.
 */
class AppFlow(
    private val scope: CoroutineScope,
    private val store: CredentialStore,
    val device: ThisDevice,
    /** Builds the client for one server: pin, server id (blank when not known yet), `host:port` endpoints. */
    private val connect: (pin: String, serverId: String, endpoints: List<String>) -> ServerApi,
    /** Fingerprint of the certificate at `host:port`, in groups of four. */
    private val probe: suspend (address: String) -> String,
    private val prefs: PreferencesStore,
    /** Milliseconds; injected so tests can move time. */
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compressor: SlipCompressor = SlipCompressor { it },
    /** Milliseconds that only move forward, for counting down the enrollment; injected so tests can move time. A wrong wall clock does not matter. */
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Finds the server on the local network when none of its saved addresses answers (its address changed). */
    private val discovery: ServerDiscovery = NoDiscovery,
    /** What installs a newer app the server offers; null: this platform cannot, so no update is looked for. */
    private val updater: AppUpdater? = null,
    /** The version of this app, which a file in the server's `downloads/` has to beat. */
    val ownVersion: AppVersion = AppVersion(0, 0, 0),
) {
    private val _state = MutableStateFlow<AppState>(AppState.Starting)
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** The server being paired with, or the stored one. */
    private class Session(val api: ServerApi, val pin: String, val serverId: String, val endpoints: List<String>)

    private var session: Session? = null
    private var enrollmentToken: String? = null
    private var enrolledUser: String? = null
    private var manual: Pair<String, String>? = null // username, code of the manual pairing in progress
    private var live: LiveConnection? = null
    private var stored: StoredServer? = null // the server open now, as this device holds it
    private var all = StoredServers(emptyList()) // every server this device holds, as last read or written
    private var pairAddress: String? = null // the address the pairing form starts with, when the server is already known
    private var addingTo: StoredServer? = null // set while another user is being paired onto this shared device
    private var unlocked: AppState.Unlocked? = null // set from unlock to lock, also while Settings is shown
    private var lastActivity = 0L
    private var idleLimitMs = 0L
    private var watchJobs = emptyList<Job>()
    // Everything an unlocked user starts runs here; a lock cancels it all, so no late answer can land in the next user's session.
    private var sessionScope: CoroutineScope = newSessionScope()
    private var removing = false
    private val saveLock = Mutex() // the stored credentials are written, and removed, one at a time
    private var persisted: StoredServer? = null // what is on disk for sure; [stored] differing from it means a save is still owed
    private var enrollGen = 0 // changes when an enrollment is abandoned, so a late answer of the abandoned one is dropped
    private var lastLiveSearchMs: Long? = null // when the live socket last searched, to space the searches

    /** The screens' state while unlocked (also while Settings is shown); null from lock to the next unlock. */
    var workspace: Workspace? = null
        private set

    // Runs a screen's call: asks for the PIN again when the server wants it, and lets a changed key, protocol or ended session block or lock the app.
    private val calls = object : Calls {
        override suspend fun <T> run(block: suspend () -> T): T = try {
            withReauth(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            blockOr(e) { }
            throw e
        }
    }

    private fun newSessionScope() = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
    private var pendingReauth: CompletableDeferred<Unit>? = null
    private var generation = 0 // changes on every unlock and every end of a session, so an action cannot outlive the user who started it
    private var lastUnlocked: String? = null // who unlocked most recently, for choosing which stored credential to try first

    private val _canWrite = MutableStateFlow(false)

    /** True while unlocked and the live connection to the server is up: recording and opening the day need it. */
    val canWrite: StateFlow<Boolean> = _canWrite.asStateFlow()

    private val _reauth = MutableStateFlow<ReauthPrompt?>(null)

    /** Not null while an action waits for the PIN or password; the screen shows the prompt and calls [submitReauth] or [cancelReauth]. */
    val reauth: StateFlow<ReauthPrompt?> = _reauth.asStateFlow()

    private val _language = MutableStateFlow(try { prefs.load().language } catch (_: Exception) { null })

    /** `en`, `lo`, `th`, or null for the system's. */
    val language: StateFlow<String?> = _language.asStateFlow()

    init {
        scope.launch {
            val found = try {
                withContext(io) { store.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // unreadable store: start from an empty list
            }
            all = found ?: StoredServers(emptyList())
            // One server opens straight away, as before there was a list; none or several show the list.
            val only = all.servers.singleOrNull()
            if (only != null) open(only) else showList()
        }
    }

    /** Builds the client for what the device holds and shows the lock screen, or the login when nobody is on this device yet. */
    private fun resume(creds: StoredServer, readFromDisk: Boolean = true) {
        stored = creds
        // Only what was just read from disk is known to be there; a snapshot from memory must not hide a save that is still owed.
        if (readFromDisk) persisted = creds
        session = Session(connect(creds.certPin, creds.serverId, creds.endpoints), creds.certPin, creds.serverId, creds.endpoints)
        _state.value = if (creds.deviceId != null && creds.users.isNotEmpty()) lockedFor(creds)
        else AppState.Login(creds.endpoints.firstOrNull().orEmpty(), creds.name)
    }

    private fun lockedFor(creds: StoredServer, username: String? = null, needsPassword: Boolean = false, problem: Problem? = null): AppState.Locked {
        val names = creds.users.map { it.username }
        val shared = creds.mode == DeviceMode.SHARED
        // A personal device holds one user; a shared one waits for a pick.
        val chosen = if (shared) username?.takeIf { it in names } else names.firstOrNull()
        return AppState.Locked(creds.endpoints.firstOrNull().orEmpty(), names, shared, chosen, needsPassword, problem = problem)
    }

    private fun pair(
        preview: xyz.felismp.shoparchive.shared.PairPayload? = null,
        check: FingerprintCheck? = null,
        busy: Boolean = false,
        problem: Problem? = null,
    ) = AppState.Pair(preview, check, busy, problem, adding = addingTo != null, address = pairAddress.orEmpty())

    /**
     * Writes [server] into the list on disk in place of the one with its id (or after the others when it is new), as the last one opened.
     * Callers hold the save lock and run on [io].
     */
    private fun persist(server: StoredServer) {
        val others = all.servers
        val list = if (others.any { it.serverId == server.serverId }) others.map { if (it.serverId == server.serverId) server else it } else others + server
        val next = StoredServers(list, lastServerId = server.serverId)
        store.save(next)
        all = next
    }

    // ---- the server list ----

    private fun rows() = all.servers.map { s ->
        val endpoint = s.endpoints.firstOrNull().orEmpty()
        ServerRow(s.serverId, s.name.ifBlank { endpoint }, endpoint, s.users.size)
    }

    private fun showList(problem: Problem? = null) {
        _state.value = AppState.Servers(rows(), emptyList(), searching = false, problem = problem)
        refreshFound()
    }

    private fun servers(change: (AppState.Servers) -> AppState.Servers) {
        (_state.value as? AppState.Servers)?.let { _state.value = change(it) }
    }

    /** Looks on the local network again; servers already saved here are left out, since they are in the list above. */
    fun refreshFound() {
        val s = _state.value as? AppState.Servers ?: return
        if (s.searching) return
        _state.value = s.copy(searching = true)
        scope.launch {
            val found = try {
                discovery.browse(BROWSE_TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val saved = all.servers.map { it.serverId }.toSet()
            servers { it.copy(found = found.filter { f -> f.serverId !in saved }.distinctBy { f -> f.serverId }, searching = false) }
        }
    }

    /** Opens a saved server: its lock screen, or its login when nobody is on this device yet. It is remembered as the last one opened. */
    fun openServer(serverId: String) {
        val s = _state.value as? AppState.Servers ?: return
        if (s.busy) return
        val server = all.servers.firstOrNull { it.serverId == serverId } ?: return
        open(server)
        if (all.lastServerId == serverId) return
        scope.launch {
            try {
                withContext(io) {
                    saveLock.withLock {
                        val next = all.copy(lastServerId = serverId)
                        store.save(next)
                        all = next
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Only which server was opened last is not kept.
            }
        }
    }

    private fun open(server: StoredServer) = resume(server)

    /**
     * A server by `host:port`: its key is probed, and its `/info` read pinned to that key. A server not saved here yet is saved with that
     * key (trust on first use, no fingerprint to compare) and opened; a saved one with the same key is opened with this address kept; a saved
     * one with another key goes to [AppState.CertChanged], and nothing else is sent.
     */
    fun addServer(address: String) {
        val s = _state.value as? AppState.Servers ?: return
        if (s.busy) return
        val target = address.trim()
        if (!validAddress(target)) {
            _state.value = s.copy(problem = Problem.BadAddress)
            return
        }
        _state.value = s.copy(busy = true, problem = null)
        scope.launch {
            val fail = { p: Problem -> servers { it.copy(busy = false, problem = p) } }
            val fingerprint = guarded({ withContext(io) { probe(target) } }, fail) ?: return@launch
            if (_state.value !is AppState.Servers) return@launch
            val pin = fingerprintToPin(fingerprint)
            val api = connect(pin, "", listOf(target))
            val info = try {
                guarded({ api.info() }, fail)
            } finally {
                api.close()
            } ?: return@launch
            if (info.protocol != PROTOCOL_VERSION) {
                _state.value = AppState.ProtocolMismatch(info.protocol)
                return@launch
            }
            val saved = all.servers.firstOrNull { it.serverId == info.serverId }
            if (saved != null && saved.certPin != pin) {
                _state.value = AppState.CertChanged(saved.serverId, saved.name, target)
                return@launch
            }
            val server = saved?.copy(endpoints = rememberEndpoints(target, emptyList(), saved.endpoints))
                ?: StoredServer(info.serverId, info.name, pin, listOf(target))
            guarded({ withContext(io) { saveLock.withLock { persist(server) } } }, fail) ?: return@launch
            if (_state.value !is AppState.Servers) return@launch
            open(server)
        }
    }

    fun openFound(found: FoundServer) = addServer(found.endpoint)

    /** Back to the server list from the lock screen, the login or the pairing: ends the session as a lock does and closes this server's client. */
    fun showServers() {
        when (val s = _state.value) {
            is AppState.Locked -> if (s.busy) return
            is AppState.Login -> if (s.busy) return
            is AppState.Pair -> if (s.busy) return
            else -> return
        }
        leaveServer()
    }

    /** Ends the session as a lock does, closes this server's client and shows the server list. */
    private fun leaveServer() {
        endSession()
        failReauth(ClientError.Locked())
        enrollGen++
        session?.api?.close()
        session = null
        stored = null
        persisted = null
        addingTo = null
        manual = null
        enrollmentToken = null
        pairAddress = null
        showList()
    }

    // ---- a changed server key ----

    /** Cancel on the warning: back to the server list, nothing trusted and nothing changed. */
    fun cancelCertChanged() {
        val s = _state.value as? AppState.CertChanged ?: return
        if (s.busy) return
        leaveServer()
    }

    /**
     * Trust new key: the key at the warning's address is probed again and, once that address names the same server, replaces the saved one.
     * The device credentials and users stay. Then the server opens with the new key.
     */
    fun trustNewKey() {
        val s = _state.value as? AppState.CertChanged ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true, problem = null)
        scope.launch {
            val fail = { p: Problem -> certChanged { it.copy(busy = false, problem = p) } }
            val fingerprint = guarded({ withContext(io) { probe(s.address) } }, fail) ?: return@launch
            val pin = fingerprintToPin(fingerprint)
            val api = connect(pin, s.serverId, listOf(s.address))
            val info = try {
                guarded({ api.info() }, fail)
            } finally {
                api.close()
            } ?: return@launch
            if (info.protocol != PROTOCOL_VERSION) {
                _state.value = AppState.ProtocolMismatch(info.protocol)
                return@launch
            }
            if (info.serverId != s.serverId) return@launch fail(Problem.OtherServer)
            val trusted = guarded({
                withContext(io) {
                    saveLock.withLock {
                        val base = all.servers.firstOrNull { it.serverId == s.serverId } ?: StoredServer(s.serverId, info.name, pin, emptyList())
                        base.copy(certPin = pin, endpoints = rememberEndpoints(s.address, emptyList(), base.endpoints)).also { persist(it) }
                    }
                }
            }, fail) ?: return@launch
            if (_state.value !is AppState.CertChanged) return@launch
            // The client pinned to the old key goes; the server opens again with the new one.
            session?.api?.close()
            session = null
            stored = null
            persisted = null
            addingTo = null
            open(trusted)
        }
    }

    private fun certChanged(change: (AppState.CertChanged) -> AppState.CertChanged) {
        (_state.value as? AppState.CertChanged)?.let { _state.value = change(it) }
    }

    // ---- pairing: link ----

    fun previewLink(text: String) {
        val payload = try {
            parsePairLink(text)
        } catch (e: ClientError.InvalidPairLink) {
            _state.value = pair(problem = Problem.InvalidLink)
            return
        }
        _state.value = pair(preview = payload)
    }

    /** The link carries the pin, so Continue on the preview is the whole confirmation. */
    fun redeemLink() {
        val s = _state.value as? AppState.Pair ?: return
        val link = s.preview ?: return
        if (s.busy) return
        if (addingTo?.let { it.serverId != link.sid } == true) {
            _state.value = s.copy(problem = Problem.WrongServer) // checked before redeeming: a pairing is used up once
            return
        }
        _state.value = s.copy(busy = true, problem = null)
        scope.launch {
            val pin = fingerprintToPin(link.fp)
            val opened = Session(connect(pin, link.sid, link.ep), pin, link.sid, link.ep)
            val response = guarded({ opened.api.redeem(RedeemRequest(secret = link.sec)) }) { _state.value = s.copy(busy = false, problem = it) }
            if (response == null) opened.api.close() else startEnroll(opened, response)
        }
    }

    fun cancelPreview() {
        _state.value = pair()
    }

    // ---- pairing: manual code ----

    fun submitManual(address: String, username: String, code: String) {
        val s = _state.value as? AppState.Pair ?: return
        if (s.busy) return
        val problem = when {
            !validAddress(address.trim()) -> Problem.BadAddress
            username.isBlank() -> Problem.BadUsername
            normalizeCode(code) == null -> Problem.BadCode
            else -> null
        }
        if (problem != null) {
            _state.value = pair(problem = problem)
            return
        }
        val target = address.trim()
        _state.value = pair(busy = true)
        scope.launch {
            val fingerprint = guarded({ withContext(io) { probe(target) } }) { _state.value = pair(problem = it) } ?: return@launch
            manual = username.trim() to normalizeCode(code)!!
            _state.value = pair(check = FingerprintCheck(target, fingerprint))
        }
    }

    fun cancelFingerprint() {
        manual = null
        _state.value = pair()
    }

    /** The user compared the fingerprint with the server console: trust it from now on and redeem the code. */
    fun confirmFingerprint() {
        val s = _state.value as? AppState.Pair ?: return
        val check = s.check ?: return
        val (username, code) = manual ?: return
        if (s.busy) return
        _state.value = s.copy(busy = true, problem = null)
        scope.launch {
            val pin = fingerprintToPin(check.fingerprint)
            val endpoints = listOf(check.address)
            val api = connect(pin, "", endpoints)
            val fail = { p: Problem -> _state.value = s.copy(busy = false, problem = p) }
            val info = guarded({ api.info() }, fail)
            if (info == null) return@launch api.close()
            if (info.protocol != PROTOCOL_VERSION) {
                api.close()
                _state.value = AppState.ProtocolMismatch(info.protocol)
                return@launch
            }
            if (addingTo?.let { it.serverId != info.serverId } == true) {
                api.close()
                _state.value = s.copy(busy = false, problem = Problem.WrongServer)
                return@launch
            }
            val response = guarded({ api.redeem(RedeemRequest(username = username, code = code)) }, fail)
            if (response == null) api.close() else startEnroll(Session(api, pin, info.serverId, endpoints), response)
        }
    }

    // ---- enroll ----

    private fun startEnroll(opened: Session, response: RedeemResponse) {
        manual = null
        session = opened
        enrollmentToken = response.enrollmentToken
        enrolledUser = response.username
        // The server counted the time from when it answered, so the deadline is that many seconds from now, whatever this device's clock says.
        val deadline = response.expiresInSeconds.takeIf { it > 0 }?.let { monotonicMs() + it * 1000 }
        _state.value = AppState.Enroll(opened.endpoints.first(), response, adding = addingTo != null, deadlineMs = deadline)
    }

    /** Whole seconds left to finish setting up (0 once it has run out), or null when the server did not say how long it lasts. */
    fun enrollSecondsLeft(): Long? {
        val deadline = (_state.value as? AppState.Enroll)?.deadlineMs ?: return null
        return maxOf(0L, (deadline - monotonicMs() + 999) / 1000)
    }

    /** The enrollment is over (its time ran out, here or on the server): close the session and start the pairing again, still adding a user if that was the aim. */
    private fun enrollmentExpired() {
        enrollGen++
        session?.api?.close()
        session = null
        enrollmentToken = null
        _state.value = pair(problem = Problem.EnrollmentExpired)
    }

    /** Back to the start; the one-time pairing already used is gone, so a new link or code is needed. */
    fun startOver() {
        enrollGen++
        session?.api?.close()
        session = null
        enrollmentToken = null
        _state.value = pair()
    }

    fun enroll(input: EnrollInput) {
        val s = _state.value as? AppState.Enroll ?: return
        val opened = session ?: return
        val token = enrollmentToken ?: return
        val username = enrolledUser ?: return
        if (s.busy) return
        if (enrollSecondsLeft() == 0L) return enrollmentExpired() // nothing is sent: the server would only say the same
        val adding = addingTo?.let { a -> a.copy(users = a.users.sortedWith(compareBy({ it.rejected }, { it.username != lastUnlocked }))) } // not rejected first, then most recent first
        // Adding a user: the device keeps its own name and mode, so the form does not ask for them.
        val form = if (adding == null) input else EnrollInput(input.password, input.passwordRepeat, input.pin, input.pinRepeat, adding.mode, device.label)
        validateEnroll(s.redeem, form)?.let { _state.value = s.copy(problem = it); return }
        _state.value = s.copy(busy = true, problem = null)
        val gen = ++enrollGen
        scope.launch {
            val rejected = mutableListOf<String>()
            val accepted = mutableListOf<String>()
            val done = guarded({
                try {
                    enrollTrying(opened, token, s.redeem, form, adding, rejected, accepted)
                } finally {
                    // The attempt is over, however it ended: the credentials the server did not recognize are tried last next time (see [markRejected]).
                    withContext(NonCancellable) { markRejected(rejected, accepted) }
                }
            }) { p ->
                if (gen != enrollGen) return@guarded // started over meanwhile: this answer is not for the screen any more
                when (p) {
                    Problem.EnrollmentExpired -> enrollmentExpired()
                    // Someone set the PIN/password first: ask for the existing ones next.
                    Problem.CredentialsChanged ->
                        _state.value = s.copy(redeem = s.redeem.copy(hasPin = true, hasPassword = s.redeem.passwordRequired), busy = false, problem = p)
                    else -> _state.value = s.copy(busy = false, problem = p)
                }
            } ?: return@launch
            // The server enrolled, but the person started over meanwhile: nothing is saved over the new pairing.
            if (gen != enrollGen) return@launch
            enrollmentToken = null
            // Only the device credential is kept. If unlocking fails below, the next start asks for the PIN.
            // A user added to a shared device gets a credential of their own; the device and its other users stay as they were.
            val user = StoredUser(username, done.credential)
            // The address that just worked goes first: the device's old ones may be what no longer answers.
            // Built and written under the save lock, from the newest [stored], and [stored] moves on before the lock is let go: a queued
            // address save then writes this user too, and an older snapshot can never overwrite it.
            val saved = guarded({
                withContext(io) {
                    saveLock.withLock {
                        val base = if (adding != null) stored ?: adding else null
                        val made = if (base != null) base.copy(
                            users = base.users.filter { it.username != username } + user,
                            endpoints = mergeEndpoints(opened.api.endpoints, base.endpoints),
                        ) else StoredServer(opened.serverId, s.redeem.serverName, opened.pin, opened.api.endpoints, done.deviceId, listOf(user), form.mode)
                        persist(made)
                        stored = made
                        persisted = made
                        made
                    }
                }
            }) { if (gen == enrollGen) _state.value = s.copy(busy = false, problem = it) }
            if (saved == null) return@launch
            if (gen != enrollGen) return@launch // saved safely; the screen already moved on
            val creds = saved
            addingTo = null
            val secret = if (s.redeem.passwordRequired) UnlockRequest(done.deviceId, username, done.credential, password = form.password)
            else UnlockRequest(done.deviceId, username, done.credential, pin = form.pin)
            val locked = lockedFor(creds, username, needsPassword = s.redeem.passwordRequired)
            if (guarded({ opened.api.unlock(secret) }) { _state.value = locked.copy(problem = it) } != null) enterUnlocked(opened, username)
        }
    }

    /**
     * Enrolls; when adding a user to this device, tries the device credentials of its users one by one until the server accepts one.
     * The server answers [ErrorCode.DEVICE_NOT_RECOGNIZED] for a device credential it does not accept before it looks at the PIN, so
     * only that moves on to the next credential. Any other error (a wrong PIN, an enrollment that ran out, too many requests) stops at once:
     * each wrong try counts against the enrollment, which ends after 5.
     */
    private suspend fun enrollTrying(
        opened: Session, token: String, r: RedeemResponse, form: EnrollInput, adding: StoredServer?,
        /** Filled with the users whose device credential the server did not recognize. */
        rejected: MutableList<String>,
        /** Filled with the user whose credential the server accepted. */
        accepted: MutableList<String>,
    ): EnrollResponse {
        if (adding == null) return opened.api.enroll(token, enrollRequest(r, form, device.platform))
        var last: ClientError.Api? = null
        for (user in adding.users) {
            try {
                return opened.api.enroll(token, enrollRequest(r, form, device.platform, adding.deviceId, user.credential)).also { accepted += user.username }
            } catch (e: ClientError.Api) {
                if (e.code != ErrorCode.DEVICE_NOT_RECOGNIZED) throw e // only "this credential is not accepted" moves on
                rejected += user.username
                last = e
            }
        }
        throw last ?: ClientError.Api(401, ErrorCode.DEVICE_NOT_RECOGNIZED, "This device holds no credential to add a user with.")
    }

    /**
     * Marks the users whose credential the server answered DEVICE_NOT_RECOGNIZED for as [StoredUser.rejected], and clears the mark of [cleared]
     * (accepted or unlocked). The answer is also given for a user who is disabled or locked for a while, so nobody is ever deleted for it; the mark
     * only moves them to the end of the order adding a user tries, so a stale one cannot use up the enrollment's wrong tries (5) before a good one.
     * Written under the save lock from the newest [stored]. A failed write keeps everything as it was.
     */
    private suspend fun markRejected(rejected: Collection<String>, cleared: Collection<String> = emptyList()) {
        if (rejected.isEmpty() && cleared.isEmpty()) return
        try {
            withContext(io) {
                saveLock.withLock {
                    val base = stored ?: return@withLock
                    fun marked(users: List<StoredUser>) = users.map { u ->
                        when {
                            u.username in cleared -> u.copy(rejected = false)
                            u.username in rejected -> u.copy(rejected = true)
                            else -> u
                        }
                    }
                    val next = base.copy(users = marked(base.users))
                    addingTo = addingTo?.let { a -> a.copy(users = marked(a.users)) }
                    if (next == base) return@withLock
                    persist(next)
                    stored = next
                    persisted = next
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The order stays as it was; the next try meets the same answer and marks it again.
        }
    }

    // ---- locked ----

    /** Shared device: pick who is unlocking, or null to go back to the list. */
    fun selectUser(username: String?) {
        val s = _state.value as? AppState.Locked ?: return
        if (!s.shared || s.busy) return
        _state.value = s.copy(username = username?.takeIf { it in s.users }, needsPassword = false, problem = null)
    }

    /** [secret] is the PIN, or the password once the server has asked for it. */
    fun unlock(secret: String) {
        val s = _state.value as? AppState.Locked ?: return
        val username = s.username ?: return
        val opened = session ?: return
        if (s.busy) return
        if (secret.isEmpty()) {
            _state.value = s.copy(problem = Problem.WrongCredentials)
            return
        }
        _state.value = s.copy(busy = true, problem = null)
        scope.launch {
            val loaded = try {
                withContext(io) { store.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val creds = loaded?.servers?.firstOrNull { it.serverId == opened.serverId }
            if (loaded == null || creds == null) {
                // This server is gone from the store: back to what it still holds.
                all = loaded ?: StoredServers(emptyList())
                stored = null
                persisted = null
                session = null
                opened.api.close()
                showList()
                return@launch
            }
            all = loaded
            stored = creds
            persisted = creds
            val credential = creds.users.firstOrNull { it.username == username }?.credential
            val deviceId = creds.deviceId
            if (credential == null || deviceId == null) {
                _state.value = s.copy(busy = false, problem = Problem.WrongCredentials)
                return@launch
            }
            val request = UnlockRequest(deviceId, username, credential,
                pin = secret.takeIf { !s.needsPassword }, password = secret.takeIf { s.needsPassword })
            val passwordAsked = { e: Exception -> e is ClientError.Api && e.code == ErrorCode.REAUTH_REQUIRED && e.passwordRequired }
            try {
                unlockFindingServer(opened, request)
                if (creds.users.any { it.username == username && it.rejected }) markRejected(emptyList(), listOf(username))
                enterUnlocked(opened, username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (passwordAsked(e)) _state.value = s.copy(needsPassword = true, busy = false, problem = null)
                else blockOr(e) { _state.value = s.copy(busy = false, problem = it) }
            }
        }
    }

    /** Unlocks; when no saved address answers, looks for the server on the local network (its address may have changed) and tries again. Every attempt the person starts may search once. */
    private suspend fun unlockFindingServer(opened: Session, request: UnlockRequest) {
        try {
            opened.api.unlock(request)
        } catch (e: ClientError.Unreachable) {
            if (!rediscover(opened)) throw e
            opened.api.unlock(request)
        }
    }

    /**
     * None of the saved addresses answered: asks [discovery] where the server announces itself, and takes the first address
     * that a pinned connection reaches and whose `/info` names this server. The pin is what makes this safe: a candidate with
     * another key (or another server id) is skipped, never trusted. The callers decide how often this may run. Returns true when an address was adopted (and saved).
     */
    private suspend fun rediscover(opened: Session): Boolean {
        val candidates = try {
            discovery.find(opened.serverId, DISCOVERY_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        for (candidate in candidates.filter { it !in opened.api.endpoints }) {
            if (!isThisServer(opened, candidate)) continue
            opened.api.useEndpoint(candidate)
            saveEndpoints(opened.api, emptyList())
            return true
        }
        return false
    }

    private suspend fun isThisServer(opened: Session, candidate: String): Boolean {
        val probe = connect(opened.pin, opened.serverId, listOf(candidate)) // the same pin: a different key fails here
        return try {
            probe.info().serverId == opened.serverId
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            probe.close()
        }
    }

    /**
     * Keeps the addresses this device reaches the server at: the one in use first, then those the server [announced], then the old
     * ones. Saved only when what is stored differs from what is on disk. The session carries on if saving fails; every later config
     * load tries again until a save succeeds, even when it brings the same addresses.
     */
    private fun saveEndpoints(api: ServerApi, announced: List<String>) {
        val creds = stored ?: return
        val merged = rememberEndpoints(api.endpoints.firstOrNull(), announced, creds.endpoints)
        if (merged != creds.endpoints) stored = creds.copy(endpoints = merged)
        if (stored == persisted) return
        scope.launch {
            try {
                withContext(io) {
                    saveLock.withLock {
                        // The newest credentials at the moment of writing, not the ones this call saw.
                        val latest = stored ?: return@withLock
                        if (latest == persisted) return@withLock
                        persist(latest)
                        persisted = latest
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The addresses still apply until the app closes.
            }
        }
    }

    /** Another user logs in on this device: the login of the same server, which then joins this device. */
    fun addUser() {
        val s = _state.value as? AppState.Locked ?: return
        val creds = stored ?: return
        if (s.busy) return
        _state.value = AppState.Login(s.server, creds.name, adding = true)
    }

    /** Shared device: pair another user onto it. The pairing screens come first, and the enroll then joins this device. Nothing leads here since [addUser] logs in. */
    fun addUserByPairing() {
        val s = _state.value as? AppState.Locked ?: return
        val creds = stored ?: return
        if (!s.shared || s.busy) return
        addingTo = creds
        session?.api?.close()
        session = null
        _state.value = pair()
    }

    /** Leaves the pairing screens for the lock screen again. */
    fun cancelAddUser() {
        val adding = addingTo ?: return
        addingTo = null
        manual = null
        enrollmentToken = null
        session?.api?.close()
        // The newest credentials in memory (an address save may have moved them on since adding began); [persisted] stays what it was.
        resume(stored ?: adding, readFromDisk = false)
    }

    // ---- login ----

    /** Back from adding a user to the lock screen. */
    fun cancelLogin() {
        val s = _state.value as? AppState.Login ?: return
        val creds = stored ?: return
        if (s.busy || !s.adding) return
        _state.value = lockedFor(creds)
    }

    /**
     * Logs [username] in with [pin], or with [newPin] (typed twice, the second time [newPinRepeat]) once the server said the user has none.
     * The first login on this device makes it a shared one with this platform's name; when it already holds users, one of their device
     * credentials puts the new user on it. The device id and the user's credential are saved (never the PIN), and the PIN just typed unlocks.
     */
    fun login(username: String, pin: String, newPin: String? = null, newPinRepeat: String? = null) {
        val s = _state.value as? AppState.Login ?: return
        val opened = session ?: return
        val creds = stored ?: return
        if (s.busy) return
        val name = username.trim()
        val secret = if (s.needsNewPin) newPin.orEmpty() else pin
        val problem = when {
            name.isEmpty() -> Problem.BadUsername
            // An empty PIN is sent as none: a user who has no PIN yet does not know one, and the server then asks for a new one.
            (s.needsNewPin || secret.isNotEmpty()) && (secret.length !in 4..12 || !secret.all { it in '0'..'9' }) -> Problem.PinDigits
            s.needsNewPin && newPin != newPinRepeat -> Problem.PinsDiffer
            else -> null
        }
        if (problem != null) {
            _state.value = s.copy(problem = problem)
            return
        }
        _state.value = s.copy(busy = true, problem = null)
        val request = LoginRequest(
            name, device.label, device.platform, DeviceMode.SHARED,
            pin = secret.takeIf { !s.needsNewPin && it.isNotEmpty() }, newPin = secret.takeIf { s.needsNewPin },
        )
        scope.launch {
            val rejected = mutableListOf<String>()
            val accepted = mutableListOf<String>()
            val done = try {
                try {
                    loginTrying(opened, creds, request, rejected, accepted)
                } finally {
                    withContext(NonCancellable) { markRejected(rejected, accepted) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = (e as? ClientError.Api)?.reason
                when (reason) {
                    ErrorReasons.PIN_NOT_SET -> _state.value = s.copy(needsNewPin = true, busy = false, problem = null)
                    ErrorReasons.DEVICE_PERSONAL -> _state.value = s.copy(busy = false, problem = Problem.PersonalDevice)
                    else -> blockOr(e) { _state.value = s.copy(busy = false, problem = it) }
                }
                return@launch
            }
            val user = StoredUser(name, done.credential)
            // Built from the newest [stored] under the save lock, like an enroll: a queued address save then writes this user too.
            val saved = guarded({
                withContext(io) {
                    saveLock.withLock {
                        val base = stored ?: creds
                        val first = creds.deviceId == null || creds.users.isEmpty()
                        val made = base.copy(
                            deviceId = done.deviceId,
                            users = (if (first) emptyList() else base.users.filter { it.username != name }) + user,
                            endpoints = mergeEndpoints(opened.api.endpoints, base.endpoints),
                            mode = if (first) DeviceMode.SHARED else base.mode,
                        )
                        persist(made)
                        stored = made
                        persisted = made
                        made
                    }
                }
            }) { _state.value = s.copy(busy = false, problem = it) } ?: return@launch
            // The server's AuthPolicy (unlock without a PIN) is only known after an unlock; the PIN just typed always unlocks.
            val unlock = UnlockRequest(done.deviceId, name, done.credential, pin = secret)
            val locked = lockedFor(saved, name)
            if (guarded({ opened.api.unlock(unlock) }) { _state.value = locked.copy(problem = it) } != null) enterUnlocked(opened, name)
        }
    }

    /**
     * Logs in; on a device that holds users already, tries their device credentials (not rejected first, the last to unlock first) until the
     * server accepts one. Only [ErrorCode.DEVICE_NOT_RECOGNIZED] moves on: the server answers it before it looks at the PIN, so it costs no wrong try.
     */
    private suspend fun loginTrying(
        opened: Session, creds: StoredServer, request: LoginRequest,
        rejected: MutableList<String>, accepted: MutableList<String>,
    ): EnrollResponse {
        val deviceId = creds.deviceId
        if (deviceId == null || creds.users.isEmpty()) return opened.api.login(request)
        var last: ClientError.Api? = null
        for (user in creds.users.sortedWith(compareBy({ it.rejected }, { it.username != lastUnlocked }))) {
            try {
                return opened.api.login(request.copy(deviceId = deviceId, deviceCredential = user.credential)).also { accepted += user.username }
            } catch (e: ClientError.Api) {
                if (e.code != ErrorCode.DEVICE_NOT_RECOGNIZED) throw e
                rejected += user.username
                last = e
            }
        }
        throw last ?: ClientError.Api(401, ErrorCode.DEVICE_NOT_RECOGNIZED, "This device holds no credential to add a user with.")
    }

    private fun enterUnlocked(opened: Session, username: String) {
        val connection = LiveConnection(scope, { opened.api.isUnlocked }) { onOpen, onText -> liveSession(opened, onOpen, onText) }
        lastLiveSearchMs = null
        live?.stop()
        live = connection
        connection.start()
        val state = AppState.Unlocked(username, opened.endpoints.first(), connection.status)
        generation++
        lastUnlocked = username
        unlocked = state
        _state.value = state
        // From here on: lock after the idle time, and follow the connection for canWrite.
        val shared = stored?.mode == DeviceMode.SHARED
        lastActivity = now()
        idleLimitMs = (if (shared) FALLBACK_LOCK_SHARED_MINUTES else FALLBACK_LOCK_PERSONAL_MINUTES) * 60_000L
        watchJobs.forEach { it.cancel() }
        sessionScope.cancel()
        sessionScope = newSessionScope()
        watchJobs = listOf(
            sessionScope.launch { while (true) { delay(IDLE_CHECK_MS); checkIdle() } },
            sessionScope.launch { connection.status.collect { _canWrite.value = it == ConnectionStatus.CONNECTED } },
            sessionScope.launch {
                val policy = guarded({ opened.api.config().auth }) { } ?: return@launch // the fallback stays if the server cannot be asked now
                idleLimitMs = policy.lockMinutes(shared) * 60_000L
            },
        )
        workspace = Workspace(
            sessionScope, opened.api, calls, canWrite, connection.refetch, connection.messages,
            onConfig = { saveEndpoints(opened.api, it.endpoints) },
            savedBranch = try { prefs.load().branch } catch (_: Exception) { null },
            saveBranch = ::saveBranch, compressor = compressor,
            today = { Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate() }, now = now,
            updater = updater, ownVersion = ownVersion,
        )
    }

    /**
     * One live socket; when no address answers, a search for the server (at most once per [LIVE_SEARCH_COOLDOWN_MS]), then straight another try.
     * A socket the server refuses because the session is gone (it restarted) locks like any other call that finds the token gone.
     */
    private suspend fun liveSession(opened: Session, onOpen: () -> Unit, onText: (String) -> Unit) {
        try {
            try {
                opened.api.webSocketSession(onOpen, onText)
            } catch (e: ClientError.Unreachable) {
                val last = lastLiveSearchMs
                if (last != null && monotonicMs() - last < LIVE_SEARCH_COOLDOWN_MS) throw e
                lastLiveSearchMs = monotonicMs()
                if (!rediscover(opened)) throw e
                opened.api.webSocketSession(onOpen, onText)
            }
        } catch (e: ClientError.Locked) {
            lockNow(Problem.SessionEnded) // stops this loop too; the throw below only ends this attempt
            throw e
        }
    }

    private fun AuthPolicy.lockMinutes(shared: Boolean) = (if (shared) autoLockSharedMinutes else autoLockPersonalMinutes).coerceAtLeast(1)

    // ---- auto-lock ----

    /** The app saw the user touch, type or click. */
    fun userActive() {
        lastActivity = now()
    }

    /** The app came back to the front: time spent away counts as idle. */
    fun appResumed() = checkIdle()

    /** Locks when there has been no interaction for the auto-lock time. Called by a timer, and on resume. */
    internal fun checkIdle() {
        if (unlocked != null && now() - lastActivity >= idleLimitMs) lockNow()
    }

    /** Forgets the access token, stops the live connection and shows the lock screen. */
    fun lockNow(problem: Problem? = null) {
        if (unlocked == null || removing) return
        val creds = stored
        endSession()
        failReauth(ClientError.Locked())
        _state.value = if (creds == null) pair() else lockedFor(creds, problem = problem)
    }

    /** Everything that exists only while unlocked. */
    private fun endSession() {
        lastLiveSearchMs = null
        generation++
        sessionScope.cancel()
        unlocked = null
        workspace = null
        session?.api?.lock()
        live?.stop()
        live = null
        watchJobs.forEach { it.cancel() }
        watchJobs = emptyList()
        _canWrite.value = false
    }

    // ---- re-auth ----

    /**
     * Runs [block], an authorized call. If the server answers that the PIN or password must be entered again, the
     * prompt shows ([reauth]); once the user confirms, [block] runs once more. If the prompt is dismissed this throws
     * [ClientError.ReauthCancelled]. Other failures pass through unchanged.
     */
    suspend fun <T> withReauth(block: suspend () -> T): T {
        // The action belongs to the session that started it: if the user locked or another one unlocked meanwhile, it is dropped, never retried for them.
        val started = generation
        try {
            return block()
        } catch (e: ClientError.Api) {
            if (e.code != ErrorCode.REAUTH_REQUIRED) throw e
            if (started != generation) throw ClientError.ReauthCancelled()
            askReauth(e.passwordRequired)
            if (started != generation) throw ClientError.ReauthCancelled()
            return block()
        }
    }

    private suspend fun askReauth(needsPassword: Boolean) {
        val waiting = pendingReauth ?: CompletableDeferred<Unit>().also {
            pendingReauth = it
            _reauth.value = ReauthPrompt(needsPassword)
        }
        waiting.await()
    }

    fun submitReauth(secret: String) {
        val prompt = _reauth.value ?: return
        val opened = session ?: return
        if (prompt.busy) return
        if (secret.isEmpty()) {
            _reauth.value = prompt.copy(problem = Problem.WrongCredentials)
            return
        }
        _reauth.value = prompt.copy(busy = true, problem = null)
        // The answer belongs to this prompt in this session: after a lock or another user's unlock it is dropped, success or not.
        val started = generation
        val waiting = pendingReauth
        fun stale() = started != generation || pendingReauth !== waiting
        sessionScope.launch {
            try {
                opened.api.reauth(if (prompt.needsPassword) ReauthRequest(password = secret) else ReauthRequest(pin = secret))
                if (stale()) return@launch
                waiting?.complete(Unit)
                pendingReauth = null
                _reauth.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stale()) return@launch
                if (e is ClientError.Api && e.code == ErrorCode.REAUTH_REQUIRED && e.passwordRequired) _reauth.value = ReauthPrompt(needsPassword = true)
                else blockOr(e) { _reauth.value = prompt.copy(busy = false, problem = it) }
            }
        }
    }

    fun cancelReauth() = failReauth(ClientError.ReauthCancelled())

    private fun failReauth(e: Exception) {
        pendingReauth?.completeExceptionally(e)
        pendingReauth = null
        _reauth.value = null
    }

    // ---- settings ----

    fun openSettings() {
        val u = unlocked ?: return
        _state.value = AppState.Settings(u.username, u.server)
        loadDevices()
    }

    fun closeSettings() {
        unlocked?.let { _state.value = it }
    }

    fun setLanguage(tag: String?) {
        _language.value = tag
        scope.launch {
            try {
                withContext(io) { prefs.save(prefs.load().copy(language = tag)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The choice still applies until the app closes.
            }
        }
    }

    private fun saveBranch(key: String) {
        scope.launch {
            try {
                withContext(io) { prefs.save(prefs.load().copy(branch = key)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The choice still applies until the app closes.
            }
        }
    }

    private fun settings(change: (AppState.Settings) -> AppState.Settings) {
        (_state.value as? AppState.Settings)?.let { _state.value = change(it) }
    }

    fun loadDevices() {
        val opened = session ?: return
        if (_state.value !is AppState.Settings) return
        sessionScope.launch {
            val list = guarded({ opened.api.devices() }) { p -> settings { it.copy(problem = p) } } ?: return@launch
            settings { it.copy(devices = list, problem = null) }
        }
    }

    /** Takes this user off another of their devices. The server asks for the PIN or password again first. */
    fun revokeDevice(id: String) {
        val opened = session ?: return
        val s = _state.value as? AppState.Settings ?: return
        if (s.busy || s.devices?.any { it.id == id && !it.current } != true) return
        _state.value = s.copy(busy = true, problem = null)
        sessionScope.launch {
            val done = guarded({ withReauth { opened.api.revokeDevice(id) } }) { p -> settings { it.copy(busy = false, problem = p) } }
            if (done == null) return@launch
            settings { it.copy(busy = false) }
            loadDevices()
        }
    }

    /** Forgets this server on this device: deletes the saved sign-ins of every user here and returns to the server list. Other servers stay. */
    fun removeServer() {
        val s = _state.value as? AppState.Settings ?: return
        if (s.busy || removing) return // one removal at a time, even after Back and Settings again
        _state.value = s.copy(busy = true, problem = null)
        // Not cancellable and no lock meanwhile: the saved sign-ins are either all gone, or the user is still in Settings.
        removing = true
        scope.launch {
            val gone = stored?.serverId
            val cleared = try {
                guarded({
                    withContext(io) {
                        saveLock.withLock {
                            val next = StoredServers(all.servers.filter { it.serverId != gone }, all.lastServerId.takeIf { it != gone })
                            // The last server takes the file with it.
                            if (next.servers.isEmpty()) store.clear() else store.save(next)
                            all = next
                            stored = null
                            persisted = null
                        }
                    }
                }) { p -> settings { it.copy(busy = false, problem = p) } }
            } finally {
                removing = false
            }
            if (cleared == null) return@launch
            endSession()
            failReauth(ClientError.Locked())
            session?.api?.close()
            session = null
            stored = null
            addingTo = null
            pairAddress = null
            showList()
        }
    }

    // ---- errors ----

    /** Runs [block]; a pin or protocol failure blocks the app, any other failure goes to [fail]. Returns null on failure. */
    private suspend fun <T> guarded(block: suspend () -> T, fail: (Problem) -> Unit): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        blockOr(e, fail)
        null
    }

    /** Shows a blocking state; nothing more is sent, so the live connection and a waiting re-auth prompt end. */
    private fun block(state: AppState, e: Exception) {
        endSession()
        failReauth(e)
        _state.value = state
    }

    private fun nameOf(serverId: String) = (all.servers.firstOrNull { it.serverId == serverId } ?: stored?.takeIf { it.serverId == serverId })?.name.orEmpty()

    private fun blockOr(e: Exception, fail: (Problem) -> Unit) {
        when (e) {
            // A server this device holds (the open session's) can be trusted again; a pairing that has no server yet just stops.
            is ClientError.PinMismatch -> session?.takeIf { it.serverId.isNotEmpty() }?.let { opened ->
                block(AppState.CertChanged(opened.serverId, nameOf(opened.serverId), e.endpoint ?: opened.api.endpoints.firstOrNull() ?: opened.endpoints.first()), e)
            } ?: fail(e.toProblem())
            is ClientError.ProtocolMismatch -> block(AppState.ProtocolMismatch(e.serverProtocol), e)
            // The server ended the session: back to the lock screen, with the reason.
            is ClientError.Locked -> if (unlocked != null) lockNow(Problem.SessionEnded) else fail(e.toProblem())
            else -> fail(e.toProblem())
        }
    }
}

internal fun Exception.toProblem(): Problem = when (this) {
    is ClientError.Unreachable -> Problem.Unreachable
    is ClientError.InvalidPairLink -> Problem.InvalidLink
    is ClientError.Locked -> Problem.SessionEnded
    is ClientError.ReauthCancelled -> Problem.ReauthCancelled
    is ClientError.Api -> when (code) {
        ErrorCode.PAIRING_INVALID -> Problem.PairingInvalid
        ErrorCode.RATE_LIMITED -> Problem.TooManyAttempts(retryAfterSeconds)
        ErrorCode.BANNED -> Problem.Banned
        ErrorCode.ENROLLMENT_EXPIRED -> Problem.EnrollmentExpired
        ErrorCode.DEVICE_NOT_RECOGNIZED -> Problem.DeviceRejected
        // A refusal that says why (e.g. this device is personal) is worded by its reason; one that does not is a wrong secret.
        ErrorCode.FORBIDDEN -> if (reason != null) Problem.Rejected(code, reason) else Problem.WrongCredentials
        ErrorCode.UNAUTHORIZED, ErrorCode.REAUTH_REQUIRED -> Problem.WrongCredentials
        ErrorCode.CREDENTIALS_CHANGED -> Problem.CredentialsChanged
        else -> Problem.Rejected(code, reason)
    }
    is ClientError.PinMismatch -> Problem.PinMismatch
    is ClientError.ProtocolMismatch -> Problem.ProtocolMismatch
    else -> Problem.Unknown
}

/** `9:05` for 545 seconds: minutes, then seconds in two digits. */
fun formatCountdown(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** `host:port` with a port from 1 to 65535. */
internal fun validAddress(address: String): Boolean {
    val port = address.substringAfterLast(':', "").toIntOrNull() ?: return false
    val host = address.substringBeforeLast(':')
    return port in 1..65535 && host.isNotBlank() && host.none { it.isWhitespace() || it == '/' }
}

/** The 10 characters of a manual code, whatever case, dashes or spaces were typed; null when it cannot be one. */
internal fun normalizeCode(typed: String): String? =
    typed.filter { it != '-' && !it.isWhitespace() }.uppercase().takeIf { it.length == 10 && it.all(Char::isLetterOrDigit) }
