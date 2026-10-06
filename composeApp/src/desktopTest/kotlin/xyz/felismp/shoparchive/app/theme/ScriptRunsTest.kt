package xyz.felismp.shoparchive.app.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptRunsTest {
    private fun runs(text: String) = scriptRuns(text).map { text.substring(it.start, it.end) to it.script }

    @Test
    fun emptyTextHasNoRuns() {
        assertEquals(emptyList(), runs(""))
    }

    @Test
    fun mixedLineSplitsByScript() {
        assertEquals(
            listOf(
                "ລາຍງານ" to Script.Lao,
                " · รายงาน · Oppo a31 · 6,715,000 " to Script.Other,
                "₭" to Script.Lao,
                " · ฿2,000" to Script.Other,
            ),
            runs("ລາຍງານ · รายงาน · Oppo a31 · 6,715,000 ₭ · ฿2,000"),
        )
    }

    @Test
    fun thaiAndBahtStayInOtherRun() {
        assertEquals(listOf("รายงาน ฿" to Script.Other), runs("รายงาน ฿"))
    }
}
