package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.shared.EntryType
import xyz.felismp.shoparchive.shared.TenderMethod

/** The words of one export language; the app's own wording (strings.xml) where the app has the concept. */
private class Table(
    val header: List<String>,
    val deleted: String,
    val entries: String,
    val daily: String,
    val dailyHeader: List<String>,
    val type: Map<EntryType, String>,
    val method: Map<TenderMethod, String>,
    val yes: String,
    val no: String,
)

private val ENGLISH = Table(
    listOf("date", "time", "entry id", "type", "branch", "category", "item", "note", "currency", "method", "amount", "created-by", "slip count"),
    "deleted", "Entries", "Daily", listOf("date", "branch", "currency", "cash in", "cash out", "online in", "online out", "net"),
    mapOf(EntryType.INCOME to "income", EntryType.EXPENSE to "expense"), mapOf(TenderMethod.CASH to "cash", TenderMethod.ONLINE to "online"), "true", "false",
)

private val THAI = Table(
    listOf("วันที่", "เวลา", "รหัสรายการ", "ประเภท", "สาขา", "หมวดหมู่", "รายการ", "หมายเหตุ", "สกุลเงิน", "วิธีชำระเงิน", "จำนวนเงิน", "ผู้บันทึก", "จำนวนสลิป"),
    "ลบแล้ว", "รายการ", "ทีละวัน", listOf("วันที่", "สาขา", "สกุลเงิน", "เงินสดเข้า", "เงินสดออก", "ออนไลน์เข้า", "ออนไลน์ออก", "สุทธิ"),
    mapOf(EntryType.INCOME to "รายรับ", EntryType.EXPENSE to "รายจ่าย"), mapOf(TenderMethod.CASH to "เงินสด", TenderMethod.ONLINE to "ออนไลน์"), "ใช่", "ไม่",
)

private val LAO = Table(
    listOf("ວັນທີ", "ເວລາ", "ລະຫັດລາຍການ", "ປະເພດ", "ສາຂາ", "ໝວດໝູ່", "ລາຍການ", "ໝາຍເຫດ", "ສະກຸນເງິນ", "ວິທີຈ່າຍ", "ຈຳນວນເງິນ", "ຜູ້ບັນທຶກ", "ຈຳນວນສະລິບ"),
    "ລຶບແລ້ວ", "ລາຍການ", "ແຕ່ລະວັນ", listOf("ວັນທີ", "ສາຂາ", "ສະກຸນເງິນ", "ເງິນສົດເຂົ້າ", "ເງິນສົດອອກ", "ອອນລາຍເຂົ້າ", "ອອນລາຍອອກ", "ສຸດທິ"),
    mapOf(EntryType.INCOME to "ລາຍຮັບ", EntryType.EXPENSE to "ລາຍຈ່າຍ"), mapOf(TenderMethod.CASH to "ເງິນສົດ", TenderMethod.ONLINE to "ອອນລາຍ"), "ແມ່ນ", "ບໍ່",
)

/**
 * What an export says: its headers, sheet names and enum words in one language (`lo`, `th`; anything else is English), and the display
 * names of categories and branches. [DEFAULT] is the English of the first versions: wire words and the keys themselves.
 */
internal class ExportWords(
    language: String?,
    private val categoryName: (String) -> String = { it },
    private val branchName: (String) -> String = { it },
) {
    private val table = when (language) { "lo" -> LAO; "th" -> THAI; else -> ENGLISH }

    fun header(deleted: Boolean) = table.header + if (deleted) listOf(table.deleted) else emptyList()
    val entriesSheet: String get() = table.entries
    val dailySheet: String get() = table.daily
    val dailyHeader: List<String> get() = table.dailyHeader
    fun type(type: EntryType) = table.type.getValue(type)
    fun method(method: TenderMethod) = table.method.getValue(method)
    fun flag(deleted: Boolean) = if (deleted) table.yes else table.no
    fun category(key: String?) = if (key == null) "" else categoryName(key)
    fun branch(key: String) = branchName(key)

    companion object {
        val DEFAULT = ExportWords(null)
    }
}
