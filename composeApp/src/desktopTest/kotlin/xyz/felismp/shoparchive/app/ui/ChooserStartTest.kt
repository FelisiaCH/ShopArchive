package xyz.felismp.shoparchive.app.ui

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ChooserStartTest {
    private fun withHome(block: (File) -> Unit) {
        val home = createTempDirectory("chooser-start-").toFile()
        try {
            block(home)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun usesPreferredWhenPresent() = withHome { home ->
        File(home, "Pictures").mkdir()
        assertEquals(File(home, "Pictures").path, chooserStart(home, "Pictures"))
    }

    @Test
    fun fallsBackToHomeWhenMissing() = withHome { home ->
        assertEquals(home.path, chooserStart(home, "Pictures"))
    }

    @Test
    fun usesFirstExistingPreferred() = withHome { home ->
        File(home, "Images").mkdir()
        File(home, "Pictures").mkdir()
        assertEquals(File(home, "Images").path, chooserStart(home, "Missing", "Images", "Pictures"))
    }

    @Test
    fun ignoresPreferredThatIsAFile() = withHome { home ->
        File(home, "Pictures").writeText("x")
        assertEquals(home.path, chooserStart(home, "Pictures"))
    }
}
