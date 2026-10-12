package xyz.felismp.shoparchive.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UninstallCommandTest {
    @Test fun theShortcutsArgumentsBecomeMsiexecRemovingThatProduct() {
        assertEquals(
            listOf("msiexec", "/x", "{0F0214EA-689C-324B-81F1-C1B23DDD4C76}"),
            uninstallCommand(arrayOf("--uninstall", "{0F0214EA-689C-324B-81F1-C1B23DDD4C76}")),
        )
    }

    @Test fun anythingElseStartsTheApp() {
        assertNull(uninstallCommand(emptyArray()))
        assertNull(uninstallCommand(arrayOf("--uninstall")))
        assertNull(uninstallCommand(arrayOf("--uninstall", "C:\\Windows\\evil.msi")))
        assertNull(uninstallCommand(arrayOf("--uninstall", "{0F0214EA-689C-324B-81F1-C1B23DDD4C76} /qn")))
        assertNull(uninstallCommand(arrayOf("--uninstall", "{0F0214EA-689C-324B-81F1-C1B23DDD4C76}", "/qn")))
    }
}
