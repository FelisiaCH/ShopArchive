package xyz.felismp.shoparchive.app.client

/**
 * How many addresses a device keeps for one server. The one it was added by and the two the server announces fit, and four still
 * lets an old address that stopped working fall off instead of being tried first on every connect.
 */
internal const val MAX_STORED_ENDPOINTS = 4

/** What a user added on a device that already holds the server keeps: the addresses [fresh] that just worked first, then the [old] ones, no repeats, at most [MAX_STORED_ENDPOINTS] (the oldest drop), LAN first. */
internal fun mergeEndpoints(fresh: List<String>, old: List<String>): List<String> =
    orderEndpoints((fresh + old).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_STORED_ENDPOINTS))

/** What a device saves after the server told it where it is: the address in use first (it works), then those the server [announced], then the [old] ones, capped like [mergeEndpoints]. */
internal fun rememberEndpoints(inUse: String?, announced: List<String>, old: List<String>): List<String> =
    (listOfNotNull(inUse) + announced + old).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_STORED_ENDPOINTS)

/** [endpoints] (`host:port`) with LAN/private addresses first; the given order is kept inside each group. */
internal fun orderEndpoints(endpoints: List<String>): List<String> {
    val (lan, other) = endpoints.map { it.trim() }.filter { it.isNotEmpty() }.distinct().partition { isPrivateHost(hostOf(it)) }
    return lan + other
}

private fun hostOf(endpoint: String): String =
    if (endpoint.startsWith("[")) endpoint.substringAfter('[').substringBefore(']') else endpoint.substringBeforeLast(':')

private fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase()
    if (h.contains(':')) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
    val parts = h.split('.')
    val n = parts.map { it.toIntOrNull() }
    if (parts.size == 4 && n.all { it != null && it in 0..255 }) {
        val (a, b) = n[0]!! to n[1]!!
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
    }
    return h == "localhost" || h.endsWith(".local") || !h.contains('.')
}
