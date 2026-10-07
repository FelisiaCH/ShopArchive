package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.flow.StateFlow
import xyz.felismp.shoparchive.app.client.ConnectionStatus
import xyz.felismp.shoparchive.app.client.FoundServer
import xyz.felismp.shoparchive.shared.DeviceInfo
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.PairPayload
import xyz.felismp.shoparchive.shared.RedeemResponse

/** What went wrong, in terms the screen words itself (the text lives in strings.xml). */
sealed interface Problem {
    data object InvalidLink : Problem
    data object Unreachable : Problem
    data object PairingInvalid : Problem
    data class TooManyAttempts(val seconds: Int?) : Problem
    data object Banned : Problem
    data object WrongCredentials : Problem
    data object CredentialsChanged : Problem
    data object BadAddress : Problem
    data object BadUsername : Problem
    data object BadCode : Problem
    data object PasswordEmpty : Problem
    data object PasswordsDiffer : Problem
    data class PinFormat(val length: Int) : Problem
    data object PinsDiffer : Problem
    /** A PIN typed at login is not 4 to 12 digits (the server's own length is not known before login; it refuses a wrong one). */
    data object PinDigits : Problem
    /** Adding a user: this device was set up as personal, so it holds one user; it has to be removed and the server added again. */
    data object PersonalDevice : Problem
    data object LabelEmpty : Problem
    /** The access token is gone (the server ended the session): unlock again. */
    data object SessionEnded : Problem
    /** The pairing is for another server than the one this device is already paired with. */
    data object WrongServer : Problem
    /** Adding a user: the server accepted none of the device credentials this device holds (the device was removed from the server). */
    data object DeviceRejected : Problem
    /** The time to finish setting up ran out: the pairing must be done again with a new link or code. */
    data object EnrollmentExpired : Problem
    /** The PIN-or-password prompt was dismissed, so the action was not done. */
    data object ReauthCancelled : Problem
    /** The server said no: its error [code] and the machine [reason] key if it gave one, worded by the screen. */
    data class Rejected(val code: ErrorCode, val reason: String? = null) : Problem
    /** The server's certificate is not the pinned one, and nothing of the server is held here to trust it for. */
    data object PinMismatch : Problem
    /** Trusting a changed key: another server than the saved one answers at that address now. */
    data object OtherServer : Problem
    /** The server speaks another protocol version (the app also shows its blocking screen). */
    data object ProtocolMismatch : Problem
    /** An unexpected local exception: said in general words, the exception's own message is never shown. */
    data object Unknown : Problem
}

/** A server saved on this device, as the server list shows it: [endpoint] is the address tried first. */
data class ServerRow(val serverId: String, val name: String, val endpoint: String, val userCount: Int)

/** The fingerprint the user must compare with the server console before the manual-code pairing goes on. */
data class FingerprintCheck(val address: String, val fingerprint: String)

sealed interface AppState {
    data object Starting : AppState

    /**
     * The home screen: the servers saved here, the others [found] on the local network ([searching] while it is looked at), and a field to add one by address.
     * [busy] while an added address is being checked.
     */
    data class Servers(
        val saved: List<ServerRow>,
        val found: List<FoundServer>,
        val searching: Boolean,
        val problem: Problem?,
        val busy: Boolean = false,
    ) : AppState

    /** Both pairing tabs. [preview] is a parsed link waiting for Continue, [check] a fingerprint waiting for Confirm. */
    data class Pair(
        val preview: PairPayload? = null,
        val check: FingerprintCheck? = null,
        val busy: Boolean = false,
        val problem: Problem? = null,
        /** Pairing another user onto this shared device: the screen offers a way back to the lock screen. */
        val adding: Boolean = false,
        /** The server's address when it is already known (picked from the server list): the manual-code form starts with it. */
        val address: String = "",
    ) : AppState

    data class Enroll(
        val server: String,
        val redeem: RedeemResponse,
        val busy: Boolean = false,
        val problem: Problem? = null,
        /** Adding a user to this shared device: its name and mode are kept, so the form leaves them out. */
        val adding: Boolean = false,
        /** When the enrollment runs out, in [AppFlow]'s monotonic milliseconds (see [AppFlow.enrollSecondsLeft]); null when the server did not say. */
        val deadlineMs: Long? = null,
    ) : AppState

    /**
     * Logging in on [server] (called [serverName]) with a user name and PIN, without pairing. [needsNewPin] once the server said the user
     * has no PIN yet: the form then asks for a new one twice. [adding] when another user joins this device from the lock screen, which the screen offers a way back to.
     */
    data class Login(
        val server: String,
        val serverName: String,
        val needsNewPin: Boolean = false,
        val busy: Boolean = false,
        val problem: Problem? = null,
        val adding: Boolean = false,
    ) : AppState

    /**
     * Stored credentials exist. [users] are the people paired on this device. On a [shared] device [username] is null until
     * one is picked; on a personal device it is the one user. [needsPassword] turns on after the server asks for it.
     */
    data class Locked(
        val server: String,
        val users: List<String>,
        val shared: Boolean,
        val username: String?,
        val needsPassword: Boolean = false,
        val busy: Boolean = false,
        val problem: Problem? = null,
    ) : AppState

    data class Unlocked(val username: String, val server: String, val connection: StateFlow<ConnectionStatus>) : AppState

    /** [devices] is null until the list has loaded. [busy] while a device is being taken off. */
    data class Settings(
        val username: String,
        val server: String,
        val devices: List<DeviceInfo>? = null,
        val busy: Boolean = false,
        val problem: Problem? = null,
    ) : AppState

    /**
     * The saved server [serverId] (called [name]) showed another key at [address] than the one this device trusts: someone may be
     * intercepting, or the server was reinstalled. Nothing more is sent until the person cancels or trusts the new key. [busy] while it is checked.
     */
    data class CertChanged(
        val serverId: String,
        val name: String,
        val address: String,
        val busy: Boolean = false,
        val problem: Problem? = null,
    ) : AppState

    data class ProtocolMismatch(val serverProtocol: Int?) : AppState
}

/** The PIN-or-password prompt an important action asked for; shown over whatever screen is current. */
data class ReauthPrompt(val needsPassword: Boolean, val busy: Boolean = false, val problem: Problem? = null)
