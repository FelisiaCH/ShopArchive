package xyz.felismp.shoparchive.server.notify

import xyz.felismp.shoparchive.api.ApiError
import xyz.felismp.shoparchive.api.DayClosedEvent
import xyz.felismp.shoparchive.api.NotificationService
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.records.Access
import xyz.felismp.shoparchive.server.records.notFound
import xyz.felismp.shoparchive.server.records.stamp
import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.NotificationDto
import xyz.felismp.shoparchive.shared.NotificationState
import xyz.felismp.shoparchive.shared.PermissionNodes
import java.time.Clock

internal const val NOTIFICATIONS_VIEW_NODE = PermissionNodes.NOTIFICATIONS_VIEW
internal const val NOTIFICATIONS_SEND_NODE = PermissionNodes.NOTIFICATIONS_SEND

/** The most messages one list answers with. */
internal const val MAX_NOTIFICATION_LIST = 500

/** The permission nodes of the outbox. Neither is a default: a message holds the amounts of a branch, and sending again can put a second message in a chat. */
internal fun registerNotifyNodes(nodes: PermissionNodeRegistry) {
    nodes.register(
        PermissionNode(
            NOTIFICATIONS_VIEW_NODE, "See the messages sent to the shop's channels (Telegram...) and whether they arrived", false,
            th = "ดูข้อความที่ส่งไปยังช่องทางของร้าน (Telegram ฯลฯ) และดูว่าส่งถึงหรือไม่",
            lo = "ເບິ່ງຂໍ້ຄວາມທີ່ສົ່ງໄປຊ່ອງທາງຂອງຮ້ານ (Telegram ແລະອື່ນໆ) ແລະ ເບິ່ງວ່າສົ່ງເຖິງຫຼືບໍ່",
        ),
    )
    nodes.register(
        PermissionNode(
            NOTIFICATIONS_SEND_NODE, "Send a message of the outbox again, or the summary of a closed day", false,
            th = "ส่งข้อความในคิวซ้ำ หรือส่งสรุปปิดยอดของวันที่ปิดแล้วอีกครั้ง",
            lo = "ສົ່ງຂໍ້ຄວາມໃນຄິວຊ້ຳ ຫຼື ສົ່ງສະຫຼຸບປິດຍອດຂອງວັນທີ່ປິດແລ້ວອີກຄັ້ງ",
        ),
    )
}

internal class DefaultNotificationService(
    private val outbox: Outbox,
    private val access: Access,
    private val closedDay: (branch: String, id: String) -> DayClosedEvent?,
    private val config: ConfigService,
    private val clock: Clock,
) : NotificationService {
    override fun list(principal: Principal, state: NotificationState?, limit: Int): List<NotificationDto> {
        access.require(principal, NOTIFICATIONS_VIEW_NODE)
        return outbox.items().asReversed()
            .filter { (state == null || it.state == state) && access.canBranch(principal, it.notification.branch) }
            .take(limit.coerceIn(1, MAX_NOTIFICATION_LIST)).map { it.toDto() }
    }

    override fun resend(principal: Principal, id: String): NotificationDto {
        access.require(principal, NOTIFICATIONS_SEND_NODE)
        val item = outbox.items().firstOrNull { it.id == id } ?: throw notFound("No such message.")
        access.requireBranch(principal, item.notification.branch)
        if (item.state == NotificationState.QUEUED || item.state == NotificationState.UNKNOWN) throw pending()
        // Also refused while a copy sent earlier from this message (or from another one about the same entry or day) is still waiting.
        return outbox.unlessWaiting(item.notification.event, item.subject, item.notification.code) { outbox.resend(item) }?.toDto() ?: throw pending()
    }

    override fun notifyDay(principal: Principal, branch: String, sessionId: String): NotificationDto {
        access.require(principal, NOTIFICATIONS_SEND_NODE)
        access.requireBranch(principal, branch)
        val event = closedDay(branch, sessionId) ?: throw notFound("There is no closed day with that id in that branch.")
        val before = outbox.items().filter { it.notification.event == event.type && it.subject == sessionId }
        val first = before.lastOrNull()
        // A day that has a message is sent again as it was sent the first time (same words, same figures), never rebuilt from entries that may have been edited since.
        val draft = if (first == null) draftFor(event, config.locale, stamp(clock, config)) ?: throw notFound("There is no closed day with that id in that branch.") else null
        val sent = outbox.unlessWaiting(event.type, sessionId, first?.notification?.code ?: draft!!.code) {
            if (first != null) outbox.resend(first) else outbox.enqueue(draft!!)
        }
        return (sent ?: throw pending()).toDto()
    }

    private fun pending() = ApiError(409, ErrorCode.CONFLICT, "This message is still waiting to be sent.", reason = ErrorReasons.NOTIFICATION_PENDING)
}
