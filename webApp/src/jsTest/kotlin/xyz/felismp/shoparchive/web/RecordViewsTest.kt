package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.flow.Capabilities
import xyz.felismp.shoparchive.app.flow.Destination
import xyz.felismp.shoparchive.app.flow.Draft
import xyz.felismp.shoparchive.app.flow.Failure
import xyz.felismp.shoparchive.app.flow.Problem
import xyz.felismp.shoparchive.app.flow.RangeProblem
import xyz.felismp.shoparchive.app.flow.RecordIssue
import xyz.felismp.shoparchive.app.flow.RecordUi
import xyz.felismp.shoparchive.app.flow.SavePhase
import xyz.felismp.shoparchive.app.flow.SlipNotice
import xyz.felismp.shoparchive.app.flow.TenderRow
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CurrencyInfo
import xyz.felismp.shoparchive.shared.EntryDto
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.RecordsPolicy
import xyz.felismp.shoparchive.shared.SlipCheckDto
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import xyz.felismp.shoparchive.shared.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordViewsTest {
    private val lak = CurrencyInfo("LAK", 0)

    private fun draft() = Draft("id", EntryType.INCOME, null, "", "", emptyList(), emptyList())

    private fun ui(
        phase: SavePhase = SavePhase.Editing,
        currencies: List<CurrencyInfo> = listOf(lak),
        pendingBranch: String? = null,
        connected: Boolean = true,
        policy: RecordsPolicy = RecordsPolicy(),
    ) = RecordUi(
        draft(), phase, currencies, emptyList(), emptyList(), policy, emptySet(), emptyList(), null, null, false,
        pendingBranch = pendingBranch, connected = connected,
    )

    private fun phase(ui: RecordUi, branchName: (String) -> String = { it }) = recordPhaseView(ui, { it.key }, branchName)

    private fun entry(type: EntryType = EntryType.INCOME, item: String = "Coffee") = EntryDto(
        "e1", "2026-11-14", type, "main", null, item, "", UserRef("u1", "Noy"),
        "2026-11-14T08:12:30+07:00", "2026-11-14T08:12:30+07:00", false,
        listOf(TenderDto("LAK", TenderMethod.CASH, "1500")), emptyList(),
    )

    // ---- navigation ----

    @Test
    fun theNavShowsOnlyWhatThePersonMayOpen() {
        assertEquals(listOf("nav_today"), navItems(Capabilities.NONE).map { it.label })
        val all = navItems(Capabilities(setOf(PermissionNodes.ENTRY_CREATE, PermissionNodes.ENTRY_VIEW_OWN)))
        assertEquals(listOf("nav_today", "nav_record", "nav_history"), all.map { it.label })
        assertEquals(listOf(Destination.TODAY, Destination.RECORD, Destination.HISTORY), all.map { it.destination })
        assertEquals(listOf("nav_today", "nav_record"), navItems(Capabilities(setOf(PermissionNodes.ENTRY_CREATE))).map { it.label })
        assertEquals(listOf("nav_today", "nav_notes"), navItems(Capabilities(setOf(PermissionNodes.NOTE_CREATE))).map { it.label })
        assertEquals(listOf("nav_today", "nav_notes"), navItems(Capabilities(setOf(PermissionNodes.NOTE_READ))).map { it.label })
    }

    // ---- Record ----

    @Test
    fun theSavePhaseSaysWhatHappened() {
        assertNull(phase(ui()).banner)
        assertEquals("record_saving", phase(ui(SavePhase.Saving)).banner?.key)
        val saved = assertNotNull(phase(ui(SavePhase.Saved(entry()))).banner)
        assertEquals("record_saved_income", saved.key)
        assertTrue(saved.args.single().endsWith(" LAK"), saved.args.single())
        assertEquals("record_saved_expense", phase(ui(SavePhase.Saved(entry(EntryType.EXPENSE)))).banner?.key)
        assertNull(phase(ui(SavePhase.Saved(entry()))).note)
    }

    @Test
    fun anUnknownSaveOffersCheckingAgainOnlyWhileConnected() {
        val view = phase(ui(SavePhase.Unknown(Failure.Unreachable), pendingBranch = "b1")) { "Branch" }
        assertEquals("record_unknown", view.banner?.key)
        assertEquals(Message("record_unknown_branch", listOf("Branch")), view.note)
        assertTrue(view.checkAgain && view.discard && view.checkEnabled)
        assertFalse(phase(ui(SavePhase.Unknown(Failure.Unreachable), connected = false)).checkEnabled)
        assertNull(phase(ui(SavePhase.Unknown(Failure.Unreachable))).note)
    }

    @Test
    fun aRefusedSaveSaysTheServersReasonAndKeepsTheDraft() {
        val view = phase(ui(SavePhase.Failed(Failure.Unreachable)))
        assertEquals("record_failed", view.banner?.key)
        assertEquals(listOf("err_unreachable"), view.banner?.args)
        assertTrue(view.error)
    }

    @Test
    fun everyIssueHasItsOwnWords() {
        val issues = listOf(
            RecordIssue.AMOUNT_MISSING to "issue_amount_missing",
            RecordIssue.AMOUNT_INVALID to "issue_amount_invalid",
            RecordIssue.SLIP_NEEDED to "issue_slip_needed",
            RecordIssue.SLIP_NOT_ALLOWED to "issue_slip_not_allowed",
            RecordIssue.TOO_MANY_SLIPS to "issue_too_many_slips",
            RecordIssue.CATEGORY_NEEDED to "issue_category_needed",
        )
        issues.forEach { (issue, key) -> assertEquals(key, recordIssueKey(issue)) }
        assertEquals(issues.size, issues.map { recordIssueKey(it.first) }.toSet().size)
    }

    @Test
    fun aSlipNoticeUsesThePolicysNumbers() {
        val policy = RecordsPolicy(slipMaxCount = 3, slipMaxSizeKb = 2048)
        assertEquals(Message("notice_too_many", listOf("3")), slipNoticeMessage(SlipNotice.TOO_MANY, policy))
        assertEquals(Message("notice_too_big", listOf("2048")), slipNoticeMessage(SlipNotice.TOO_BIG, policy))
        assertEquals("notice_unreadable", slipNoticeMessage(SlipNotice.UNREADABLE, policy).key)
        assertEquals("notice_no_qr", slipNoticeMessage(SlipNotice.NO_QR, policy).key)
        assertEquals("slip_attached_twice", slipNoticeMessage(SlipNotice.ALREADY_ADDED, policy).key)
        assertEquals("notice_not_confirmed", slipNoticeMessage(SlipNotice.NOT_CONFIRMED, policy).key)
    }

    @Test
    fun aPaymentLineShowsWhatTheServerWillRead() {
        assertTrue(tenderShown(TenderRow(1, "LAK", TenderMethod.CASH, "1500"), listOf(lak))!!.endsWith(" LAK"))
        assertNull(tenderShown(TenderRow(1, "LAK", TenderMethod.CASH, "12x"), listOf(lak)))
        assertNull(tenderShown(TenderRow(1, "USD", TenderMethod.CASH, "12"), listOf(lak)))
        assertNull(tenderShown(TenderRow(1, "LAK", TenderMethod.CASH, ""), listOf(lak)))
    }

    @Test
    fun theBankCheckSaysWhichTransferItIs() {
        val unknown = slipCheckBanner(SlipCheckDto("BCEL", known = false))
        assertEquals("slip_check_unknown", unknown.message.key)
        assertEquals(listOf("BCEL"), unknown.message.args)
        assertTrue(unknown.error)

        val outside = slipCheckBanner(SlipCheckDto("BCEL", known = true, to = "Other", toShop = false))
        assertEquals(Message("slip_check_not_shop", listOf("Other")), outside.message)
        assertTrue(outside.error)

        val known = slipCheckBanner(SlipCheckDto("BCEL", true, amount = "850000", currency = "LAK", time = "2026-09-18 19:18:42", to = "Shop")) { "to $it" }
        assertEquals("slip_check_known", known.message.key)
        assertEquals("BCEL", known.message.args[0])
        assertTrue(known.message.args[1].contains("850") && known.message.args[1].contains("to Shop") && known.message.args[1].contains("2026-09-18 19:18"))
        assertFalse(known.error)
    }

    // ---- History ----

    @Test
    fun theHistoryListKeepsTheServersOrderAndGroupsByDay() {
        val groups = historyGroups(listOf(entry(item = "A"), entry(item = "B"), entry().copy(id = "e2", date = "2026-11-13", item = "C")))
        assertEquals(listOf("2026-11-14", "2026-11-13"), groups.map { it.date })
        assertEquals(listOf("A", "B"), groups[0].entries.map { it.item })
        assertEquals(listOf("C"), groups[1].entries.map { it.item })
    }

    @Test
    fun aBadDateRangeSaysWhy() {
        assertEquals("range_not_a_date", rangeProblemKey(RangeProblem.NOT_A_DATE))
        assertEquals("range_backwards", rangeProblemKey(RangeProblem.BACKWARDS))
        assertEquals("range_too_long", rangeProblemKey(RangeProblem.TOO_LONG))
    }

    @Test
    fun aRowUsesTheItemThenTheCategoryThenADash() {
        val food = CategoryDto("food", LocalizedName("ອາຫານ", "อาหาร", "Food"), AppliesTo.BOTH, archived = false)
        assertEquals("Coffee", historyRowTitle(entry(), listOf(food), "en"))
        assertEquals("Food", historyRowTitle(entry(item = "").copy(category = "food"), listOf(food), "en"))
        assertEquals("อาหาร", historyRowTitle(entry(item = "").copy(category = "food"), listOf(food), "th"))
        assertEquals("—", historyRowTitle(entry(item = ""), listOf(food), "en"))
        assertEquals("gone", historyRowTitle(entry(item = "").copy(category = "gone"), listOf(food), "en"))
    }

    @Test
    fun everyTenderMethodHasWords() {
        assertEquals("method_cash", methodKey(TenderMethod.CASH))
        assertEquals("method_online", methodKey(TenderMethod.ONLINE))
    }

    @Test
    fun aCancelledPinPromptIsNotSaidTwice() {
        assertEquals("err_reauth_cancelled", notDoneMessage(Failure.Client(Problem.ReauthCancelled)).key)
        val other = notDoneMessage(Failure.Unreachable)
        assertEquals("detail_failed", other.key)
        assertEquals(Message("err_unreachable"), other.detail)
    }
}
