package xyz.felismp.shoparchive.server.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Test-only file at version 3 with `file-version` (the key name data files will use), to prove the migration chain. */
private object Sample : YamlConfigFile<Values>(
    versionKey = "file-version",
    currentVersion = 3,
    steps = listOf(
        // 1 -> 2: `title` became `name`
        MigrationStep(1) { m -> if ("title" in m) m - "title" + ("name" to m["title"]) else m },
        // 2 -> 3: `timeout-s` (seconds) became `timeout-ms` (milliseconds)
        MigrationStep(2) { m ->
            val seconds = m["timeout-s"] as? String
            if (seconds == null) m else m - "timeout-s" + ("timeout-ms" to (seconds.toLong() * 1000).toString())
        },
    ),
) {
    override val path = "data/sample.yml"
    override val title = "Sample"

    val name = StringKey("name", "unnamed", "Display name.")
    val timeoutMs = IntKey("timeout-ms", 1000, "Timeout in milliseconds.", min = 1, max = 3_600_000)
    val keys: List<Key<*>> = listOf(name, timeoutMs)

    override fun resolve(content: Map<String, Any?>, warn: Warn) = resolveKeys(keys, flatten(content), warn)
    override fun renderBody(value: Values) = renderYamlKeys(keys, value)
}

class MigrationTest {
    @TempDir
    lateinit var root: Path

    private val log = RecordingLog()

    @BeforeTest
    fun setUp() {
        prepareRoot(root)
    }

    private fun load() = loadConfigFile(root, Sample, log, FIXED_CLOCK)

    @Test
    fun versionOneFileRunsTheWholeChainThenIsBackedUpAndRewritten() {
        val old = "file-version: 1\ntitle: Corner Shop\ntimeout-s: 5\n"
        root.write("data/sample.yml", old)

        val result = load()

        assertEquals("Corner Shop", result.value[Sample.name])
        assertEquals(5000, result.value[Sample.timeoutMs])
        assertEquals(FileState.REWRITTEN, result.state)
        val rewritten = root.text("data/sample.yml")
        assertTrue(rewritten.lines().first { !it.startsWith("#") } == "file-version: 3", rewritten)
        assertTrue("name: Corner Shop" in rewritten && "timeout-ms: 5000" in rewritten, rewritten)
        assertTrue("title" !in rewritten && "timeout-s" !in rewritten, rewritten)
        assertContentEquals(old.toByteArray(), Files.readAllBytes(root.resolve("data/migration/$FIXED_STAMP/data/sample.yml")))
        assertEquals(emptyList(), log.warnings)
    }

    @Test
    fun versionTwoFileRunsOnlyTheLastStep() {
        // If step 1 ran on a v2 file it would turn `title` into `name` and replace "Kept".
        root.write("data/sample.yml", "file-version: 2\nname: Kept\ntitle: Not renamed\ntimeout-s: 7\n")

        val result = load()

        assertEquals("Kept", result.value[Sample.name])
        assertEquals(7000, result.value[Sample.timeoutMs])
        assertEquals(FileState.REWRITTEN, result.state)
    }

    @Test
    fun currentVersionFileIsLeftAlone() {
        load() // creates it
        val bytes = Files.readAllBytes(root.resolve("data/sample.yml"))

        val result = load()

        assertEquals(FileState.UNCHANGED, result.state)
        assertContentEquals(bytes, Files.readAllBytes(root.resolve("data/sample.yml")))
        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun newerVersionIsRefusedAndTheFileIsUntouched() {
        val future = "file-version: 4\nname: X\n"
        root.write("data/sample.yml", future)

        val e = assertFailsWith<ConfigFileException> { load() }

        assertTrue("file-version 4" in e.message!!, e.message)
        assertEquals(future, root.text("data/sample.yml"))
        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun aMissingFileIsCreatedAtTheCurrentVersionWithoutBackup() {
        val result = load()

        assertEquals(FileState.CREATED, result.state)
        assertTrue("file-version: 3\n" in root.text("data/sample.yml"))
        assertEquals(emptyList(), root.backups())
    }

    @Test
    fun aChainWithAGapOrTheWrongEndIsRejectedWhenTheDefinitionIsBuilt() {
        fun definition(current: Int, vararg from: Int) = object : YamlConfigFile<Values>("config-version", current, from.map { MigrationStep(it) { m -> m } }) {
            override val path = "x.yml"
            override val title = "x"
            override fun resolve(content: Map<String, Any?>, warn: Warn) = resolveKeys(emptyList(), emptyMap(), warn)
            override fun renderBody(value: Values) = ""
        }

        definition(1) // no steps at version 1 is fine
        definition(3, 1, 2)
        assertFailsWith<IllegalArgumentException> { definition(3, 1, 3) }
        assertFailsWith<IllegalArgumentException> { definition(3, 2, 3) }
        assertFailsWith<IllegalArgumentException> { definition(4, 1, 2) }
    }
}
