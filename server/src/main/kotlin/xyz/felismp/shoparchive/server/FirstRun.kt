package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.server.auth.DeviceStore
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.records.BranchStore
import xyz.felismp.shoparchive.server.users.NO_ROLE
import xyz.felismp.shoparchive.server.users.ShowPairing
import xyz.felismp.shoparchive.server.users.UserException
import xyz.felismp.shoparchive.server.users.UserStore
import java.util.Locale

/**
 * What makes a server usable the first time it starts, without the console: with no users yet, the branch `setup.first-branch` (if there is
 * none) and the op `setup.first-user` in it; then, on every start while that user has no paired device, a new pairing for it ([pair], on the
 * console only). A failure is logged and the server goes on: the admin can do the same by hand.
 */
internal fun firstRun(config: ConfigService, users: UserStore, branches: BranchStore, devices: DeviceStore, pair: ShowPairing) {
    val setup = config.setup
    val owner = setup.firstUser
    if (owner.isEmpty()) return
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
        val account = users.find(owner) ?: return
        if (account.enabled && devices.devicesOf(account.id).isEmpty()) pair(ConsoleSender, owner, false)
    } catch (e: UserException) {
        Log.warn("First start setup: ${e.message}")
    } catch (e: ApiError) {
        Log.warn("First start setup: ${e.message}")
    }
}
