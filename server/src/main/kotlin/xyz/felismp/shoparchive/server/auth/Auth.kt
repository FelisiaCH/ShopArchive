package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.api.AuthService
import xyz.felismp.shoparchive.api.ClientConfigService
import xyz.felismp.shoparchive.api.DeviceService
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.PairingService
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.server.net.CORE_SERVICE_PRIORITY
import xyz.felismp.shoparchive.server.net.DefaultEventService
import xyz.felismp.shoparchive.server.DataBarrier
import xyz.felismp.shoparchive.server.users.UserStore
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

/** The permission nodes the pairing code needs; plugins register theirs the same way. */
internal fun registerAuthNodes(nodes: PermissionNodeRegistry) {
    nodes.register(
        PermissionNode(
            PAIR_NODE, "Create a pairing for another user, so a new device can be set up for them", default = false,
            th = "สร้างการจับคู่ให้ผู้ใช้คนอื่น เพื่อตั้งค่าอุปกรณ์เครื่องใหม่ให้เขา",
            lo = "ສ້າງການຈັບຄູ່ໃຫ້ຜູ້ໃຊ້ຄົນອື່ນ ເພື່ອຕັ້ງຄ່າອຸປະກອນເຄື່ອງໃໝ່ໃຫ້ລາວ",
        ),
    )
    nodes.register(
        PermissionNode(
            USERS_MANAGE_NODE, "Manage users: create, enable, disable, change roles, branches and permissions", default = false,
            th = "จัดการผู้ใช้: สร้าง เปิด/ปิดการใช้งาน เปลี่ยนบทบาท สาขา และสิทธิ์",
            lo = "ຈັດການຜູ້ໃຊ້: ສ້າງ ເປີດ/ປິດການໃຊ້ງານ ປ່ຽນບົດບາດ ສາຂາ ແລະ ສິດ",
        ),
    )
    nodes.register(
        PermissionNode(
            DEVICES_REVOKE_NODE, "Take another user off a device", default = false,
            th = "ถอดผู้ใช้คนอื่นออกจากอุปกรณ์",
            lo = "ຖອດຜູ້ໃຊ້ຄົນອື່ນອອກຈາກອຸປະກອນ",
        ),
    )
    nodes.register(
        PermissionNode(
            SERVER_STATUS_NODE, "See the status of the server", default = false,
            th = "ดูสถานะของเซิร์ฟเวอร์",
            lo = "ເບິ່ງສະຖານະຂອງເຊີບເວີ",
        ),
    )
}

internal const val USERS_MANAGE_NODE = "shoparchive.users.manage"
internal const val DEVICES_REVOKE_NODE = "shoparchive.devices.revoke"
internal const val SERVER_STATUS_NODE = "shoparchive.server.status"

/**
 * Everything behind login: pairings, devices, access tokens, the audit log and the pushes to open WebSockets.
 * Registers its services with the core priority, so a plugin can still replace any of them.
 */
internal class Auth(
    root: Path,
    config: ConfigService,
    users: UserStore,
    services: ServiceRegistry,
    serverId: UUID,
    endpoints: () -> List<String>,
    clock: Clock = Clock.systemUTC(),
    cost: Argon2Cost = Argon2Cost.DEFAULT,
    val hasher: Hasher = Hasher(config.auth.hashConcurrency, cost),
    records: ClientRecordsConfig = NoRecords,
    barrier: DataBarrier = DataBarrier(),
) {
    val sessions = Sessions(clock)

    // A block the admin took out of a device file ends that account's tokens on the device; the open socket follows within a second.
    val devices = DeviceStore(root, clock = clock, onRevoked = { deviceId, userId -> sessions.revokeAccess(userId, deviceId) }, barrier = barrier).also { it.load() }
    val events = DefaultEventService()
    val audit = AuditLog(root, { config.timezone }, clock, barrier)
    private val policy = Policy(config, users)
    private val backoff = Backoff(config, users, sessions, audit, clock)

    val pairing = DefaultPairingService(root, config, users, policy, sessions, audit, services, serverId, endpoints, clock)
    val console = PairingConsole(root, config, pairing)
    val accounts = AccountConsole(users, devices, sessions, audit, pairing, console, barrier)

    init {
        // The device files are keyed by name for the admin to read; the account behind a block is its user-id, and the name follows a rename.
        users.onRename { id, old, new -> devices.renameUser(id, old, new) }
        // Disabled, role changed, credentials cleared: the tokens end now, and the open sockets follow within a second.
        users.onAccessChanged { id -> sessions.revokeAccess(id, null) }
        services.register(EventService::class.java, events, CORE_SERVICE_PRIORITY, "core")
        services.register(PairingService::class.java, pairing, CORE_SERVICE_PRIORITY, "core")
        services.register(DeviceService::class.java, DefaultDeviceService(config, users, devices, sessions, audit), CORE_SERVICE_PRIORITY, "core")
        services.register(ClientConfigService::class.java, DefaultClientConfigService(config, users, policy, endpoints, records), CORE_SERVICE_PRIORITY, "core")
        services.register(AuthService::class.java, DefaultAuthService(config, users, policy, hasher, sessions, devices, audit, services, backoff, clock, barrier), CORE_SERVICE_PRIORITY, "core")
    }

    /** The lines `status` adds. */
    fun statusLines(): List<String> = listOf(
        "Pending pairings: ${pairing.pendingCount()}",
        "WebSocket connections: ${events.openConnections()}",
    )
}
