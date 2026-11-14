package xyz.felismp.shoparchive.web

import xyz.felismp.shoparchive.app.flow.NotesUi
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.NoteRead
import xyz.felismp.shoparchive.shared.UserRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotesViewsTest {
    private fun ui(canRead: Boolean = true, connected: Boolean = true, marking: Set<String> = emptySet(), userId: String = "u1") =
        NotesUi("main", "2026-11-14", true, false, emptyList(), null, false, canRead, "", false, null, null, marking, userId, connected)

    private fun note(by: String = "u2", reads: List<NoteRead> = emptyList()) =
        NoteDto("n1", "main", "2026-11-14", "Hello", UserRef(by, if (by == "u1") "Noy" else "Dao"), "2026-11-14T08:12:30+07:00", reads)

    @Test
    fun theReaderMarksANoteReadOrSeesWhenTheyDid() {
        val unread = noteFoot(ui(), note()) as NoteFoot.MarkRead
        assertEquals("n1", unread.id)
        assertTrue(unread.enabled)
        assertFalse((noteFoot(ui(marking = setOf("n1")), note()) as NoteFoot.MarkRead).enabled)
        assertFalse((noteFoot(ui(connected = false), note()) as NoteFoot.MarkRead).enabled)

        val read = noteFoot(ui(), note(reads = listOf(NoteRead(UserRef("u1", "Noy"), "2026-11-14T09:05:00+07:00"))))
        assertEquals(NoteFoot.ReadAt("09:05"), read)
    }

    @Test
    fun theWriterSeesWhetherTheOwnerHasReadIt() {
        val seen = noteFoot(ui(canRead = false, userId = "u2"), note(by = "u2", reads = listOf(NoteRead(UserRef("u1", "Noy"), "2026-11-14T09:05:00+07:00"))))
        assertEquals(NoteFoot.Seen("09:05"), seen)
        assertEquals(NoteFoot.NotSeen, noteFoot(ui(canRead = false, userId = "u2"), note(by = "u2")))
    }

    @Test
    fun nothingIsShownUnderAnOwnNoteOrOneTheReaderMayNotMark() {
        assertEquals(NoteFoot.None, noteFoot(ui(canRead = true, userId = "u1"), note(by = "u1")))
        assertEquals(NoteFoot.None, noteFoot(ui(canRead = false, userId = "u2"), note(by = "u3")))
    }
}
