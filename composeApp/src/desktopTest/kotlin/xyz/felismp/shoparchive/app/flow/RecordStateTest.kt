package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.app.client.ClientError
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.RecordsPolicy
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.isUuidV7
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecordStateTest {
    /** A compressor that keeps the bytes; an empty file is "not a picture". */
    private val keep = SlipCompressor { raw -> raw.takeIf { it.isNotEmpty() } }

    private fun TestScope.setup(
        policy: RecordsPolicy = RecordsPolicy(),
        compressor: SlipCompressor = keep,
        dayOpen: Boolean = true,
    ): Pair<Harness, RecordState> {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(policy)))
        if (dayOpen) h.api.current = h.api.session("s1")
        return h to RecordState(h.env, TodayState(h.env), compressor)
    }

    private fun RecordState.fillCash(amount: String = "1500") {
        setAmount(ui.value.draft.tenders.first().key, amount)
    }

    @Test fun startsWithOneCashLineInTheFirstCurrencyAndAFreshId() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup()
        val d = record.ui.value.draft
        assertTrue(isUuidV7(d.id))
        assertEquals("LAK", d.tenders.single().currency)
        assertEquals(TenderMethod.CASH, d.tenders.single().method)
        assertEquals(listOf(RecordIssue.AMOUNT_MISSING), record.ui.value.issues)
    }

    @Test fun categoriesFollowTheTypeAndLeaveOutArchivedOnes() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup()
        record.setType(EntryType.INCOME)
        assertEquals(listOf("sales", "misc"), record.ui.value.categories.map { it.key })
        record.setType(EntryType.EXPENSE)
        assertEquals(listOf("stock", "misc"), record.ui.value.categories.map { it.key })
    }

    @Test fun aCategoryThatNoLongerFitsTheTypeIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup()
        record.setType(EntryType.INCOME)
        record.setCategory("sales")
        record.setType(EntryType.EXPENSE)
        assertNull(record.ui.value.draft.category)
        record.setCategory("misc")
        record.setType(EntryType.INCOME)
        assertEquals("misc", record.ui.value.draft.category) // both
    }

    @Test fun aCategoryIsOnlyNeededWhenTheShopSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val (_, optional) = setup()
        optional.fillCash()
        assertTrue(optional.ui.value.canSave)
        val (_, needed) = setup(RecordsPolicy(requireCategory = true))
        needed.fillCash()
        assertEquals(listOf(RecordIssue.CATEGORY_NEEDED), needed.ui.value.issues)
        needed.setCategory("stock")
        assertTrue(needed.ui.value.canSave)
    }

    @Test fun amountsAreCheckedAgainstTheCurrencysDecimals() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup()
        val row = record.ui.value.draft.tenders.first().key
        record.setAmount(row, "1.5") // kip has no decimals
        assertEquals(setOf(row), record.ui.value.badRows)
        assertFalse(record.ui.value.canSave)
        record.setAmount(row, "0")   // an entry must be worth something
        assertEquals(setOf(row), record.ui.value.badRows)
        record.setCurrency(row, "THB")
        record.setAmount(row, "1.5")
        assertTrue(record.ui.value.badRows.isEmpty())
        record.setAmount(row, "1.555")
        assertEquals(setOf(row), record.ui.value.badRows)
        record.setAmount(row, "1,234.50") // grouping commas are fine
        assertTrue(record.ui.value.badRows.isEmpty())
    }

    @Test fun whatWasTypedIsKeptAsTypedAndNeverReinterpreted() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        val row = record.ui.value.draft.tenders.first().key
        for (typed in listOf("1,50", "-700", "1,5", "12a", "1..5", "1.5.0", "12,34")) {
            record.setAmount(row, typed)
            assertEquals(typed, record.ui.value.draft.tenders.first().amount)
            assertEquals(setOf(row), record.ui.value.badRows, typed)
            assertFalse(record.ui.value.canSave, typed)
            record.save()
        }
        assertTrue(h.api.created.isEmpty())
        record.setAmount(row, "2,500")
        record.save()
        assertEquals("2500", h.api.created.single().first.tenders.single().amount)
    }

    @Test fun groupedBahtIsSentInItsOwnDecimals() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        val row = record.ui.value.draft.tenders.first().key
        record.setCurrency(row, "THB")
        record.setAmount(row, "12.345")
        assertEquals(setOf(row), record.ui.value.badRows)
        record.setAmount(row, "1,234.50")
        record.save()
        assertEquals("1234.50", h.api.created.single().first.tenders.single().amount)
    }

    @Test fun linesCanBeAddedAndRemovedButNotTheLast() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup()
        val first = record.ui.value.draft.tenders.single().key
        record.removeTender(first)
        assertEquals(1, record.ui.value.draft.tenders.size)
        record.addTender()
        assertEquals(listOf("LAK", "THB"), record.ui.value.draft.tenders.map { it.currency }) // the next unused currency
        record.removeTender(first)
        assertEquals(listOf("THB"), record.ui.value.draft.tenders.map { it.currency })
    }

    @Test fun slipsAreRequiredExactlyWhenSomethingIsPaidOnline() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup()
        record.fillCash()
        val row = record.ui.value.draft.tenders.first().key
        record.addSlip("a.jpg", ByteArray(10) { 1 })
        assertEquals(listOf(RecordIssue.SLIP_NOT_ALLOWED), record.ui.value.issues) // cash only
        record.setMethod(row, TenderMethod.ONLINE)
        assertTrue(record.ui.value.issues.isEmpty())
        record.removeSlip(record.ui.value.draft.slips.single().key)
        assertEquals(listOf(RecordIssue.SLIP_NEEDED), record.ui.value.issues)
        assertFalse(record.ui.value.canSave)
    }

    @Test fun noMoreSlipsThanTheShopAllows() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup(RecordsPolicy(slipMaxCount = 2))
        repeat(3) { record.addSlip("s$it.jpg", ByteArray(10) { 1 }) }
        assertEquals(2, record.ui.value.draft.slips.size)
        assertEquals(SlipNotice.TOO_MANY, record.ui.value.slipNotice)
        record.removeSlip(record.ui.value.draft.slips.first().key)
        assertNull(record.ui.value.slipNotice)
    }

    @Test fun aSlipStillTooBigAfterCompressionIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup(RecordsPolicy(slipMaxSizeKb = 1))
        record.addSlip("big.jpg", ByteArray(1025))
        assertTrue(record.ui.value.draft.slips.isEmpty())
        assertEquals(SlipNotice.TOO_BIG, record.ui.value.slipNotice)
        record.addSlip("ok.jpg", ByteArray(1024) { 1 })
        assertEquals(1, record.ui.value.draft.slips.size)
        assertNull(record.ui.value.slipNotice)
    }

    @Test fun theSlipSentIsTheCompressedOne() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup(compressor = { byteArrayOf(9, 9) })
        record.fillCash()
        record.setMethod(record.ui.value.draft.tenders.first().key, TenderMethod.ONLINE)
        record.addSlip("photo.png", ByteArray(5_000_000))
        record.save()
        assertEquals(listOf(byteArrayOf(9, 9).toList()), h.api.created.single().second.map { it.toList() })
    }

    @Test fun aFileThatIsNotAPictureIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup()
        record.addSlip("x.txt", ByteArray(0))
        assertEquals(SlipNotice.UNREADABLE, record.ui.value.slipNotice)
        assertTrue(record.ui.value.draft.slips.isEmpty())
    }

    @Test fun savingSendsTheEntryAndStartsAFreshDraft() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        record.setType(EntryType.INCOME)
        record.setCategory("sales")
        record.setItem("  Rice  ")
        record.setNote("note ")
        record.fillCash("12000")
        record.addTender()
        val second = record.ui.value.draft.tenders[1].key
        record.setAmount(second, "7.5")
        val id = record.ui.value.draft.id
        record.save()
        val (request, slips) = h.api.created.single()
        assertEquals(id, request.id)
        assertEquals("main", request.branch)
        assertEquals(EntryType.INCOME, request.type)
        assertEquals("sales", request.category)
        assertEquals("Rice", request.item)
        assertEquals("note", request.note)
        assertEquals(listOf(TenderDto("LAK", TenderMethod.CASH, "12000"), TenderDto("THB", TenderMethod.CASH, "7.50")), request.tenders)
        assertTrue(slips.isEmpty())
        assertIs<SavePhase.Saved>(record.ui.value.phase)
        assertNotEquals(id, record.ui.value.draft.id)
        assertEquals(EntryType.INCOME, record.ui.value.draft.type)
        assertEquals("", record.ui.value.draft.item)
    }

    @Test fun savingTellsTodayToLoadAgain() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        val before = h.api.dashboardCalls.size
        record.fillCash()
        record.save()
        assertEquals(before + 1, h.api.dashboardCalls.size)
    }

    @Test fun whileSavingASecondPressDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        h.api.createGate = CompletableDeferred()
        record.fillCash()
        record.save()
        assertEquals(SavePhase.Saving, record.ui.value.phase)
        record.save()
        assertEquals(1, h.api.created.size)
        h.api.createGate!!.complete(Unit)
        assertIs<SavePhase.Saved>(record.ui.value.phase)
    }

    @Test fun anAnswerThatNeverCameIsUnknownAndCheckingAgainSendsTheSameId() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        h.api.createFails = { n -> if (n == 1) ClientError.Unreachable() else null }
        record.fillCash()
        val id = record.ui.value.draft.id
        record.save()
        assertEquals(Failure.Unreachable, assertIs<SavePhase.Unknown>(record.ui.value.phase).failure)
        // The draft is frozen: an edit now could not be told apart from the entry that may be stored.
        record.setItem("changed")
        assertEquals("", record.ui.value.draft.item)
        assertEquals(id, record.ui.value.draft.id)
        record.checkAgain()
        assertEquals(listOf(id, id), h.api.created.map { it.first.id })
        assertIs<SavePhase.Saved>(record.ui.value.phase)
        assertNotEquals(id, record.ui.value.draft.id)
    }

    @Test fun checkingAgainAfterSwitchingBranchResendsTheFirstRequestForTheFirstBranch() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        h.api.createFails = { n -> if (n == 1) ClientError.Unreachable() else null }
        record.fillCash()
        record.setMethod(record.ui.value.draft.tenders.first().key, TenderMethod.ONLINE)
        record.addSlip("a.jpg", byteArrayOf(1, 2, 3))
        record.save()
        assertEquals("main", record.ui.value.pendingBranch)
        h.branch.value = "second"
        record.setAmount(record.ui.value.draft.tenders.first().key, "999") // frozen: ignored
        record.checkAgain()
        val (first, second) = h.api.created
        assertEquals("main", second.first.branch)
        assertEquals(first.first, second.first)
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), second.second.single().toList())
        assertIs<SavePhase.Saved>(record.ui.value.phase)
        assertNull(record.ui.value.pendingBranch)
    }

    @Test fun anEntryThatLandedBeforeTheAnswerWasLostIsNotRecordedTwice() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        h.api.storeThenFail = true
        record.fillCash()
        record.save()
        assertIs<SavePhase.Unknown>(record.ui.value.phase)
        record.checkAgain()
        assertIs<SavePhase.Saved>(record.ui.value.phase)
        assertEquals(1, h.api.stored.size)
    }

    @Test fun checkingAgainOnlyNeedsTheConnectionNotTheOpenDayOfTheBranchShown() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig()))
        h.api.current = h.api.session("s1")
        val today = TodayState(h.env)
        val record = RecordState(h.env, today, keep)
        h.api.createFails = { n -> if (n == 1) ClientError.Unreachable() else null }
        record.fillCash()
        record.save()
        assertIs<SavePhase.Unknown>(record.ui.value.phase)
        // The day of the branch shown now is closed, so Save is off, but the unknown entry can still be checked.
        h.api.current = null
        today.reload()
        assertEquals(SaveBlock.NO_DAY, record.ui.value.block)
        assertTrue(record.ui.value.connected)
        h.canWrite.value = false
        assertFalse(record.ui.value.connected)
        h.canWrite.value = true
        record.checkAgain()
        assertIs<SavePhase.Saved>(record.ui.value.phase)
    }

    @Test fun checkingAgainOnlyWorksWhileTheResultIsUnknown() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        record.fillCash()
        record.checkAgain()
        assertTrue(h.api.created.isEmpty())
    }

    @Test fun droppingAnUnknownDraftStartsOverWithANewId() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        h.api.createFails = { ClientError.Unreachable() }
        record.fillCash()
        val id = record.ui.value.draft.id
        record.save()
        record.discardDraft()
        assertNotEquals(id, record.ui.value.draft.id)
        assertEquals(SavePhase.Editing, record.ui.value.phase)
        assertEquals("", record.ui.value.draft.tenders.single().amount)
    }

    @Test fun aRefusalKeepsTheDraftAndShowsTheServersWords() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        h.api.createFails = { n -> if (n == 1) ClientError.Api(409, ErrorCode.NO_OPEN_SESSION, "No day is open in this branch.", reason = ErrorReasons.SESSION_NO_OPEN_DAY) else null }
        record.setItem("Rice")
        record.fillCash()
        val id = record.ui.value.draft.id
        record.save()
        assertEquals(Failure.Refused(ErrorCode.NO_OPEN_SESSION, ErrorReasons.SESSION_NO_OPEN_DAY), assertIs<SavePhase.Failed>(record.ui.value.phase).failure)
        assertEquals("Rice", record.ui.value.draft.item)
        assertEquals(id, record.ui.value.draft.id)
        record.setItem("Rice paper") // still editable
        assertEquals(SavePhase.Editing, record.ui.value.phase)
        record.save()
        assertIs<SavePhase.Saved>(record.ui.value.phase)
    }

    @Test fun saveIsOffWhileOffline() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        record.fillCash()
        assertTrue(record.ui.value.canSave)
        h.canWrite.value = false
        assertEquals(SaveBlock.OFFLINE, record.ui.value.block)
        assertFalse(record.ui.value.canSave)
        record.save()
        assertTrue(h.api.created.isEmpty())
    }

    @Test fun saveIsOffUntilTheDayIsOpenWhenTheShopRequiresIt() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup(dayOpen = false)
        record.fillCash()
        assertEquals(SaveBlock.NO_DAY, record.ui.value.block)
        record.save()
        assertTrue(h.api.created.isEmpty())
        h.api.current = h.api.session("s1")
        h.refetch.tryEmit(Unit)
        assertNull(record.ui.value.block)
        assertTrue(record.ui.value.canSave)
    }

    @Test fun withoutTheRuleASaveNeedsNoOpenDay() = runTest(UnconfinedTestDispatcher()) {
        val (_, record) = setup(RecordsPolicy(requireOpenDay = false), dayOpen = false)
        record.fillCash()
        assertNull(record.ui.value.block)
    }

    @Test fun saveWaitsForTheConfigAndForABranch() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Loading, branch = null)
        val record = RecordState(h.env, TodayState(h.env), keep)
        assertEquals(SaveBlock.LOADING, record.ui.value.block)
        h.config.value = ConfigState.Ready(testConfig())
        assertEquals(SaveBlock.NO_BRANCH, record.ui.value.block)
        // The first currency fills in once the config is there.
        assertEquals("LAK", record.ui.value.draft.tenders.single().currency)
    }

    @Test fun itemSuggestionsComeFromTheCategoryAndFollowTheTypedText() = runTest(UnconfinedTestDispatcher()) {
        val (h, record) = setup()
        record.setCategory("stock")
        assertEquals("stock", h.api.itemCalls.last())
        assertEquals(listOf("Rice", "Rice paper", "Noodles"), record.ui.value.suggestions)
        record.setItem("ric")
        assertEquals(listOf("Rice", "Rice paper"), record.ui.value.suggestions)
        record.setItem("Rice")
        assertEquals(listOf("Rice paper"), record.ui.value.suggestions)
    }
}
