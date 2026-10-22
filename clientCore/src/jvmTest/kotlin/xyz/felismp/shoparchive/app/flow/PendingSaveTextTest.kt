package xyz.felismp.shoparchive.app.flow

import xyz.felismp.shoparchive.shared.CreateEntryRequest
import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.TenderDto
import xyz.felismp.shoparchive.shared.TenderMethod
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingSaveTextTest {
    @Test fun aPendingSaveWithSlipsRoundTripsAndKeepsTheBytesAsBase64() {
        val photo = ByteArray(300_000) { (it * 7).toByte() }
        val draft = Draft(
            "id-1", EntryType.EXPENSE, "rent", "table", "note",
            listOf(TenderRow(1, "LAK", TenderMethod.ONLINE, "1500")),
            listOf(SlipItem(2, "a.jpg", photo, code = "QR1")),
            listOf(ScannedTransfer(3, "QR2", xyz.felismp.shoparchive.shared.SlipCheckDto("bank", true))),
        )
        val request = CreateEntryRequest("id-1", EntryType.EXPENSE, "main", "rent", "table", "note", listOf(TenderDto("LAK", TenderMethod.ONLINE, "1500")), listOf("QR1"), listOf("QR2"))
        val text = PendingSave(draft, request, listOf(photo)).toText()
        assertTrue(text.length < photo.size * 3, "two Base64 copies, not number arrays")
        val back = assertNotNull(pendingSaveFrom(text))
        assertEquals(request, back.request)
        assertEquals("id-1", back.draft.id)
        assertEquals("1500", back.draft.tenders.single().amount)
        assertEquals("QR1", back.draft.slips.single().code)
        assertEquals("QR2", back.draft.transfers.single().code)
        assertContentEquals(photo, back.draft.slips.single().bytes)
        assertContentEquals(photo, back.slips.single())
    }

    @Test fun damagedOrOldTextReadsAsNothingPending() {
        assertNull(pendingSaveFrom(null))
        assertNull(pendingSaveFrom(""))
        assertNull(pendingSaveFrom("{not json"))
        assertNull(pendingSaveFrom("""{"draft":{"id":"x"}}"""))
    }
}
