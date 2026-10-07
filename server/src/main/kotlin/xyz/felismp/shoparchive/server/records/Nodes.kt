package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.server.auth.BRANCH_ALL_NODE
import xyz.felismp.shoparchive.shared.PermissionNodes

internal const val ENTRY_CREATE_NODE = PermissionNodes.ENTRY_CREATE
internal const val ENTRY_VIEW_OWN_NODE = PermissionNodes.ENTRY_VIEW_OWN
internal const val ENTRY_VIEW_ALL_NODE = PermissionNodes.ENTRY_VIEW_ALL
internal const val ENTRY_EDIT_OWN_NODE = PermissionNodes.ENTRY_EDIT_OWN
internal const val ENTRY_EDIT_ALL_NODE = PermissionNodes.ENTRY_EDIT_ALL
internal const val ENTRY_DELETE_OWN_NODE = PermissionNodes.ENTRY_DELETE_OWN
internal const val ENTRY_DELETE_ALL_NODE = PermissionNodes.ENTRY_DELETE_ALL
internal const val BRANCHES_MANAGE_NODE = "shoparchive.branches.manage"
internal const val CATEGORIES_MANAGE_NODE = "shoparchive.categories.manage"
internal const val DAY_OPEN_NODE = PermissionNodes.DAY_OPEN
internal const val DAY_CLOSE_NODE = PermissionNodes.DAY_CLOSE
internal const val EXPORT_NODE = PermissionNodes.EXPORT
internal const val DASHBOARD_VIEW_NODE = PermissionNodes.DASHBOARD_VIEW

/** The permission nodes of records, branches and day sessions. Defaults: a clerk records and sees their own work; everything wider is given on purpose. */
internal fun registerRecordNodes(nodes: PermissionNodeRegistry) {
    fun node(node: String, description: String, default: Boolean, th: String, lo: String) =
        nodes.register(PermissionNode(node, description, default, th = th, lo = lo))

    node(
        ENTRY_CREATE_NODE, "Record an income or expense entry", true,
        "บันทึกรายการรายรับหรือรายจ่าย",
        "ບັນທຶກລາຍການລາຍຮັບ ຫຼື ລາຍຈ່າຍ",
    )
    node(
        ENTRY_VIEW_OWN_NODE, "See the entries you recorded yourself", true,
        "ดูรายการที่ตนเองบันทึก",
        "ເບິ່ງລາຍການທີ່ຕົນເອງບັນທຶກ",
    )
    node(
        ENTRY_VIEW_ALL_NODE, "See the entries everyone recorded in your branches", false,
        "ดูรายการที่ทุกคนบันทึกในสาขาของตน",
        "ເບິ່ງລາຍການທີ່ທຸກຄົນບັນທຶກໃນສາຂາຂອງຕົນ",
    )
    node(
        ENTRY_EDIT_OWN_NODE, "Change your own entries while they are within the edit window (records.edit-window-days)", true,
        "แก้ไขรายการของตนเองภายในช่วงเวลาที่แก้ได้ (records.edit-window-days)",
        "ແກ້ໄຂລາຍການຂອງຕົນເອງພາຍໃນຊ່ວງເວລາທີ່ແກ້ໄດ້ (records.edit-window-days)",
    )
    node(
        ENTRY_EDIT_ALL_NODE, "Change anyone's entries, on any day", false,
        "แก้ไขรายการของทุกคนในทุกวัน",
        "ແກ້ໄຂລາຍການຂອງທຸກຄົນໃນທຸກວັນ",
    )
    node(
        ENTRY_DELETE_OWN_NODE, "Delete your own entries while they are within the edit window (records.edit-window-days)", true,
        "ลบรายการของตนเองภายในช่วงเวลาที่แก้ได้ (records.edit-window-days)",
        "ລຶບລາຍການຂອງຕົນເອງພາຍໃນຊ່ວງເວລາທີ່ແກ້ໄດ້ (records.edit-window-days)",
    )
    node(
        ENTRY_DELETE_ALL_NODE, "Delete anyone's entries, on any day", false,
        "ลบรายการของทุกคนในทุกวัน",
        "ລຶບລາຍການຂອງທຸກຄົນໃນທຸກວັນ",
    )
    node(
        BRANCH_ALL_NODE, "Work in every branch, not only the branches on the account", false,
        "ทำงานได้ทุกสาขา ไม่จำกัดเฉพาะสาขาที่อยู่ในบัญชี",
        "ເຮັດວຽກໄດ້ທຸກສາຂາ ບໍ່ຈຳກັດສະເພາະສາຂາທີ່ຢູ່ໃນບັນຊີ",
    )
    node(
        BRANCHES_MANAGE_NODE, "Add, rename and archive branches", false,
        "เพิ่ม เปลี่ยนชื่อ และเก็บถาวรสาขา",
        "ເພີ່ມ ປ່ຽນຊື່ ແລະ ເກັບຖາວອນສາຂາ",
    )
    node(
        CATEGORIES_MANAGE_NODE, "Add, rename and archive categories", false,
        "เพิ่ม เปลี่ยนชื่อ และเก็บถาวรหมวดหมู่",
        "ເພີ່ມ ປ່ຽນຊື່ ແລະ ເກັບຖາວອນໝວດໝູ່",
    )
    node(
        DAY_OPEN_NODE, "Open the day: count the change in the drawer and start a session", true,
        "เปิดวัน: นับเงินทอนในลิ้นชักแล้วเริ่มรอบ",
        "ເປີດວັນ: ນັບເງິນທອນໃນລິ້ນຊັກ ແລ້ວເລີ່ມຮອບ",
    )
    node(
        DAY_CLOSE_NODE, "Close the day: count the drawer and end the session", false,
        "ปิดยอด: นับเงินในลิ้นชักแล้วจบรอบ",
        "ປິດຍອດ: ນັບເງິນໃນລິ້ນຊັກ ແລ້ວຈົບຮອບ",
    )
    node(
        EXPORT_NODE, "Export the entries you may see as a CSV or Excel file", false,
        "ส่งออกรายการที่ตนดูได้เป็นไฟล์ CSV หรือ Excel (ต้องใส่ PIN หรือรหัสผ่านอีกครั้ง)",
        "ສົ່ງອອກລາຍການທີ່ຕົນເບິ່ງໄດ້ເປັນໄຟລ໌ CSV ຫຼື Excel (ຕ້ອງໃສ່ PIN ຫຼື ລະຫັດຜ່ານອີກຄັ້ງ)",
    )
    node(
        DASHBOARD_VIEW_NODE, "See the dashboard: the totals of the branches you work in, whoever recorded them", false,
        "ดูแดชบอร์ด: ยอดรวมของสาขาที่ตนทำงานอยู่ ไม่ว่าใครเป็นผู้บันทึก",
        "ເບິ່ງແດຊບອດ: ຍອດລວມຂອງສາຂາທີ່ຕົນເຮັດວຽກຢູ່ ບໍ່ວ່າໃຜເປັນຜູ້ບັນທຶກ",
    )
}
