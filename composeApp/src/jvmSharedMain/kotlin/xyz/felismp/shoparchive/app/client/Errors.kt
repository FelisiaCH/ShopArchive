package xyz.felismp.shoparchive.app.client

import xyz.felismp.shoparchive.shared.ErrorCode

/** Everything the client core can fail with; the UI picks its wording from the subclass. */
sealed class ClientError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The pasted text is not a pairing link. */
    class InvalidPairLink(val reason: Reason) : ClientError("Not a valid pairing link: $reason") {
        enum class Reason { NOT_A_PAIR_LINK, BAD_PAYLOAD, UNSUPPORTED_VERSION }
    }

    /** The server answered with a certificate other than the pinned one: block and warn, never fall back. [endpoint] is the address that showed it, when known. */
    class PinMismatch(cause: Throwable? = null, val endpoint: String? = null) : ClientError("The server's certificate is not the one this device was paired with", cause)

    /** No endpoint could be reached. */
    class Unreachable(cause: Throwable? = null) : ClientError("The server could not be reached", cause)

    /** The server speaks another protocol version ([serverProtocol]); the app or the server needs an update. */
    class ProtocolMismatch(val serverProtocol: Int, message: String) : ClientError(message)

    /** There is no valid access token (never unlocked, or the server answered 401): unlock again. */
    class Locked : ClientError("The app is locked")

    /** The user dismissed the PIN-or-password prompt that an important action asked for, so the action was not done. */
    class ReauthCancelled : ClientError("The PIN or password was not entered")

    /**
     * Any other error answer; [code] is INTERNAL when the body was not the shared error JSON. [reason] is the server's
     * machine key for why ([xyz.felismp.shoparchive.shared.ErrorReasons]); [message] is the server's English text, for logs only and never shown.
     */
    class Api(
        val status: Int,
        val code: ErrorCode,
        message: String,
        val passwordRequired: Boolean = false,
        val retryAfterSeconds: Int? = null,
        val reason: String? = null,
    ) : ClientError(message)
}
