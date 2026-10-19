package xyz.felismp.shoparchive.server.net

import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.auth.AuditLog
import xyz.felismp.shoparchive.shared.ConfigChangedMessage

/**
 * What every change to the shop's setup from the app needs, whatever service a plugin has put in place of the core's: the PIN or password entered
 * recently (401 `REAUTH_REQUIRED` otherwise). The routes call this before the service, so the service cannot skip it.
 */
internal fun ServiceRegistry.requireRecentAuth(principal: Principal) = require<AuthService>().requireRecentAuth(principal)

/** After a change to the branches or categories went through: one line in the audit log, and every open app asks for the config again. */
internal suspend fun ServiceRegistry.configChanged(principal: Principal, event: String, ip: String, result: String) {
    blocking { get(AuditLog::class.java)?.record(event, principal.username, principal.deviceId, ip, result) }
    get(EventService::class.java)?.broadcast(ConfigChangedMessage)
}

/** After a change to the branches of the account [userId]: its open apps ask for the config again, as it lists the branches the account works in. */
internal fun ServiceRegistry.userConfigChanged(userId: String) {
    get(EventService::class.java)?.notifyUser(userId, ConfigChangedMessage)
}
