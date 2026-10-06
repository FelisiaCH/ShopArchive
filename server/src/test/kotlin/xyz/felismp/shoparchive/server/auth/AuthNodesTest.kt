package xyz.felismp.shoparchive.server.auth

import xyz.felismp.shoparchive.server.Permissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AuthNodesTest {
    @Test
    fun theNodesOfLoginAreRegisteredOffByDefaultWithThaiAndLaoText() {
        val nodes = Permissions().also(::registerAuthNodes).all()

        assertEquals(
            listOf("shoparchive.devices.pair", "shoparchive.devices.revoke", "shoparchive.server.status", "shoparchive.users.manage"),
            nodes.map { it.node },
        )
        for (node in nodes) {
            assertFalse(node.default, node.node)
            assertNotNull(node.th, node.node)
            assertNotNull(node.lo, node.node)
        }
    }
}
