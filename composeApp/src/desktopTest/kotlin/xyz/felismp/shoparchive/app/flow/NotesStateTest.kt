package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.NoteChangedMessage
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.NoteRead
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BOB = UserRef("u2", "bob")
private val ME = UserRef("u1", "alice")

private fun note(
    id: String, at: String = "09:00", by: UserRef = BOB, date: String = "2026-10-04", branch: String = "main", reads: List<NoteRead> = emptyList(),
) = NoteDto(id, branch, date, "text of $id", by, "${date}T$at:00+07:00", reads)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesStateTest {
    private fun harness(scope: kotlinx.coroutines.CoroutineScope, permissions: List<String> = ALL_NODES) =
        Harness(scope, ConfigState.Ready(testConfig(permissions = permissions)))

    // ---- reading ----

    @Test fun showsTodaysNotesOfTheBranchNewestFirst() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a", "09:00"), note("b", "11:30"), note("c", "10:00", date = "2026-10-03"), note("d", "10:00", branch = "market"))
        val ui = NotesState(h.env).ui.value
        assertEquals(listOf("b", "a"), ui.notes!!.map { it.id })
        assertEquals(listOf("main" to "2026-10-04"), h.api.noteCalls)
        assertEquals("2026-10-04", ui.date)
        assertTrue(ui.isToday)
        assertFalse(ui.loading)
        assertNull(ui.failure)
    }

    @Test fun anUnreadNoteIsOneOfSomeoneElseThatTheReaderHasNotRead() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(
            note("theirs-unread", "09:00"), note("theirs-read", "09:10", reads = listOf(NoteRead(ME, "2026-10-04T09:30:00+07:00"))),
            note("mine", "09:20", by = ME), note("read-by-other", "09:30", reads = listOf(NoteRead(BOB, "2026-10-04T09:40:00+07:00"))),
        )
        val ui = NotesState(h.env).ui.value
        assertEquals(listOf("read-by-other", "theirs-unread"), ui.notes!!.filter { ui.unread(it) }.map { it.id })
        assertEquals(2, ui.unreadCount)
        assertEquals("2026-10-04T09:30:00+07:00", ui.readAt(ui.notes!!.first { it.id == "theirs-read" }))
        assertNull(ui.readAt(ui.notes!!.first { it.id == "mine" }))
    }

    @Test fun withoutTheReadNodeNothingIsUnreadAndMarkingIsNotAsked() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope, permissions = listOf(PermissionNodes.NOTE_CREATE))
        h.api.noteList = listOf(note("mine", by = ME, reads = listOf(NoteRead(BOB, "2026-10-04T09:40:00+07:00"))), note("a"))
        val state = NotesState(h.env)
        assertEquals(0, state.ui.value.unreadCount)
        assertFalse(state.ui.value.canRead)
        state.markRead("a")
        state.markAllRead()
        assertTrue(h.api.noteReads.isEmpty() && h.api.noteReadAlls.isEmpty())
    }

    @Test fun withoutEitherNodeNothingIsAskedAndNothingKept() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope, permissions = listOf(PermissionNodes.DASHBOARD_VIEW))
        h.api.noteList = listOf(note("a"))
        val state = NotesState(h.env)
        assertTrue(h.api.noteCalls.isEmpty())
        assertNull(state.ui.value.notes)
        assertFalse(Capabilities(setOf(PermissionNodes.DASHBOARD_VIEW)).allows(Destination.NOTES))
        assertTrue(Capabilities(setOf(PermissionNodes.NOTE_CREATE)).allows(Destination.NOTES))
        assertTrue(Capabilities(setOf(PermissionNodes.NOTE_READ)).allows(Destination.NOTES))
    }

    @Test fun aFailedLoadIsAFailureNotAnEmptyDayAndReloadRecovers() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"))
        h.api.notesError = ClientError.Unreachable()
        val state = NotesState(h.env)
        assertEquals(Failure.Unreachable, state.ui.value.failure)
        assertNull(state.ui.value.notes)
        h.api.notesError = null
        state.reload()
        assertNull(state.ui.value.failure)
        assertEquals(listOf("a"), state.ui.value.notes!!.map { it.id })
    }

    @Test fun aReloadKeepsTheLastNotesOnScreenWhenItFails() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"))
        val state = NotesState(h.env)
        h.api.notesError = ClientError.Unreachable()
        state.reload()
        assertEquals(Failure.Unreachable, state.ui.value.failure)
        assertEquals(listOf("a"), state.ui.value.notes!!.map { it.id })
    }

    // ---- the day ----

    @Test fun previousAndNextDayAskForThatDayAndNextStopsAtToday() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("today"), note("yesterday", date = "2026-10-03"))
        val state = NotesState(h.env)
        state.nextDay()
        assertEquals(1, h.api.noteCalls.size, "there is no day after today")
        state.previousDay()
        assertEquals("2026-10-03", state.ui.value.date)
        assertFalse(state.ui.value.isToday)
        assertEquals(listOf("yesterday"), state.ui.value.notes!!.map { it.id })
        state.nextDay()
        assertEquals(listOf("today"), state.ui.value.notes!!.map { it.id })
        assertTrue(state.ui.value.isToday)
    }

    @Test fun showTakesADateAndIgnoresAnythingElse() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotesState(h.env)
        state.show("2026-10-01")
        assertEquals("2026-10-01", state.ui.value.date)
        state.show("yesterday")
        assertEquals("2026-10-01", state.ui.value.date)
    }

    @Test fun aBranchChangeLoadsTheOtherBranch() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("m", branch = "market"), note("a"))
        val state = NotesState(h.env)
        h.branch.value = "market"
        assertEquals(listOf("m"), state.ui.value.notes!!.map { it.id })
        assertEquals("market", state.ui.value.branch)
    }

    @Test fun aLiveMessageForTheShownBranchAndDayReloadsOthersDoNot() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"))
        val state = NotesState(h.env)
        h.api.noteList = listOf(note("a"), note("b", "10:00"))
        h.messages.emit(NoteChangedMessage("market", "2026-10-04"))
        h.messages.emit(NoteChangedMessage("main", "2026-10-03"))
        assertEquals(1, h.api.noteCalls.size)
        h.messages.emit(NoteChangedMessage("main", "2026-10-04"))
        assertEquals(listOf("b", "a"), state.ui.value.notes!!.map { it.id })
        h.refetch.emit(Unit)
        assertEquals(3, h.api.noteCalls.size)
    }

    // ---- marking ----

    @Test fun markingOneNoteReadShowsWhenAndKeepsTheRest() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a", "09:00"), note("b", "10:00"))
        val state = NotesState(h.env)
        state.markRead("a")
        assertEquals(listOf("a"), h.api.noteReads)
        val ui = state.ui.value
        assertEquals(listOf("b", "a"), ui.notes!!.map { it.id })
        assertEquals("2026-10-04T10:00:00+07:00", ui.readAt(ui.notes!!.first { it.id == "a" }))
        assertEquals(1, ui.unreadCount)
        assertTrue(ui.marking.isEmpty())
    }

    @Test fun markAllReadMarksTheDayAndLeavesOwnNotesAlone() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"), note("b", "10:00"), note("mine", "11:00", by = ME))
        val state = NotesState(h.env)
        assertEquals(2, state.ui.value.unreadCount)
        state.markAllRead()
        assertEquals(listOf("main" to "2026-10-04"), h.api.noteReadAlls)
        assertEquals(0, state.ui.value.unreadCount)
        assertEquals(3, state.ui.value.notes!!.size)
    }

    @Test fun markAllReadOnADayWithNoNotesAsksNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotesState(h.env)
        state.markAllRead()
        assertTrue(h.api.noteReadAlls.isEmpty())
    }

    @Test fun aRefusedMarkIsKeptForTheScreenToWordAndTheNotesStay() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"))
        h.api.markNoteError = ClientError.Api(403, ErrorCode.FORBIDDEN, "no")
        val state = NotesState(h.env)
        state.markRead("a")
        assertIs<Failure.Refused>(state.ui.value.markFailure)
        assertEquals(1, state.ui.value.unreadCount)
        assertTrue(state.ui.value.marking.isEmpty())
        h.api.markNoteError = null
        state.markRead("a")
        assertNull(state.ui.value.markFailure)
    }

    @Test fun nothingIsMarkedWhileOffline() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"))
        val state = NotesState(h.env)
        h.canWrite.value = false
        state.markRead("a")
        state.markAllRead()
        assertTrue(h.api.noteReads.isEmpty() && h.api.noteReadAlls.isEmpty())
        assertFalse(state.ui.value.connected)
    }

    @Test fun aMarkAllAnswerForADayThatIsNoLongerShownIsNotKept() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("today-note"), note("yesterday-note", date = "2026-10-03"))
        val state = NotesState(h.env)
        h.api.markGate = CompletableDeferred()
        state.markAllRead()
        state.previousDay()
        assertEquals(listOf("yesterday-note"), state.ui.value.notes!!.map { it.id })
        h.api.markGate!!.complete(Unit)
        assertEquals(listOf("yesterday-note"), state.ui.value.notes!!.map { it.id }, "the answer was for today")
        assertEquals("2026-10-03", state.ui.value.date)
        assertTrue(state.ui.value.marking.isEmpty())
    }

    @Test fun aMarkFailureForADayThatIsNoLongerShownIsNotShown() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"))
        val state = NotesState(h.env)
        h.api.markGate = CompletableDeferred()
        h.api.markNoteError = ClientError.Unreachable()
        state.markAllRead()
        state.previousDay()
        h.api.markGate!!.complete(Unit)
        assertNull(state.ui.value.markFailure)
    }

    @Test fun afterABranchChangeTheOldBranchsNotesAreGoneEvenWhenTheNewLoadFails() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"), note("m", branch = "market"))
        val state = NotesState(h.env)
        assertEquals(listOf("a"), state.ui.value.notes!!.map { it.id })
        h.api.notesError = ClientError.Unreachable()
        h.branch.value = "market"
        val ui = state.ui.value
        assertEquals("market", ui.branch)
        assertNull(ui.notes, "branch A's notes, and their buttons, are not shown for branch B")
        assertEquals(Failure.Unreachable, ui.failure)
        assertTrue(ui.marking.isEmpty())
    }

    @Test fun aMarkInFlightWhenTheBranchChangesDoesNotLandOnTheNewBranch() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.noteList = listOf(note("a"), note("m", branch = "market"))
        val state = NotesState(h.env)
        h.api.markGate = CompletableDeferred()
        state.markAllRead()
        h.branch.value = "market"
        h.api.markGate!!.complete(Unit)
        assertEquals(listOf("m"), state.ui.value.notes!!.map { it.id })
        assertEquals("market", state.ui.value.branch)
    }

    // ---- posting ----

    @Test fun postingSendsTheTextOnceClearsTheDraftAndShowsTheNote() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotesState(h.env)
        assertFalse(state.ui.value.canPost)
        state.setDraft("  Bl168 held until Friday  ")
        assertTrue(state.ui.value.canPost)
        state.post()
        val sent = h.api.createdNotes.single()
        assertEquals("main", sent.branch)
        assertEquals("Bl168 held until Friday", sent.text)
        assertTrue(xyz.felismp.shoparchive.shared.isUuidV7(sent.id))
        assertEquals("", state.ui.value.draft)
        assertFalse(state.ui.value.posting)
        assertNull(state.ui.value.postFailure)
        assertEquals(listOf(sent.id), state.ui.value.notes!!.map { it.id })
    }

    @Test fun aBlankDraftIsNotPostedAndNeitherIsOneWithoutTheCreateNode() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotesState(h.env)
        state.setDraft("  \n ")
        state.post()
        assertTrue(h.api.createdNotes.isEmpty())

        val reader = harness(backgroundScope, permissions = listOf(PermissionNodes.NOTE_READ))
        val readerState = NotesState(reader.env)
        assertFalse(readerState.ui.value.canCreate)
        readerState.setDraft("hello")
        readerState.post()
        assertTrue(reader.api.createdNotes.isEmpty())
    }

    @Test fun aFailedPostKeepsTheTextAndRetryingSendsTheSameIdAgain() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.createNoteError = ClientError.Unreachable()
        val state = NotesState(h.env)
        state.setDraft("keep me")
        state.post()
        assertEquals(Failure.Unreachable, state.ui.value.postFailure)
        assertEquals("keep me", state.ui.value.draft)
        assertFalse(state.ui.value.posting)
        h.api.createNoteError = null
        state.post()
        assertEquals(2, h.api.createdNotes.size)
        assertEquals(h.api.createdNotes[0].id, h.api.createdNotes[1].id, "a resend is the same note")
        assertNull(state.ui.value.postFailure)
        assertEquals("", state.ui.value.draft)
        assertEquals(1, h.api.noteList.size, "the server holds it once")
    }

    @Test fun otherWordsAfterAFailureAreAnotherNote() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.createNoteError = ClientError.Unreachable()
        val state = NotesState(h.env)
        state.setDraft("first")
        state.post()
        state.setDraft("first, better")
        state.post()
        assertTrue(h.api.createdNotes[0].id != h.api.createdNotes[1].id)
    }

    @Test fun aNewNoteAfterASuccessIsANewId() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotesState(h.env)
        state.setDraft("one")
        state.post()
        state.setDraft("two")
        state.post()
        assertTrue(h.api.createdNotes[0].id != h.api.createdNotes[1].id)
        assertEquals(2, state.ui.value.notes!!.size)
    }

    @Test fun postingTwiceAtOnceSendsOnceAndTheButtonIsOffMeanwhile() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.createNoteGate = CompletableDeferred()
        val state = NotesState(h.env)
        state.setDraft("once")
        state.post()
        assertTrue(state.ui.value.posting)
        assertFalse(state.ui.value.canPost)
        state.post()
        assertEquals(1, h.api.createdNotes.size)
        h.api.createNoteGate!!.complete(Unit)
        assertFalse(state.ui.value.posting)
    }

    @Test fun nothingIsPostedWhileOfflineAndTheTextStays() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        val state = NotesState(h.env)
        h.canWrite.value = false
        state.setDraft("later")
        assertFalse(state.ui.value.canPost)
        state.post()
        assertTrue(h.api.createdNotes.isEmpty())
        assertEquals("later", state.ui.value.draft)
    }

    @Test fun aNoteTheServerPutOnTheOpenDayBeforeIsShownOnThatDay() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(backgroundScope)
        h.api.createdNoteDate = "2026-10-03" // the day is still open after midnight
        val state = NotesState(h.env)
        state.setDraft("after midnight")
        state.post()
        assertEquals("2026-10-03", state.ui.value.date)
        assertEquals(listOf("after midnight"), state.ui.value.notes!!.map { it.text })
    }
}
