package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.app.client.EntryFilter
import xyz.felismp.shoparchive.shared.EntryDeletedMessage
import xyz.felismp.shoparchive.shared.EntryMovedMessage
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.EntryUpdatedMessage
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.HistoryChange
import xyz.felismp.shoparchive.shared.HistoryItem
import xyz.felismp.shoparchive.shared.RecordsPolicy
import xyz.felismp.shoparchive.shared.SlipDto
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryStateTest {
    private val keep = SlipCompressor { raw -> raw.takeIf { it.isNotEmpty() } }

    private fun TestScope.setup(policy: RecordsPolicy = RecordsPolicy()): Pair<Harness, HistoryState> {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(policy)))
        return h to HistoryState(h.env, keep)
    }

    private fun refused(status: Int, code: ErrorCode, message: String, reason: String? = null) = ClientError.Api(status, code, message, reason = reason)

    // ---- the list ----

    @Test fun startsOnThisMonthForEveryBranchAndAsksTheServerForIt() = runTest(UnconfinedTestDispatcher()) {
        val (h, history) = setup()
        assertEquals(EntryFilter("2026-10-01", "2026-10-31"), h.api.entryFilters.single())
        assertEquals("2026-10-01", history.ui.value.from)
        assertEquals(emptyList(), history.ui.value.entries)
        assertFalse(history.ui.value.loading)
    }

    @Test fun nothingIsAskedForWhileHistoryIsHiddenAndTheListLoadsOnceViewAccessComes() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(permissions = listOf(xyz.felismp.shoparchive.shared.PermissionNodes.ENTRY_CREATE))))
        val history = HistoryState(h.env, keep)
        assertEquals(emptyList(), h.api.entryFilters, "no view node: no call")
        history.reload()
        assertEquals(emptyList(), h.api.entryFilters)
        h.config.value = ConfigState.Ready(testConfig(permissions = listOf(xyz.felismp.shoparchive.shared.PermissionNodes.ENTRY_VIEW_OWN)))
        assertEquals(1, h.api.entryFilters.size)
        assertEquals(emptyList(), history.ui.value.entries)
    }

    @Test fun filtersBecomeQueryParametersAndOnlyTheSetOnesAreSent() = runTest(UnconfinedTestDispatcher()) {
        val (h, history) = setup()
        history.setFrom("2026-09-01")
        history.setTo("2026-09-30")
        history.setBranch("main")
        history.setType(EntryType.EXPENSE)
        history.setCurrency("THB")
        history.setCategory("stock")
        history.setIncludeDeleted(true)
        val last = h.api.entryFilters.last()
        assertEquals(EntryFilter("2026-09-01", "2026-09-30", "main", EntryType.EXPENSE, "THB", "stock", true), last)
        assertEquals("from=2026-09-01&to=2026-09-30&branch=main&type=expense&currency=THB&category=stock&includeDeleted=true", last.query())
        assertEquals("from=2026-10-01&to=2026-10-31", EntryFilter("2026-10-01", "2026-10-31").query())
        assertEquals("from=a&to=b&category=a%20b%26c", EntryFilter("a", "b", category = "a b&c").query())
    }

    @Test fun aRangeTheServerWouldRefuseIsNotAskedFor() = runTest(UnconfinedTestDispatcher()) {
        val (h, history) = setup()
        val asked = h.api.entryFilters.size
        history.setFrom("2026-1-1")
        assertEquals(RangeProblem.NOT_A_DATE, history.ui.value.rangeProblem)
        history.setFrom("2026-11-02")
        assertEquals(RangeProblem.BACKWARDS, history.ui.value.rangeProblem)
        history.setFrom("2025-10-01") // 396 days
        assertEquals(RangeProblem.TOO_LONG, history.ui.value.rangeProblem)
        assertEquals(asked, h.api.entryFilters.size)
        history.setFrom("2025-10-31") // exactly 366 days with both ends
        assertNull(history.ui.value.rangeProblem)
        assertEquals("2025-10-31", h.api.entryFilters.last().from)
    }

    @Test fun theListIsNewestFirst() = runTest(UnconfinedTestDispatcher()) {
        val (h, history) = setup()
        h.api.listed = listOf(
            testEntry("a", "2026-10-02"), testEntry("b", "2026-10-04", createdAt = "2026-10-04T08:00:00+07:00"),
            testEntry("c", "2026-10-04", createdAt = "2026-10-04T09:30:00+07:00"),
        )
        history.reload()
        assertEquals(listOf("c", "b", "a"), history.ui.value.entries!!.map { it.id })
    }

    @Test fun deletedEntriesComeWithTheirBadgeFlag() = runTest(UnconfinedTestDispatcher()) {
        val (h, history) = setup()
        h.api.listed = listOf(testEntry("a", deleted = true))
        history.setIncludeDeleted(true)
        assertTrue(history.ui.value.entries!!.single().deleted)
        assertTrue(h.api.entryFilters.last().includeDeleted)
    }

    @Test fun aFailedLoadKeepsWhatWasShownAndSaysWhy() = runTest(UnconfinedTestDispatcher()) {
        val (h, history) = setup()
        h.api.listed = listOf(testEntry("a"))
        history.reload()
        h.api.listError = refused(403, ErrorCode.FORBIDDEN, "This needs the permission shoparchive.entry.view.own.", ErrorReasons.PERMISSION_MISSING)
        history.reload()
        assertEquals(listOf("a"), history.ui.value.entries!!.map { it.id })
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), history.ui.value.failure)
        h.api.listError = null
        history.reload()
        assertNull(history.ui.value.failure)
    }

    @Test fun liveEntryMessagesAndAReconnectLoadAgain() = runTest(UnconfinedTestDispatcher()) {
        val (h, _) = setup()
        val first = h.api.entryFilters.size
        h.messages.tryEmit(EntryUpdatedMessage("x", "2026-10-04", "other"))
        assertEquals(first + 1, h.api.entryFilters.size)
        h.refetch.tryEmit(Unit)
        assertEquals(first + 2, h.api.entryFilters.size)
    }

    // ---- one entry ----

    private fun TestScope.openEntry(
        entry: xyz.felismp.shoparchive.shared.EntryDto = testEntry(slips = listOf(SlipDto("slip-1.jpg", "x"), SlipDto("slip-2.jpg", "y"))),
        policy: RecordsPolicy = RecordsPolicy(),
    ): Triple<Harness, HistoryState, EntryDetailState> {
        val (h, history) = setup(policy)
        h.api.listed = listOf(entry)
        h.api.historyItems = listOf(HistoryItem("2026-10-04T09:00:00+07:00", h.api.alice, "create", emptyList()))
        history.reload()
        history.open(history.ui.value.entries!!.single())
        return Triple(h, history, history.detail.value!!)
    }

    @Test fun anEntryLoadsItsSlipsAndItsHistory() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry()
        h.api.slipBytes = mapOf(1 to byteArrayOf(9), 2 to byteArrayOf(8))
        val ui = d.ui.value
        assertEquals(setOf(1, 2), ui.slips.keys)
        assertIs<Load.Ready<ByteArray>>(ui.slips.getValue(1))
        assertEquals("create", assertIs<Load.Ready<List<HistoryItem>>>(ui.history).value.single().action)
    }

    @Test fun aSlipOrHistoryThatCannotBeReadFailsOnItsOwn() = runTest(UnconfinedTestDispatcher()) {
        val (h, history) = setup()
        h.api.slipError = refused(409, ErrorCode.ENTRY_BROKEN, "The file slip-1.jpg of this entry is missing.")
        h.api.historyError = ClientError.Unreachable()
        h.api.listed = listOf(testEntry(slips = listOf(SlipDto("slip-1.jpg", "x"))))
        history.reload()
        history.open(history.ui.value.entries!!.single())
        val ui = history.detail.value!!.ui.value
        assertEquals(Failure.Refused(ErrorCode.ENTRY_BROKEN), assertIs<Load.Failed>(ui.slips.getValue(1)).failure)
        assertEquals(Failure.Unreachable, assertIs<Load.Failed>(ui.history).failure)
    }

    @Test fun editingStartsFromTheStoredValuesAndSendsWhatWasTyped() = runTest(UnconfinedTestDispatcher()) {
        val entry = testEntry(
            tenders = listOf(TenderDto("LAK", TenderMethod.CASH, "150000"), TenderDto("THB", TenderMethod.ONLINE, "120.50")),
            slips = listOf(SlipDto("slip-1.jpg", "x")),
        )
        val (h, _, d) = openEntry(entry)
        d.startEdit()
        val form = d.ui.value.edit!!.form
        assertEquals(listOf("150000", "120.50"), form.tenders.map { it.amount })
        assertEquals(listOf("slip-1.jpg"), form.keep.map { it.file })
        d.setItem("Big ice")
        d.setAmount(form.tenders[0].key, "2,500")
        d.setAmount(form.tenders[1].key, "99.9")
        d.saveEdit()
        val (id, request, slips) = h.api.updates.single()
        assertEquals("e1", id)
        assertEquals("Big ice", request.item)
        assertEquals(listOf(TenderDto("LAK", TenderMethod.CASH, "2500"), TenderDto("THB", TenderMethod.ONLINE, "99.90")), request.tenders)
        assertEquals(listOf("slip-1.jpg"), request.keepSlips)
        assertTrue(slips.isEmpty())
        assertNull(d.ui.value.edit)
        assertEquals("Big ice", d.ui.value.entry.item)
    }

    @Test fun amountsAreCheckedLikeOnRecordAndNothingIsSentWhileTheyAreWrong() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        d.startEdit()
        val row = d.ui.value.edit!!.form.tenders.single().key
        for (typed in listOf("1,50", "-5", "0", "12.5", "x")) {
            d.setAmount(row, typed)
            assertTrue(RecordIssue.AMOUNT_INVALID in d.ui.value.edit!!.issues, typed)
            d.saveEdit()
        }
        d.setAmount(row, "")
        assertTrue(RecordIssue.AMOUNT_MISSING in d.ui.value.edit!!.issues)
        d.saveEdit()
        assertTrue(h.api.updates.isEmpty())
    }

    @Test fun storedSlipsCanBeKeptOrRemovedAndNewOnesAdded() = runTest(UnconfinedTestDispatcher()) {
        val entry = testEntry(
            tenders = listOf(TenderDto("LAK", TenderMethod.ONLINE, "5000")),
            slips = listOf(SlipDto("slip-1.jpg", "x"), SlipDto("slip-2.jpg", "y")),
        )
        val (h, _, d) = openEntry(entry)
        d.startEdit()
        d.removeStoredSlip("slip-1.jpg")
        d.addSlip("new.jpg", byteArrayOf(7, 7))
        d.addSlip("bad.jpg", byteArrayOf()) // not a picture
        assertEquals(SlipNotice.UNREADABLE, d.ui.value.edit!!.notice)
        d.saveEdit()
        val (_, request, slips) = h.api.updates.single()
        assertEquals(listOf("slip-2.jpg"), request.keepSlips)
        assertEquals(listOf(listOf<Byte>(7, 7)), slips.map { it.toList() })
        assertEquals(listOf("slip-2.jpg", "slip-3.jpg"), d.ui.value.entry.slips.map { it.file })
    }

    @Test fun anOnlinePaymentNeedsAtLeastOneSlipLeftAndCashAllowsNone() = runTest(UnconfinedTestDispatcher()) {
        val entry = testEntry(tenders = listOf(TenderDto("LAK", TenderMethod.ONLINE, "5000")), slips = listOf(SlipDto("slip-1.jpg", "x")))
        val (h, _, d) = openEntry(entry)
        d.startEdit()
        d.removeStoredSlip("slip-1.jpg")
        assertTrue(RecordIssue.SLIP_NEEDED in d.ui.value.edit!!.issues)
        d.saveEdit()
        assertTrue(h.api.updates.isEmpty())
        d.setMethod(d.ui.value.edit!!.form.tenders.single().key, TenderMethod.CASH)
        assertTrue(d.ui.value.edit!!.issues.isEmpty())
    }

    @Test fun tooManySlipsAreNotAdded() = runTest(UnconfinedTestDispatcher()) {
        val (_, _, d) = openEntry(testEntry(tenders = listOf(TenderDto("LAK", TenderMethod.ONLINE, "5000")), slips = listOf(SlipDto("slip-1.jpg", "x"))), RecordsPolicy(slipMaxCount = 2))
        d.startEdit()
        d.addSlip("a.jpg", byteArrayOf(1))
        d.addSlip("b.jpg", byteArrayOf(2))
        assertEquals(SlipNotice.TOO_MANY, d.ui.value.edit!!.notice)
        assertEquals(1, d.ui.value.edit!!.form.added.size)
    }

    @Test fun theEditSendsTheVersionItStartedFromAndAConflictKeepsWhatWasTypedBesideTheNewerEntry() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        d.startEdit()
        d.setItem("Mine")
        // Another device changes the note meanwhile; the server then refuses the edit.
        h.api.stored["e1"] = testEntry(slips = emptyList()).copy(note = "theirs", updatedAt = "2026-10-04T10:00:00+07:00")
        h.api.changeError = refused(409, ErrorCode.CONFLICT, "This entry was changed on another device. Load it again, then make your change.", ErrorReasons.ENTRY_CHANGED_ELSEWHERE)
        d.saveEdit()
        assertEquals("2026-10-04T09:00:00+07:00", h.api.updates.single().second.expectedUpdatedAt)
        assertEquals(Failure.Refused(ErrorCode.CONFLICT, ErrorReasons.ENTRY_CHANGED_ELSEWHERE), assertIs<ActionPhase.Failed>(d.ui.value.action).failure)
        assertEquals("theirs", d.ui.value.entry.note) // the newer entry is read
        assertEquals("Mine", d.ui.value.edit!!.form.item) // what was typed stays
        assertTrue(d.ui.value.edit!!.stale)
        h.api.changeError = null
        d.saveEdit() // a stale form is never sent over the newer entry
        assertEquals(1, h.api.updates.size)
    }

    @Test fun aChangeFromAnotherDeviceWhileEditingMakesTheFormStale() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        d.startEdit()
        assertFalse(d.ui.value.edit!!.stale)
        h.api.stored["e1"] = testEntry(slips = emptyList()).copy(note = "theirs", updatedAt = "2026-10-04T10:00:00+07:00")
        h.messages.tryEmit(EntryUpdatedMessage("e1", "2026-10-04", "main"))
        assertTrue(d.ui.value.edit!!.stale)
    }

    @Test fun aRefusedEditKeepsTheFormAndSaysWhy() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        h.api.changeError = refused(403, ErrorCode.FORBIDDEN, "That entry is past the edit window; changing it needs shoparchive.entry.edit.all.", ErrorReasons.ENTRY_PAST_WINDOW)
        d.startEdit()
        d.setItem("Late")
        d.saveEdit()
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.ENTRY_PAST_WINDOW), assertIs<ActionPhase.Failed>(d.ui.value.action).failure)
        assertEquals("Late", d.ui.value.edit!!.form.item)
        assertEquals("Ice", d.ui.value.entry.item)
    }

    @Test fun editingAndSendingNeedTheConnection() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        d.startEdit()
        d.setItem("Offline")
        h.canWrite.value = false
        d.saveEdit()
        assertTrue(h.api.updates.isEmpty())
        d.askDelete()
        d.confirmDelete()
        assertTrue(h.api.deletes.isEmpty())
    }

    @Test fun deletingAsksFirstAndThenMarksTheEntryDeleted() = runTest(UnconfinedTestDispatcher()) {
        val (h, history, d) = openEntry(testEntry(slips = emptyList()))
        val loads = h.api.entryFilters.size
        d.confirmDelete() // not asked yet
        assertTrue(h.api.deletes.isEmpty())
        d.askDelete()
        assertTrue(d.ui.value.confirmDelete)
        d.cancelDelete()
        d.confirmDelete()
        assertTrue(h.api.deletes.isEmpty())
        d.askDelete()
        d.confirmDelete()
        assertEquals(listOf("e1"), h.api.deletes)
        assertTrue(d.ui.value.entry.deleted)
        assertFalse(d.ui.value.confirmDelete)
        assertTrue(h.api.entryFilters.size > loads) // the list is read again
        d.askDelete() // nothing more to do to a deleted entry
        d.startEdit()
        assertFalse(d.ui.value.confirmDelete)
        assertNull(d.ui.value.edit)
        history.closeDetail()
        assertNull(history.detail.value)
    }

    @Test fun aRefusedDeleteStaysAsked() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        h.api.changeError = refused(403, ErrorCode.FORBIDDEN, "Deleting someone else's entry needs shoparchive.entry.delete.all.", ErrorReasons.PERMISSION_MISSING)
        d.askDelete()
        d.confirmDelete()
        assertEquals(Failure.Refused(ErrorCode.FORBIDDEN, ErrorReasons.PERMISSION_MISSING), assertIs<ActionPhase.Failed>(d.ui.value.action).failure)
        assertTrue(d.ui.value.confirmDelete)
        assertFalse(d.ui.value.entry.deleted)
    }

    @Test fun movingNeedsARealDateAndKeepsReadingTheEntryOnItsNewDay() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        d.openMove()
        assertEquals("2026-10-04", d.ui.value.moveText)
        d.setMoveDate("2026-13-01")
        assertFalse(d.ui.value.moveValid)
        d.confirmMove()
        assertTrue(h.api.moves.isEmpty())
        d.setMoveDate(" 2026-10-02 ")
        d.confirmMove()
        assertEquals(listOf("e1" to "2026-10-02"), h.api.moves)
        assertEquals("2026-10-02", d.ui.value.entry.date)
        assertNull(d.ui.value.moveText)
    }

    @Test fun aRefusedMoveShowsTheReasonAndStaysOpen() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        h.api.changeError = refused(403, ErrorCode.FORBIDDEN, "That day is past the edit window; moving an entry there needs shoparchive.entry.edit.all.")
        d.openMove()
        d.setMoveDate("2026-08-01")
        d.confirmMove()
        assertIs<Failure.Refused>(assertIs<ActionPhase.Failed>(d.ui.value.action).failure)
        assertEquals("2026-08-01", d.ui.value.moveText)
        assertEquals("2026-10-04", d.ui.value.entry.date)
    }

    @Test fun aCancelledPinPromptOnDeleteIsReportedInTheAppsWordsNotTheExceptions() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        h.api.changeError = ClientError.ReauthCancelled()
        d.askDelete()
        d.confirmDelete()
        assertEquals(Failure.Client(Problem.ReauthCancelled), assertIs<ActionPhase.Failed>(d.ui.value.action).failure)
        assertTrue(d.ui.value.confirmDelete)
    }

    @Test fun aCancelledPinPromptOnMoveIsReportedInTheAppsWordsAndTheMoveStaysOpen() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        h.api.changeError = ClientError.ReauthCancelled()
        d.openMove()
        d.setMoveDate("2026-08-01")
        d.confirmMove()
        assertEquals(Failure.Client(Problem.ReauthCancelled), assertIs<ActionPhase.Failed>(d.ui.value.action).failure)
        assertEquals("2026-08-01", d.ui.value.moveText)
    }

    @Test fun anotherDevicesChangeToThisEntryIsReadAgain() = runTest(UnconfinedTestDispatcher()) {
        val (h, _, d) = openEntry(testEntry(slips = emptyList()))
        h.api.stored["e1"] = testEntry(slips = emptyList()).copy(item = "Changed elsewhere")
        h.messages.tryEmit(EntryUpdatedMessage("e1", "2026-10-04", "main"))
        assertEquals("Changed elsewhere", d.ui.value.entry.item)
        h.api.stored["e1"] = h.api.stored.getValue("e1").copy(deleted = true)
        h.messages.tryEmit(EntryDeletedMessage("e1", "2026-10-04", "main"))
        assertTrue(d.ui.value.entry.deleted)
        h.api.stored["e1"] = h.api.stored.getValue("e1").copy(date = "2026-10-01")
        h.messages.tryEmit(EntryMovedMessage("e1", "2026-10-01", "2026-10-04", "main"))
        assertEquals("2026-10-01" to "e1", h.api.entryReads.last())
        assertEquals("2026-10-01", d.ui.value.entry.date)
        val reads = h.api.entryReads.size
        h.messages.tryEmit(EntryUpdatedMessage("someone-else", "2026-10-01", "main"))
        assertEquals(reads, h.api.entryReads.size)
        h.refetch.tryEmit(Unit)
        assertEquals(reads + 1, h.api.entryReads.size)
    }

    @Test fun closingTheDetailStopsItListening() = runTest(UnconfinedTestDispatcher()) {
        val (h, history, _) = openEntry(testEntry(slips = emptyList()))
        history.closeDetail()
        val reads = h.api.entryReads.size
        h.messages.tryEmit(EntryUpdatedMessage("e1", "2026-10-04", "main"))
        assertEquals(reads, h.api.entryReads.size)
    }
}
