package xyz.felismp.shoparchive.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaVersionTest {
    @Test
    fun parsesLegacyAndModernVersionStrings() {
        assertEquals(8, parseJavaMajorVersion("1.8"))
        assertEquals(17, parseJavaMajorVersion("17"))
        assertEquals(21, parseJavaMajorVersion("21"))
        assertEquals(25, parseJavaMajorVersion("25"))
    }

    @Test
    fun refusesBelow21() {
        assertFalse(isJavaVersionSupported(8))
        assertFalse(isJavaVersionSupported(17))
        assertTrue(isJavaVersionSupported(21))
        assertTrue(isJavaVersionSupported(25))
    }
}
