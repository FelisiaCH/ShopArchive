package xyz.felismp.shoparchive.launcher

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ArgsTest {
    private fun parse(vararg args: String) = parseArgsOrThrow(arrayOf(*args))

    private fun assertUsageError(vararg args: String) {
        val e = assertFailsWith<ArgsException>(args.joinToString(" ")) { parse(*args) }
        assertContains(e.message!!, "Usage:")
    }

    @Test
    fun noArgsMeansNoRootAndNothingForTheCore() {
        val parsed = parse()
        assertNull(parsed.root)
        assertEquals(emptyList(), parsed.coreArgs.toList())
    }

    @Test
    fun rootAloneIsTheLaunchersOwn() {
        val parsed = parse("--root", "some dir")
        assertEquals("some dir", parsed.root)
        assertEquals(emptyList(), parsed.coreArgs.toList())
    }

    @Test
    fun coreFlagsAreForwardedInTheOrderGiven() {
        val parsed = parse("--log-level", "debug", "--port", "26000", "--bind-address", "127.0.0.1")
        assertNull(parsed.root)
        assertEquals(listOf("--log-level", "debug", "--port", "26000", "--bind-address", "127.0.0.1"), parsed.coreArgs.toList())
    }

    @Test
    fun rootMixedWithCoreFlagsInAnyOrderIsNotForwarded() {
        val orders = listOf(
            parse("--root", "r", "--port", "1", "--log-level", "warn"),
            parse("--port", "1", "--root", "r", "--log-level", "warn"),
            parse("--port", "1", "--log-level", "warn", "--root", "r"),
        )
        for (parsed in orders) {
            assertEquals("r", parsed.root)
            assertEquals(listOf("--port", "1", "--log-level", "warn"), parsed.coreArgs.toList())
        }
    }

    @Test
    fun theLauncherDoesNotJudgeTheCoreValues() {
        // 70000 is for the core to refuse (exit 2 there), not the launcher.
        assertEquals(listOf("--port", "70000"), parse("--port", "70000").coreArgs.toList())
    }

    @Test
    fun missingValueIsAUsageError() {
        assertUsageError("--root")
        assertUsageError("--port")
        assertUsageError("--root", "r", "--log-level")
        assertUsageError("--bind-address")
    }

    @Test
    fun unknownFlagOrStrayWordIsAUsageError() {
        assertUsageError("--verbose")
        assertUsageError("stop")
        assertUsageError("--root", "r", "extra")
        assertUsageError("--port", "1", "--nope", "2")
    }

    @Test
    fun aFlagGivenTwiceIsAUsageError() {
        assertUsageError("--port", "1", "--port", "2")
        assertUsageError("--root", "a", "--root", "b")
    }

    @Test
    fun questionMarkInRootStillExplainsTheWindowsCodepageProblem() {
        val e = assertFailsWith<ArgsException> { parse("--root", "C:\\caf?") }
        assertContains(e.message!!, "'?'")
    }

    @Test
    fun noPluginsTakesNoValueAndIsForwardedToTheCore() {
        val parsed = parse("--port", "26000", "--no-plugins", "--root", "r")
        assertEquals("r", parsed.root)
        assertEquals(listOf("--port", "26000", "--no-plugins"), parsed.coreArgs.toList())
        assertUsageError("--no-plugins", "--no-plugins")
    }

    @Test
    fun noPatchesIsAFlagWithoutAValueForwardedToTheCore() {
        val parsed = parse("--no-patches", "--port", "1")
        assertEquals(true, parsed.noPatches)
        assertEquals(listOf("--no-patches", "--port", "1"), parsed.coreArgs.toList())
        assertEquals(false, parse().noPatches)
        assertUsageError("--no-patches", "--no-patches")
    }

    @Test
    fun ignoreHotfixMayBeRepeatedAndIsForwarded() {
        val parsed = parse("--ignore-hotfix", "SA-1", "--ignore-hotfix", "SA-2", "--ignore-hotfix", "SA-1")
        assertEquals(setOf("SA-1", "SA-2"), parsed.ignoreHotfix)
        assertEquals(listOf("--ignore-hotfix", "SA-1", "--ignore-hotfix", "SA-2"), parsed.coreArgs.toList())
    }

    @Test
    fun ignoreHotfixNeedsAnId() {
        assertUsageError("--ignore-hotfix")
        assertUsageError("--ignore-hotfix", "--port")
        assertUsageError("--ignore-hotfix", "a b")
    }
}
