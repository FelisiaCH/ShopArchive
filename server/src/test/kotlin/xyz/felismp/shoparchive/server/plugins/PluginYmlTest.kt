package xyz.felismp.shoparchive.server.plugins

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginYmlTest {
    @Test
    fun aCompleteFileIsRead() {
        val yml = parsePluginYml(
            "name: My_Plugin-1\nversion: 1.2.3\nmain: com.example.Main\napi-version: 1\ndepend: [A, B]\nsoftdepend: [B, C]\ndescription: hi\nextra: x\n",
        )
        assertEquals("My_Plugin-1", yml.name)
        assertEquals("1.2.3", yml.version)
        assertEquals(1, yml.apiVersion)
        assertEquals(listOf("A", "B"), yml.depend)
        assertEquals(listOf("C"), yml.softdepend, "a plugin in both lists is a hard dependency")
        assertEquals(listOf("extra"), yml.unknownKeys)
    }

    @Test
    fun aPluginCannotDependOnItself() {
        val e = assertFailsWith<PluginYmlException> { parsePluginYml("name: A\nversion: 1\nmain: a.B\napi-version: 1\ndepend: [A]\n") }
        assertContains(e.message!!, "cannot depend on itself")
    }

    @Test
    fun aNameOfThirtyThreeCharactersIsRefused() {
        val e = assertFailsWith<PluginYmlException> { parsePluginYml("name: ${"a".repeat(33)}\nversion: 1\nmain: a.B\napi-version: 1\n") }
        assertContains(e.message!!, "1 to 32 characters")
    }

    @Test
    fun yamlThatIsNotAMappingIsRefused() {
        assertFailsWith<PluginYmlException> { parsePluginYml("- a\n- b\n") }
        assertFailsWith<PluginYmlException> { parsePluginYml("") }
    }
}
