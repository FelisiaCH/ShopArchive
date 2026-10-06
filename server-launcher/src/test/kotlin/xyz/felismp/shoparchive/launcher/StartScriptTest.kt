package xyz.felismp.shoparchive.launcher

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the real start.bat / start.sh from a copy of the built distribution (the `dist` task), because the
 * launcher's own unit tests cannot see what a script does before the JVM starts or what exit code it returns.
 */
class StartScriptTest {
    @TempDir
    lateinit var base: Path

    private val dist: Path = Paths.get(System.getProperty("shoparchive.dist"))
    private val isWindows = System.getProperty("os.name", "").startsWith("Windows")
    private val sh: String? = findOnPath("sh")
    private val started = ArrayList<Process>()

    private class Result(val exitCode: Int, val output: String)

    private inner class Run(val process: Process, private val outputFile: Path) {
        fun output(): String = if (Files.exists(outputFile)) String(Files.readAllBytes(outputFile), Charsets.UTF_8) else ""

        fun await(): Result {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                val output = output()
                kill(process)
                fail("script still running after ${TIMEOUT_SECONDS}s, killed; output so far:\n$output")
            }
            return Result(process.exitValue(), output())
        }

        fun awaitOutput(text: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (!output().contains(text)) {
                if (!process.isAlive || System.nanoTime() > deadline) fail("no '$text' in the output:\n${output()}")
                Thread.sleep(100)
            }
        }
    }

    @AfterEach
    fun killLeftovers() = started.forEach(::kill)

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** A fresh copy of the distribution under [name], which may contain a space. */
    private fun install(name: String): Path {
        val dir = base.resolve(name)
        Files.walk(dist).use { paths ->
            paths.forEach { source ->
                val target = dir.resolve(dist.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
        return dir
    }

    /**
     * Starts [script] in [dir] with [input] on its stdin (null: leave it open for the caller). A pipe rather than
     * `< file`: start.bat's `chcp` swallows a file redirected to stdin, so the server would never see "stop".
     * The default is "stop" for the console, then a few empty lines so the .bat's pause returns.
     * [env] is added to the child's environment; [cmdLine] replaces the plain `cmd /d /c .\start.bat` command.
     */
    private fun start(
        script: String,
        dir: Path,
        vararg args: String,
        input: String? = "stop\r\n\r\n\r\n\r\n",
        env: Map<String, String> = emptyMap(),
        cmdLine: String? = null,
    ): Run {
        // .\start.bat, not start.bat: with NoDefaultCurrentDirectoryInExePath set (it is on some machines) cmd
        // does not look in the current directory.
        val command = when {
            cmdLine != null -> listOf("cmd", "/d", "/c", cmdLine)
            script == "start.bat" -> listOf("cmd", "/d", "/c", ".\\start.bat")
            else -> listOf(sh!!, script)
        }
        val output = base.resolve("run-${started.size}.out")
        val builder = ProcessBuilder(command + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
        builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        builder.environment()["SHOPARCHIVE_MEMORY"] = "256M"
        builder.environment().putAll(env)
        val process = builder.start()
        started.add(process)
        if (input != null) {
            try {
                process.outputStream.use { it.write(input.toByteArray()) }
            } catch (e: IOException) {
                // the script already exited (a refused start never reads stdin)
            }
        }
        return Run(process, output)
    }

    private fun assumeBat() = assumeTrue(isWindows, "start.bat only runs on Windows")

    private fun assumeSh() = assumeTrue(sh != null, "no sh on PATH")

    private fun newOutside(): Path {
        val outside = Files.createDirectories(base.resolve("outside"))
        Files.write(outside.resolve("keep.txt"), "untouched".toByteArray())
        return outside
    }

    private fun assertUntouched(outside: Path) =
        Files.list(outside).use { stream -> assertEquals(listOf("keep.txt"), stream.map { it.fileName.toString() }.toList()) }

    private fun assertRefusedLeavingOutsideUntouched(script: String, dir: Path, outside: Path, linkName: String) {
        val result = start(script, dir).await()

        assertEquals(1, result.exitCode, result.output)
        assertTrue(result.output.contains("[ERROR]"), result.output)
        assertTrue(result.output.contains(linkName), result.output)
        assertUntouched(outside)
    }

    private fun assertHappyPath(script: String) {
        val dir = install("my server")

        val result = start(script, dir).await()

        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("Done"), result.output)
        assertFalse(result.output.contains("[ERROR]"), result.output)
        assertTrue(Files.isDirectory(dir.resolve("tmp/jvm")), "tmp/jvm")
        assertTrue(Files.isDirectory(dir.resolve("crash-reports")), "crash-reports")
    }

    @Test
    fun batStartsOnAFreshFolderAndCreatesItsDirectories() {
        assumeBat()
        assertHappyPath("start.bat")
    }

    @Test
    fun batRefusesTmpThatIsAJunctionAndWritesNothingThroughIt() {
        assumeBat()
        val dir = install("tmp junction")
        val outside = newOutside()
        junctionOrSkip(dir.resolve("tmp"), outside)
        try {
            assertRefusedLeavingOutsideUntouched("start.bat", dir, outside, "tmp")
        } finally {
            Files.deleteIfExists(dir.resolve("tmp")) // the junction only, never its target
        }
    }

    @Test
    fun batRefusesCrashReportsThatIsAJunctionAndCreatesNothingBeforeTheCheck() {
        assumeBat()
        val dir = install("crash junction")
        val outside = newOutside()
        junctionOrSkip(dir.resolve("crash-reports"), outside)
        try {
            assertRefusedLeavingOutsideUntouched("start.bat", dir, outside, "crash-reports")
            assertFalse(Files.exists(dir.resolve("tmp")), "the script must not create tmp before the launcher's checks")
        } finally {
            Files.deleteIfExists(dir.resolve("crash-reports"))
        }
    }

    @Test
    fun batSecondInstanceInTheSameFolderExitsOneAndTheFirstStillStopsWithZero() {
        assumeBat()
        val dir = install("two instances")
        val first = start("start.bat", dir, input = null)
        first.awaitOutput("Done")

        val second = start("start.bat", dir).await()

        assertEquals(1, second.exitCode, second.output)
        assertTrue(second.output.contains("already running"), second.output)
        first.process.outputStream.use { it.write("stop\r\n\r\n\r\n".toByteArray()) }
        val firstResult = first.await()
        assertEquals(0, firstResult.exitCode, firstResult.output)
    }

    @Test
    fun batExitsOneForAServerIdThatIsNotAUuid() {
        assumeBat()
        val dir = install("bad server id")
        Files.write(Files.createDirectories(dir.resolve("data")).resolve("server-id"), "not-a-uuid".toByteArray())

        val result = start("start.bat", dir).await()

        assertEquals(1, result.exitCode, result.output)
        assertTrue(result.output.contains("not a valid UUID"), result.output)
    }

    @Test
    fun batExitsTwoForABadCommandLine() {
        assumeBat()
        val result = start("start.bat", install("bad args"), "--bogus").await()

        assertEquals(2, result.exitCode, result.output) // Args.kt: usage error
        assertTrue(result.output.contains("Usage:"), result.output)
    }

    // cmd takes %ERRORLEVEL% from a real environment variable of that name if the caller has one, so a
    // start.bat that did not clear it would return the caller's value instead of Java's exit code.
    private fun inheritedErrorLevel(value: String) = mapOf("ERRORLEVEL" to value)

    @Test
    fun batExitsTwoForABadCommandLineEvenIfTheCallerHasErrorlevelZero() {
        assumeBat()
        val result = start("start.bat", install("bad args el0"), "--bogus", env = inheritedErrorLevel("0")).await()

        assertEquals(2, result.exitCode, result.output)
        assertTrue(result.output.contains("Usage:"), result.output)
    }

    @Test
    fun batExitsTwoForABadCommandLineEvenIfTheCallerHasErrorlevel77() {
        assumeBat()
        val result = start("start.bat", install("bad args el77"), "--bogus", env = inheritedErrorLevel("77")).await()

        assertEquals(2, result.exitCode, result.output)
        assertTrue(result.output.contains("Usage:"), result.output)
    }

    @Test
    fun batStopsWithZeroEvenIfTheCallerHasErrorlevel77() {
        assumeBat()
        val result = start("start.bat", install("happy el77"), env = inheritedErrorLevel("77")).await()

        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("Done"), result.output)
    }

    @Test
    fun batExitsOneForARefusalEvenIfTheCallerHasErrorlevelZero() {
        assumeBat()
        val dir = install("bad server id el0")
        Files.write(Files.createDirectories(dir.resolve("data")).resolve("server-id"), "not-a-uuid".toByteArray())

        val result = start("start.bat", dir, env = inheritedErrorLevel("0")).await()

        assertEquals(1, result.exitCode, result.output)
        assertTrue(result.output.contains("not a valid UUID"), result.output)
    }

    @Test
    fun batLeavesTheCallersEnvironmentAlone() {
        assumeBat()
        // `call` keeps the script in the caller's cmd, so whatever it set and did not undo shows up in `set`.
        val result = start(
            "start.bat", install("env leak"),
            env = inheritedErrorLevel("77"), cmdLine = "call .\\start.bat --bogus & set",
        ).await()

        val variables = result.output.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertTrue(result.output.contains("Usage:"), result.output)
        assertEquals("77", variables["ERRORLEVEL"], result.output)
        for (name in listOf("EXITCODE", "DRIVE", "JAVA")) assertFalse(name in variables, "$name leaked:\n${result.output}")
    }

    @Test
    fun batRefusesRootOtherThanItsOwnFolder() {
        assumeBat()
        val dir = install("script folder")
        val other = Files.createDirectories(base.resolve("other root"))

        val result = start("start.bat", dir, "--root", other.toString()).await()

        assertEquals(1, result.exitCode, result.output)
        assertTrue(result.output.contains("outside the root"), result.output)
        Files.list(other).use { assertEquals(emptyList(), it.toList()) }
        assertFalse(Files.exists(dir.resolve("tmp")), "nothing created in the script folder either")
    }

    @Test
    fun shStartsOnAFreshFolderAndCreatesItsDirectories() {
        assumeSh()
        assertHappyPath("start.sh")
    }

    @Test
    fun shRefusesTmpThatIsALinkAndWritesNothingThroughIt() {
        assumeSh()
        val dir = install("tmp link")
        val outside = newOutside()
        dirLinkOrSkip(dir.resolve("tmp"), outside)
        try {
            assertRefusedLeavingOutsideUntouched("start.sh", dir, outside, "tmp")
        } finally {
            Files.deleteIfExists(dir.resolve("tmp"))
        }
    }

    private fun findOnPath(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            .flatMap { listOf(File(it, name), File(it, "$name.exe")) }
            .firstOrNull { it.isFile }?.path

    private companion object {
        const val TIMEOUT_SECONDS = 90L
    }
}
