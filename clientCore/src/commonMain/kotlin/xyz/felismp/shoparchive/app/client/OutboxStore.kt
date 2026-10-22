package xyz.felismp.shoparchive.app.client

/**
 * Where an entry save with no answer yet is kept on this device, one per server and user, so a crash or restart does not lose it.
 * The text is opaque to the store; writing null removes it.
 */
interface OutboxStore {
    fun read(serverId: String, user: String): String?
    fun write(serverId: String, user: String, text: String?)
    fun removeServer(serverId: String)
}

/** Keeps nothing past the process: the default where a platform passes no store. */
class MemoryOutboxStore : OutboxStore {
    private val texts = mutableMapOf<Pair<String, String>, String>()
    override fun read(serverId: String, user: String) = texts[serverId to user]
    override fun write(serverId: String, user: String, text: String?) {
        if (text == null) texts.remove(serverId to user) else texts[serverId to user] = text
    }
    override fun removeServer(serverId: String) {
        texts.keys.removeAll { it.first == serverId }
    }
}
