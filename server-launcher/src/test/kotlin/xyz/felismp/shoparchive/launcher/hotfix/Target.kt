package hotfixfixtures

import xyz.felismp.shoparchive.api.hotfix.HotfixTarget

@JvmInline
value class Money(val amount: Int)

/** A stand-in for a core class: the planner patches its bytes. */
class Target {
    @HotfixTarget
    fun greet(name: String): String = "hello $name"

    @HotfixTarget
    fun sum(a: Int, b: Long): Long = a + b

    fun unmarked(): String = "unmarked"

    @HotfixTarget
    suspend fun waits(): String = "waits"

    @HotfixTarget
    fun mangled(m: Money): Int = m.amount

    @HotfixTarget
    fun other(): String = "other"
}
