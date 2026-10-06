package xyz.felismp.shoparchive.launcher.hotfix

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class MiniYamlTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun readsTheFlatKeysOfAPluginYml() {
        val map = parseTopLevel(
            """
            # comment
            name: Fix1   # trailing
            version: "1.2"
            hotfix: true
            fixes: [SA-1, 'SA-2']
            severity: security
            target-build: 5
            description: "has # inside"
            depend:
              - A
              - "B"
            nested:
              key: value
            """.trimIndent()
        )
        assertEquals("Fix1", map["name"])
        assertEquals("1.2", map["version"])
        assertEquals("true", map["hotfix"])
        assertEquals(listOf("SA-1", "SA-2"), map["fixes"])
        assertEquals("5", map["target-build"])
        assertEquals("has # inside", map["description"])
        assertEquals(listOf("A", "B"), map["depend"])
    }

    @Test
    fun aBlockListOfFixes() {
        assertEquals(listOf("SA-1", "SA-2"), parseTopLevel("fixes:\n  - SA-1\n  - SA-2\nseverity: bug\n")["fixes"])
        assertEquals(listOf("SA-1"), parseTopLevel("fixes:\n- SA-1\n")["fixes"])
    }

    private fun approved(text: String): Map<String, String> {
        val file = dir.resolve("approved.yml")
        Files.write(file, text.toByteArray())
        return readApproved(file)
    }

    // The same text the server's ApprovedPlugins writes: its test (ApprovedPluginsTest) pins the writer to this exact string.
    @Test
    fun readsWhatTheCoreWrites() {
        val written = "# Plugin jars the admin approved: plugin name -> SHA-256 of the jar.\n" +
            "# Written by the console commands 'plugins approve' and 'plugins revoke'; a change applies at the next start.\n" +
            "approved:\n  \"Alpha\": \"${"a".repeat(64)}\"\n  \"Beta-2\": \"${"b".repeat(64)}\"\n"
        assertEquals(mapOf("Alpha" to "a".repeat(64), "Beta-2" to "b".repeat(64)), approved(written))
    }

    @Test
    fun readsHandWrittenVariantsAndEmptyFiles() {
        assertEquals(mapOf("X" to "ab"), approved("approved:\n  X: AB\n"))
        assertEquals(mapOf("X" to "ab"), approved("approved:\n  'X': 'ab'   # c\n"))
        assertEquals(emptyMap(), approved("approved: {}\n"))
        assertEquals(emptyMap(), approved(""))
        assertEquals(emptyMap(), readApproved(dir.resolve("missing.yml")))
        assertEquals(emptyMap(), approved("other:\n  X: ab\n"))
    }

    // Each fixture with what the core's reader (ApprovedPlugins.read, kaml) answers, taken by running it: a file the core cannot
    // read approves nothing (null here). The launcher must never approve more than the core does.
    private val fixtures: List<Triple<String, String, Map<String, String>?>> = listOf(
        Triple("nested map", "approved:\n  disabled:\n    Fix1: \"S\"\n", emptyMap()),
        Triple("list under approved", "approved:\n  - Fix1\n", null),
        Triple("comments", "# c\napproved:  # trailing\n  # inner\n  Fix1: \"S\" # c\n\n  Fix2: 'T'\n", mapOf("Fix1" to "s", "Fix2" to "t")),
        Triple("quotes", "approved:\n  \"Fix 1\": \"S\"\n  'Fix2': 'T'\n  Fix3: U\n", mapOf("Fix 1" to "s", "Fix2" to "t", "Fix3" to "u")),
        Triple("tab indentation", "approved:\n\tFix1: \"S\"\n", null),
        Triple("blank lines", "\napproved:\n\n  Fix1: \"S\"\n\n\n  Fix2: \"T\"\n", mapOf("Fix1" to "s", "Fix2" to "t")),
        Triple("duplicate child", "approved:\n  Fix1: \"S\"\n  Fix1: \"T\"\n", null),
        Triple("duplicate approved", "approved:\n  Fix1: S\napproved:\n  Fix2: T\n", null),
        Triple("scalars around a nested map", "approved:\n  Fix1: S\n  group:\n    Fix2: T\n  Fix3: U\n", mapOf("Fix1" to "s", "Fix3" to "u")),
        Triple("empty flow map", "approved: {}  # none\n", emptyMap()),
        Triple("quoted flow map", "approved: \"{Fix1: S}\"\n", null),
        Triple("single-quoted flow map", "approved: '{Fix1: S}'\n", null),
        Triple("no value", "approved:\n", emptyMap()),
        Triple("flow values", "approved:\n  Fix1: [S]\n  Fix2: {a: b}\n  Fix3: T\n", mapOf("Fix3" to "t")),
        Triple("uneven indentation", "approved:\n    Fix1: S\n  Fix2: T\n", null),
        Triple("another key after", "approved:\n  Fix1: S\nother:\n  Fix2: T\n", mapOf("Fix1" to "s")),
        Triple("deeper line after a scalar", "approved:\n  Fix1: S\n    Fix2: T\n", null),
        Triple("list as an entry value", "approved:\n  Fix1:\n    - S\n  Fix2: T\n", mapOf("Fix2" to "t")),
        Triple("value trimmed and lower-cased", "approved:\n  Fix1: \"  AB  \"\n", mapOf("Fix1" to "ab")),
        Triple("entry without value", "approved:\n  Fix1:\n  Fix2: T\n", mapOf("Fix2" to "t")),
    )

    @Test
    fun approvesExactlyWhatTheCoresReaderApproves() {
        for ((name, text, core) in fixtures) assertEquals(core ?: emptyMap(), approved(text), name)
    }

    // The core would approve this (kaml reads a flow map), but its writer never produces it: the launcher is stricter on purpose.
    @Test
    fun aFlowMapWithEntriesApprovesNothing() {
        assertEquals(emptyMap(), approved("approved: {Fix1: S}\n"))
    }

    @Test
    fun aNestedEntryIsNotApproved() {
        assertEquals(emptyMap(), approved("approved:\n  disabled:\n    Fix1: \"${"a".repeat(64)}\"\n"))
    }
}
