package xyz.felismp.shoparchive.api

/** Two registrations that must not coexist (same service priority, same command name, same permission node). */
class RegistryConflictException(message: String) : IllegalStateException(message)

/** Lets the core ship a default implementation of a service type and a plugin replace it with a higher priority. */
interface ServiceRegistry {
    /**
     * @throws RegistryConflictException if [type] already has an implementation with the same [priority];
     * the message names both owners, so the admin knows which two plugins to sort out.
     */
    fun <T : Any> register(type: Class<T>, implementation: T, priority: Int, owner: String)

    /** The implementation of [type] with the highest priority, or null if none is registered. */
    fun <T : Any> get(type: Class<T>): T?
}
