package xyz.felismp.shoparchive.server

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandSender
import xyz.felismp.shoparchive.api.PermissionNode
import xyz.felismp.shoparchive.api.RegistryConflictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistriesTest {
    private interface Greeter

    private class Named(val label: String) : Greeter

    @Test
    fun theHighestPriorityImplementationWins() {
        val services = Services()
        services.register(Greeter::class.java, Named("plugin"), priority = 10, owner = "plugin-a")
        services.register(Greeter::class.java, Named("core"), priority = 0, owner = "core")
        services.register(Greeter::class.java, Named("mid"), priority = 5, owner = "plugin-b")

        assertEquals("plugin", (services.get(Greeter::class.java) as Named).label)
    }

    @Test
    fun anUnregisteredTypeHasNoImplementation() {
        assertNull(Services().get(Greeter::class.java))
    }

    @Test
    fun equalPriorityFailsAndNamesBothOwners() {
        val services = Services()
        services.register(Greeter::class.java, Named("one"), priority = 5, owner = "plugin-a")

        val e = assertFailsWith<RegistryConflictException> {
            services.register(Greeter::class.java, Named("two"), priority = 5, owner = "plugin-b")
        }

        assertTrue("plugin-a" in e.message!! && "plugin-b" in e.message!!, e.message)
        assertEquals("one", (services.get(Greeter::class.java) as Named).label) // the loser was not added
    }

    @Test
    fun theSamePriorityOnDifferentTypesIsFine() {
        val services = Services()
        services.register(Greeter::class.java, Named("a"), priority = 0, owner = "core")
        services.register(CharSequence::class.java, "b", priority = 0, owner = "core")

        assertEquals("b", services.get(CharSequence::class.java))
    }

    @Test
    fun aDuplicatePermissionNodeFails() {
        val permissions = Permissions()
        permissions.register(PermissionNode("record.create", "Create records", default = true))

        val e = assertFailsWith<RegistryConflictException> {
            permissions.register(PermissionNode("record.create", "Create records again", default = false))
        }

        assertTrue("record.create" in e.message!!, e.message)
        assertEquals(1, permissions.all().size)
    }

    @Test
    fun permissionNodesAreListedBySortedNodeAndKeepTheirTranslations() {
        val permissions = Permissions()
        permissions.register(PermissionNode("record.view", "View records", default = true, th = "ดูรายการ", lo = "ເບິ່ງລາຍການ"))
        permissions.register(PermissionNode("export.run", "Export data", default = false))

        assertEquals(listOf("export.run", "record.view"), permissions.all().map { it.node })
        assertEquals("ดูรายการ", permissions.all()[1].th)
        assertNull(permissions.all()[0].lo)
    }

    @Test
    fun aPermissionNodeNeedsAnEnglishDescription() {
        assertFailsWith<IllegalArgumentException> { PermissionNode("record.view", " ", default = true) }
    }

    @Test
    fun aDuplicateCommandNameFailsIgnoringCaseAndNamesBothOwners() {
        val commands = Commands()
        commands.register(command("ping"), "core")

        val e = assertFailsWith<RegistryConflictException> { commands.register(command("PING"), "plugin-a") }

        assertTrue("core" in e.message!! && "plugin-a" in e.message!!, e.message)
    }

    @Test
    fun commandsAreListedByNameAndFoundIgnoringCase() {
        val commands = Commands()
        commands.register(command("zeta"), "core")
        commands.register(command("alpha"), "core")

        assertEquals(listOf("alpha", "zeta"), commands.all().map { it.name })
        assertEquals("zeta", commands.find("ZETA")?.name)
        assertNull(commands.find("missing"))
    }

    private fun command(name: String) = object : Command {
        override val name = name
        override val description = "test command"
        override fun execute(sender: CommandSender, args: List<String>) = Unit
    }

    @Test
    fun unregisteringAnOwnerRemovesOnlyItsServicesCommandsAndNodes() {
        val services = Services()
        services.register(Greeter::class.java, Named("core"), priority = 0, owner = "core")
        services.register(Greeter::class.java, Named("plugin"), priority = 10, owner = "Hello")
        val owned = services.ownedBy("Hello").single()
        assertTrue(owned.overridesCore && owned.priority == 10)

        assertEquals(1, services.unregisterOwner("Hello"))
        assertEquals("core", (services.get(Greeter::class.java) as Named).label)
        assertEquals(emptyList(), services.ownedBy("Hello").map { it.priority })

        val commands = Commands()
        commands.register(command("keep"), "core")
        commands.register(command("drop"), "Hello")
        assertEquals(1, commands.unregisterOwner("Hello"))
        assertEquals(listOf("keep"), commands.all().map { it.name })

        val nodes = Permissions()
        nodes.register(PermissionNode("a.b", "d", default = false))
        nodes.unregister(listOf("a.b", "never.registered"))
        assertEquals(emptyList(), nodes.all())
    }
}
