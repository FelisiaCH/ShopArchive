package xyz.felismp.shoparchive.app.flow

// The server writes the tender, slip and transfer changes of the history as plain text (describe in EntryService). These read them back for the screen;
// a part that does not match exactly is kept as it came, so old history files always show.

private const val PARTS = "; "
private const val NUMBER = "-?\\d+(?:\\.\\d+)?"
private val TENDER = Regex("([A-Z]{3}) (cash|online) ($NUMBER)")
private val TRANSFER = Regex("(.+) ($NUMBER) ([A-Z]{3})")
private val SLIP = Regex("slip-([1-9][0-9]{0,3})\\.(?:jpg|png)")

/** "LAK cash 150000; LAK online 150000" as "150,000 LAK · Cash; 150,000 LAK · Online", with the screen's words for the two methods. */
fun historyTenders(text: String, cash: String, online: String): String = text.split(PARTS).joinToString(PARTS) { part ->
    val m = TENDER.matchEntire(part) ?: return@joinToString part
    "${groupThousands(m.groupValues[3])} ${m.groupValues[1]} · ${if (m.groupValues[2] == "cash") cash else online}"
}

/** "BCEL 850000 LAK" as "BCEL 850,000 LAK"; the amount is the only part written for the screen. */
fun historyTransfers(text: String): String = text.split(PARTS).joinToString(PARTS) { part ->
    val m = TRANSFER.matchEntire(part) ?: return@joinToString part
    "${m.groupValues[1]} ${groupThousands(m.groupValues[2])} ${m.groupValues[3]}"
}

/** The N of every file in "slip-1.jpg; slip-2.jpg", or null if the text holds anything else. */
fun historySlipNumbers(text: String): List<Int>? =
    text.split(PARTS).map { SLIP.matchEntire(it)?.groupValues?.get(1)?.toInt() ?: return null }

private val BANK_TIME = Regex("(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}):\\d{2}")

/** A bank's "2026-09-18 19:18:42" as "2026-09-18 19:18"; anything else as it came. */
fun bankTimeText(time: String): String = BANK_TIME.matchEntire(time)?.destructured?.let { (day, hm) -> "$day $hm" } ?: time
