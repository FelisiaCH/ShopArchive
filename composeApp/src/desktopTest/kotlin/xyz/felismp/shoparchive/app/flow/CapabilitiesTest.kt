package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import xyz.felismp.shoparchive.shared.PermissionNodes
import xyz.felismp.shoparchive.shared.RecordsPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What each permission node shows or hides, at the level the screens read it. */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesTest {
    private fun caps(vararg nodes: String) = Capabilities(nodes.toSet())

    private val clerk = arrayOf(PermissionNodes.ENTRY_CREATE, PermissionNodes.ENTRY_VIEW_OWN, PermissionNodes.DAY_OPEN)

    @Test fun aClerkSeesNoReportsAndNoCloseDay() {
        val c = caps(*clerk)
        assertFalse(c.reports)
        assertEquals(emptyList(), c.reportsTabs)
        assertFalse(c.closeDay || c.dashboard || c.export)
        assertFalse(c.allows(Destination.REPORTS))
        assertTrue(c.allows(Destination.TODAY) && c.allows(Destination.RECORD) && c.allows(Destination.HISTORY) && c.allows(Destination.OPEN_DAY) && c.allows(Destination.MORE))
    }

    @Test fun eachReportsTabNeedsItsOwnNode() {
        assertEquals(listOf(ReportsTab.OVERVIEW), caps(PermissionNodes.DASHBOARD_VIEW).reportsTabs)
        assertEquals(listOf(ReportsTab.CLOSE_DAY), caps(PermissionNodes.DAY_CLOSE).reportsTabs)
        assertEquals(listOf(ReportsTab.EXPORT), caps(PermissionNodes.EXPORT).reportsTabs)
        assertEquals(
            listOf(ReportsTab.OVERVIEW, ReportsTab.CLOSE_DAY, ReportsTab.EXPORT),
            caps(PermissionNodes.EXPORT, PermissionNodes.DAY_CLOSE, PermissionNodes.DASHBOARD_VIEW).reportsTabs,
        )
        assertTrue(caps(PermissionNodes.EXPORT).allows(Destination.REPORTS), "Reports stays while one tab remains")
    }

    @Test fun recordingAndHistoryAndOpeningTheDayFollowTheirNodes() {
        val none = caps()
        assertFalse(none.allows(Destination.RECORD) || none.allows(Destination.HISTORY) || none.allows(Destination.OPEN_DAY))
        assertTrue(caps(PermissionNodes.ENTRY_VIEW_ALL).allows(Destination.HISTORY))
        assertTrue(caps(PermissionNodes.ENTRY_VIEW_OWN).allows(Destination.HISTORY))
    }

    @Test fun theConsoleNeedsTheNodeOfAtLeastOneCommand() {
        assertFalse(caps(*clerk).console || caps(*clerk).allows(Destination.CONSOLE))
        assertTrue(caps("shoparchive.command.status").allows(Destination.CONSOLE))
        assertFalse(caps("shoparchive.commands").console, "only the node prefix counts")
    }

    @Test fun nothingIsAllowedBeforeTheConfigHasLoaded() {
        assertFalse(ConfigState.Loading.capabilities().recordEntries)
        assertTrue(ConfigState.Ready(testConfig(permissions = listOf(PermissionNodes.EXPORT))).capabilities().export)
    }

    // ---- changing an entry ----

    private val today = java.time.LocalDate.parse("2026-10-04")
    private fun mine(date: String = "2026-10-04") = testEntry(date = date) // made by alice
    private fun theirs(date: String = "2026-10-04") = testEntry(date = date).let { it.copy(createdBy = xyz.felismp.shoparchive.shared.UserRef("u2", "bob")) }

    @Test fun anOwnEntryCanBeChangedWithTheOwnNodeWithinTheWindow() {
        val c = Capabilities(setOf(PermissionNodes.ENTRY_EDIT_OWN, PermissionNodes.ENTRY_DELETE_OWN), editWindowDays = 2, userId = "u1")
        assertTrue(c.canEdit(mine(), today) && c.canDelete(mine(), today))
        assertTrue(c.canEdit(mine("2026-10-02"), today), "the last day of the window")
        assertFalse(c.canEdit(mine("2026-10-01"), today), "past the window")
        assertFalse(c.canDelete(mine("2026-10-01"), today))
    }

    @Test fun ownershipIsTheUserIdNotTheName() {
        val c = Capabilities(setOf(PermissionNodes.ENTRY_EDIT_OWN, PermissionNodes.ENTRY_DELETE_OWN), editWindowDays = 2, userId = "u1")
        val sameNameOtherPerson = testEntry().copy(createdBy = xyz.felismp.shoparchive.shared.UserRef("u9", "alice"))
        val renamedSincePerson = testEntry().copy(createdBy = xyz.felismp.shoparchive.shared.UserRef("u1", "alice-before-the-rename"))
        assertFalse(c.canEdit(sameNameOtherPerson, today) || c.canDelete(sameNameOtherPerson, today), "a reused name is not mine")
        assertTrue(c.canEdit(renamedSincePerson, today) && c.canDelete(renamedSincePerson, today), "a renamed user still owns theirs")
        assertFalse(Capabilities(setOf(PermissionNodes.ENTRY_EDIT_OWN)).canEdit(mine(), today), "no id known: nothing is claimed as own")
    }

    @Test fun someoneElsesEntryNeedsTheAllNodeWhateverTheDay() {
        val own = Capabilities(setOf(PermissionNodes.ENTRY_EDIT_OWN, PermissionNodes.ENTRY_DELETE_OWN), editWindowDays = 30, userId = "u1")
        assertFalse(own.canEdit(theirs(), today) || own.canDelete(theirs(), today))
        val all = Capabilities(setOf(PermissionNodes.ENTRY_EDIT_ALL, PermissionNodes.ENTRY_DELETE_ALL))
        assertTrue(all.canEdit(theirs("2020-01-01"), today) && all.canDelete(theirs("2020-01-01"), today))
        assertTrue(all.canEdit(mine("2020-01-01"), today), "all covers an own entry past the window")
    }

    @Test fun editAndDeleteFollowTheirOwnNodes() {
        val editOnly = Capabilities(setOf(PermissionNodes.ENTRY_EDIT_OWN), userId = "u1")
        assertTrue(editOnly.canEdit(mine(), today))
        assertFalse(editOnly.canDelete(mine(), today))
        val none = Capabilities(setOf(PermissionNodes.ENTRY_CREATE))
        assertFalse(none.canEdit(mine(), today) || none.canDelete(mine(), today))
    }

    @Test fun theWindowComesFromTheConfigAndTheDetailHidesWhatIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val own = listOf(PermissionNodes.ENTRY_VIEW_OWN, PermissionNodes.ENTRY_EDIT_OWN)
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(RecordsPolicy(editWindowDays = 1), permissions = own)))
        val inWindow = EntryDetailState(h.env, mine("2026-10-03"), SlipCompressor { it }) { }
        assertTrue(inWindow.ui.value.canEdit)
        assertFalse(inWindow.ui.value.canDelete, "no delete node")
        assertFalse(EntryDetailState(h.env, mine("2026-10-02"), SlipCompressor { it }) { }.ui.value.canEdit)
        assertFalse(EntryDetailState(h.env, theirs(), SlipCompressor { it }) { }.ui.value.canEdit)
    }

    // ---- the screens' state holders ----

    @Test fun todayWithoutTheDashboardNodeAsksForNoTotalsAndStillShowsTheDay() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(permissions = clerk.toList())))
        h.api.current = h.api.session("s1")
        val ui = TodayState(h.env).ui.value
        val data = ui.data!!
        assertTrue(h.api.dashboardCalls.isEmpty(), "no dashboard call")
        assertIs<DayStatus.Open>(data.status)
        assertFalse(data.showStats)
        assertNull(data.statsFailure)
        assertNull(ui.failure)
        assertNull(data.currencies)
    }

    @Test fun todayWithTheDashboardNodeAsksForTheTotals() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(permissions = clerk.toList() + PermissionNodes.DASHBOARD_VIEW)))
        val data = TodayState(h.env).ui.value.data!!
        assertEquals(1, h.api.dashboardCalls.size)
        assertTrue(data.showStats)
    }

    @Test fun reportsWithoutTheDashboardNodeAsksForNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(permissions = listOf(PermissionNodes.EXPORT))))
        val reports = ReportsState(h.env)
        assertTrue(h.api.dashboardCalls.isEmpty())
        assertNull(reports.ui.value.failure)
    }

    @Test fun closeDayWithoutItsNodeAsksForNoPreview() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness(backgroundScope, ConfigState.Ready(testConfig(permissions = clerk.toList())))
        h.api.current = h.api.session("s1")
        CloseDayState(h.env, TodayState(h.env))
        assertTrue(h.api.previewCalls.isEmpty())
    }

}
