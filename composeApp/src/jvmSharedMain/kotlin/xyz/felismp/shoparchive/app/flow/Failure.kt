package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.ErrorCode

/** Why a call from a screen did not work, in terms the screen words itself. */
sealed interface Failure {
    /** The server could not be reached. */
    data object Unreachable : Failure

    /** The server said no: its error [code] and the machine [reason] key if it gave one. The screen words both in the user's language; the server's English text is never shown. */
    data class Refused(val code: ErrorCode, val reason: String? = null) : Failure

    /** Any other client-side error (a cancelled PIN prompt, an ended session, ...), worded like the same [problem] on the pairing and lock screens. */
    data class Client(val problem: Problem) : Failure

    /** An unexpected local exception; the screen says so in general words and never shows the exception's own (English) message. */
    data object Other : Failure
}

internal fun Throwable.toFailure(): Failure = when (this) {
    is ClientError.Unreachable -> Failure.Unreachable
    is ClientError.Api -> Failure.Refused(code, reason)
    is ClientError -> Failure.Client(toProblem())
    else -> Failure.Other
}

/**
 * How a screen runs a call to the server. The app's version asks for the PIN again when the server wants it and
 * blocks or locks the app when the server's key, protocol or session says so; every failure still comes back to the caller.
 */
interface Calls {
    suspend fun <T> run(block: suspend () -> T): T
}
