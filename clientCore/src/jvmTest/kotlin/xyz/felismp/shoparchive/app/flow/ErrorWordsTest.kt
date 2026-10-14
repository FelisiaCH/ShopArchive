package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Every error the client core can raise is worded by the app from its own strings; an exception's English message never reaches a screen. */
class ErrorWordsTest {
    private val everyClientError: List<ClientError> =
        listOf<ClientError>(
            ClientError.PinMismatch(), ClientError.PinMismatch(RuntimeException("x509 detail")),
            ClientError.Unreachable(), ClientError.Unreachable(java.io.IOException("connect timed out")),
            ClientError.ProtocolMismatch(9, "Server speaks protocol 9"),
            ClientError.Locked(), ClientError.ReauthCancelled(),
        ) + ErrorCode.entries.map { ClientError.Api(400, it, "English text of $it") }

    @Test fun everyKindOfClientErrorIsInTheList() {
        val subclasses = ClientError::class.java.permittedSubclasses
        if (subclasses != null) assertEquals(subclasses.map { it.name }.toSet(), everyClientError.map { it.javaClass.name }.toSet())
    }

    @Test fun noClientErrorBecomesAnUnknownFailureOrProblem() {
        for (e in everyClientError) {
            assertNotEquals(Failure.Other, e.toFailure(), "$e")
            assertNotEquals(Problem.Unknown, e.toProblem(), "$e")
        }
    }

    @Test fun theWordingNeverCarriesTheExceptionsMessage() {
        for (e in everyClientError) {
            val text = e.toFailure().toString() + e.toProblem().toString()
            assertTrue(e.message!! !in text, "$e leaks its message: $text")
        }
        assertEquals(Failure.Other, IllegalStateException("some English").toFailure())
        assertEquals(Problem.Unknown, IllegalStateException("some English").toProblem())
    }

    @Test fun theErrorsThatNeedTheirOwnWordsGetThem() {
        assertEquals(Failure.Client(Problem.ReauthCancelled), ClientError.ReauthCancelled().toFailure())
        assertEquals(Failure.Client(Problem.SessionEnded), ClientError.Locked().toFailure())
        assertEquals(Failure.Client(Problem.PinMismatch), ClientError.PinMismatch().toFailure())
        assertEquals(Failure.Client(Problem.ProtocolMismatch), ClientError.ProtocolMismatch(3, "m").toFailure())
        assertEquals(Failure.Unreachable, ClientError.Unreachable().toFailure())
        assertIs<Failure.Refused>(ClientError.Api(403, ErrorCode.FORBIDDEN, "m").toFailure())
    }
}
