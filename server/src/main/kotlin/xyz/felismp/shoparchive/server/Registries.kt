package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.PermissionNodeRegistry
import xyz.felismp.shoparchive.api.RegistryConflictException
import xyz.felismp.shoparchive.api.ServiceRegistry

internal class Services : ServiceRegistry {
    private class Entry(val implementation: Any, val priority: Int, val owner: String)

    private val byType = HashMap<Class<*>, MutableList<Entry>>()

    @Synchronized
    override fun <T : Any> register(type: Class<T>, implementation: T, priority: Int, owner: String) {
        val entries = byType.getOrPut(type) { mutableListOf() }
        entries.firstOrNull { it.priority == priority }?.let {
            throw RegistryConflictException(
                "service ${type.name}: '${it.owner}' and '$owner' are both registered with priority $priority"
            )
        }
        entries += Entry(implementation, priority, owner)
    }

    @Synchronized
    override fun <T : Any> get(type: Class<T>): T? =
        byType[type]?.maxByOrNull { it.priority }?.let { type.cast(it.implementation) }

    /** One registration as `plugins` shows it: the service [type], its [priority], and whether the core also registered one it is above. */
    class Owned(val type: Class<*>, val priority: Int, val overridesCore: Boolean)

    /** What [owner] registered, sorted by type name. */
    @Synchronized
    fun ownedBy(owner: String): List<Owned> = byType.entries.mapNotNull { (type, entries) ->
        val mine = entries.firstOrNull { it.owner == owner } ?: return@mapNotNull null
        val core = entries.firstOrNull { it.owner == "core" }
        Owned(type, mine.priority, core != null && mine.priority > core.priority)
    }.sortedBy { it.type.name }

    /** Removes everything [owner] registered (a plugin that failed or was disabled); returns how many registrations went. */
    @Synchronized
    fun unregisterOwner(owner: String): Int {
        var removed = 0
        byType.values.forEach { entries -> removed += entries.count { it.owner == owner }; entries.removeAll { it.owner == owner } }
        byType.values.removeAll { it.isEmpty() }
        return removed
    }
}

internal class Permissions : PermissionNodeRegistry {
    private val byNode = sortedMapOf<String, PermissionNode>()

    @Synchronized
    override fun register(node: PermissionNode) {
        if (node.node in byNode) throw RegistryConflictException("permission node '${node.node}' is registered twice")
        byNode[node.node] = node
    }

    @Synchronized
    override fun all(): List<PermissionNode> = byNode.values.toList()

    /** Takes [nodes] back (a plugin that failed to load); a node nobody registered is ignored. */
    @Synchronized
    fun unregister(nodes: Collection<String>) {
        nodes.forEach { byNode.remove(it) }
    }
}
