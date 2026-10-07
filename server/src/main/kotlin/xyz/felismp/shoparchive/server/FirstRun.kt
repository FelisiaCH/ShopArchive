package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.auth.DeviceStore
import xyz.felismp.shoparchive.server.auth.Hasher
import xyz.felismp.shoparchive.server.auth.Sessions
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.records.BranchStore
import xyz.felismp.shoparchive.server.users.NO_ROLE
import xyz.felismp.shoparchive.server.users.UserException
import xyz.felismp.shoparchive.server.users.UserStore
import java.security.SecureRandom
import java.util.Locale

/** The owner's login made at this start: its user [name] and the [pin] in clear, to print once and forget. */
internal class FirstRunLogin(val name: String, val pin: String)

private val random = SecureRandom()

/**
 * What makes a server usable the first time it starts, without the console: with no users yet, the branch `setup.first-branch` (if there is
 * none) and the op `setup.first-user` in it; then, on every start while that user has no device, a new PIN of `auth.pin.length` digits for it,
 * replacing the last one (and its lock). Run it before the network starts, so the owner never has a moment without a PIN in which anyone could
 * log in and choose one; [printFirstRun] shows the result later. A failure is logged and the server goes on: the admin can do the same by hand.
 */
internal fun firstRunSetup(config: ConfigService, users: UserStore, branches: BranchStore, devices: DeviceStore, hasher: Hasher, sessions: Sessions): FirstRunLogin? {
    val setup = config.setup
    val owner = setup.firstUser
    if (owner.isEmpty()) return null
    try {
        if (users.userNames().isEmpty()) {
            val branch = setup.firstBranch
            if (branch.isNotEmpty() && branches.all().isEmpty()) {
                branches.add(branch, branch.replaceFirstChar { it.titlecase(Locale.ROOT) })
                Log.info("First start: branch '$branch' made")
            }
            users.addUser(owner, NO_ROLE, listOfNotNull(branch.takeIf { branches.find(it) != null }))
            users.setOp(owner, true)
            Log.info("First start: user '$owner' made, an op")
        }
        val account = users.find(owner) ?: return null
        if (!account.enabled || devices.devicesOf(account.id).isNotEmpty()) return null
        val pin = (1..config.auth.pinLength).joinToString("") { random.nextInt(10).toString() }
        val hash = hasher.hash(pin)
        // The same lock as a login, so one under way does not see half of the change.
        sessions.withAccount(account.id) { users.replacePin(account.id, hash) }
        return FirstRunLogin(owner, pin)
    } catch (e: UserException) {
        Log.warn("First start setup: ${e.message}")
    } catch (e: ApiError) {
        Log.warn("First start setup: ${e.message}")
    }
    return null
}

/** Prints what [firstRunSetup] made, if anything, to [out]: the terminal only, never a file under `logs/`. */
internal fun printFirstRun(login: FirstRunLogin?, out: (String) -> Unit = Log::terminalOnly) {
    if (login == null) return
    out("Owner login: user '${login.name}'  PIN ${login.pin}")
    out("Open the app, pick this server and log in with these. A new PIN is made at every start until the owner has logged in.")
}
