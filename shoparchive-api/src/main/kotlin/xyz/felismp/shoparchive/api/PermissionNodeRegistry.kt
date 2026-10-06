package xyz.felismp.shoparchive.api

/** A permission that exists; [description] is English and required, [th] and [lo] are optional translations. */
data class PermissionNode(
    val node: String,
    val description: String,
    val default: Boolean,
    val th: String? = null,
    val lo: String? = null,
) {
    init {
        require(node.isNotBlank()) { "permission node must not be blank" }
        require(description.isNotBlank()) { "permission node '$node' needs an English description" }
    }
}

interface PermissionNodeRegistry {
    /** @throws RegistryConflictException if the node is already registered. */
    fun register(node: PermissionNode)

    /** Every registered node, sorted by node string. */
    fun all(): List<PermissionNode>
}
